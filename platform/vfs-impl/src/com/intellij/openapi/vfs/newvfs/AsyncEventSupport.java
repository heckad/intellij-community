// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vfs.newvfs;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.PingProgress;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.AsyncFileListener;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.impl.VirtualFileManagerImpl;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFSImpl;
import com.intellij.util.containers.CollectionFactory;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashingStrategy;
import it.unimi.dsi.fastutil.Hash;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
public final class AsyncEventSupport {
  private static final Logger LOG = Logger.getInstance(AsyncEventSupport.class);

  @ApiStatus.Internal
  public static final ExtensionPointName<AsyncFileListener> EP_NAME = new ExtensionPointName<>("com.intellij.vfs.asyncListener");

  //
  // VFS events could be fired with any unpredictable nesting.
  // One may fire new events (for example using syncPublisher()) while processing current events in before() or after().
  // So, we cannot rely on listener's execution and nesting order in this case and we should explicitly mark events that supposed to be processed asynchronously.
  //
  @NotNull
  private static final Set<List<? extends VFileEvent>> ourAsyncProcessedEvents = CollectionFactory.createCustomHashingStrategySet(HashingStrategy.identity());
  @NotNull
  private static final Map<List<? extends VFileEvent>, List<AsyncFileListener.ChangeApplier>> ourAppliers = CollectionFactory.createSmallMemoryFootprintMap(1);

  public static void startListening() {
    Application app = ApplicationManager.getApplication();
    Disposer.register(app, () -> ensureAllEventsProcessed());

    app.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
      @Override
      public void before(@NotNull List<? extends @NotNull VFileEvent> events) {
        if (ourAsyncProcessedEvents.contains(events)) {
          return;
        }
        List<AsyncFileListener.ChangeApplier> appliers = runAsyncListeners(events);
        ourAppliers.put(events, appliers);
        beforeVfsChange(appliers);
      }

      @Override
      public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
        if (ourAsyncProcessedEvents.contains(events)) {
          return;
        }
        List<AsyncFileListener.ChangeApplier> appliers = ourAppliers.remove(events);
        afterVfsChange(appliers);
      }
    });
  }

  private static void ensureAllEventsProcessed() {
    LOG.assertTrue(ourAsyncProcessedEvents.isEmpty(), "Some vfs events were not properly processed " + ourAsyncProcessedEvents);
    LOG.assertTrue(ourAppliers.isEmpty(), "Some vfs events were not processed after vfs change performed " + ourAppliers);
  }

  @NotNull
  static List<AsyncFileListener.ChangeApplier> runAsyncListeners(@NotNull List<? extends VFileEvent> events) {
    if (events.isEmpty()) return Collections.emptyList();

    if (LOG.isDebugEnabled()) {
      LOG.debug("Processing " + events);
    }

    List<AsyncFileListener.ChangeApplier> appliers = new ArrayList<>();
    List<AsyncFileListener> allListeners = new ArrayList<>(EP_NAME.getExtensionList());
    ((VirtualFileManagerImpl)VirtualFileManager.getInstance()).addAsyncFileListenersTo(allListeners);
    for (AsyncFileListener listener : allListeners) {
      ProgressManager.checkCanceled();
      long startNs = System.nanoTime();
      boolean canceled = false;
      try {
        ReadAction.run(() -> ContainerUtil.addIfNotNull(appliers, listener.prepareChange(events)));
      }
      catch (ProcessCanceledException e) {
        canceled = true;
        throw e;
      }
      catch (Throwable e) {
        LOG.error(e);
      }
      finally {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        if (elapsedMs > 10_000) {
          LOG.warn(listener + " took too long (" + elapsedMs + "ms) on " + events.size() + " events" + (canceled ? ", canceled" : ""));
        }
      }
    }
    return appliers;
  }

  public static void markAsynchronouslyProcessedEvents(@NotNull List<? extends VFileEvent> events) {
    ourAsyncProcessedEvents.add(events);
  }

  public static void unmarkAsynchronouslyProcessedEvents(@NotNull List<? extends VFileEvent> events) {
    LOG.assertTrue(ourAsyncProcessedEvents.remove(events));
  }

  private static void beforeVfsChange(List<? extends AsyncFileListener.ChangeApplier> appliers) {
    for (AsyncFileListener.ChangeApplier applier : appliers) {
      PingProgress.interactWithEdtProgress();
      try {
        applier.beforeVfsChange();
      }
      catch (Throwable e) {
        LOG.error(e);
      }
    }
  }

  private static void afterVfsChange(@NotNull List<? extends AsyncFileListener.ChangeApplier> appliers) {
    for (AsyncFileListener.ChangeApplier applier : appliers) {
      PingProgress.interactWithEdtProgress();
      try {
        applier.afterVfsChange();
      }
      catch (ProcessCanceledException e) {
        throw e;
      }
      catch (Throwable e) {
        LOG.error(e);
      }
    }
  }

  static void processEventsFromRefresh(@NotNull List<? extends CompoundVFileEvent> events,
                                       @NotNull List<? extends AsyncFileListener.ChangeApplier> appliers,
                                       boolean asyncEventProcessing) {
    ApplicationManager.getApplication().assertWriteAccessAllowed();
    beforeVfsChange(appliers);
    try {
      ((PersistentFSImpl) PersistentFS.getInstance()).processEventsImpl(events, asyncEventProcessing);
    }
    catch (Throwable e) {
      LOG.error(e);
    }
    finally {
      afterVfsChange(appliers);
    }
  }

  private static class IdentityHashingStrategy implements Hash.Strategy<List<? extends VFileEvent>> {
    @Override
    public int hashCode(List<? extends VFileEvent> o) {
      return o == null ? 0 : System.identityHashCode(o);
    }

    @Override
    public boolean equals(@Nullable List<? extends VFileEvent> a,
                          @Nullable List<? extends VFileEvent> b) {
      return a == b;
    }
  }
}
