/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.refactoring.rename;

import com.intellij.openapi.extensions.Extensions;
import com.intellij.pom.references.PomService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiTarget;
import com.intellij.psi.targets.AliasingPsiTarget;
import com.intellij.psi.targets.AliasingPsiTargetMapper;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class RenameAliasingPomTargetProcessor extends RenamePsiElementProcessor {

  @Override
  public boolean canProcessElement(@Nullable PsiElement element) {
    return element instanceof PsiTarget; // TODO [sergey.vasiliyev]
  }

  @Override
  public void prepareRenaming(PsiElement element, String newName, Map<PsiElement, String> allRenames) {
    if (element instanceof PsiTarget) {
      for (AliasingPsiTargetMapper mapper : Extensions.getExtensions(AliasingPsiTargetMapper.EP_NAME)) {
        for (AliasingPsiTarget psiTarget : mapper.getTargets((PsiTarget)element)) {
          allRenames.put(PomService.convertToPsi(psiTarget), psiTarget.getNameAlias(newName));
        }
      }
    }
  }
}
