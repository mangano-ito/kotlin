// FILE: MyJavaEnum.java
public enum MyJavaEnum {}

// FILE: test.kt
open enum class MyEnum() {
    A()
}

enum class MyEnum2() {}

class MyClass(): <!INAPPLICABLE_CANDIDATE!>MyEnum2<!>() {}

class MyClass2(): <!UNRESOLVED_REFERENCE!>MyJavaEnum<!>() {}
