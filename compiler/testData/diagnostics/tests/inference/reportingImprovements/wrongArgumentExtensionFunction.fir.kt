// !DIAGNOSTICS: -UNUSED_PARAMETER

class A
fun A.fn(b: Int): Nothing = TODO()

fun A.run() {
    "".apply { <!INAPPLICABLE_CANDIDATE!>fn<!>("") }
}