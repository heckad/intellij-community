// "Merge with 'case null'" "GENERIC_ERROR_OR_WARNING"
class C {
  void foo(Object o) {
    switch (o) {
      case null, Number n && n.intValue() == 42 -> bar("A");
      case String s -> bar("B");
        default -> bar("C");
    }
  }
  void bar(String s){}
}