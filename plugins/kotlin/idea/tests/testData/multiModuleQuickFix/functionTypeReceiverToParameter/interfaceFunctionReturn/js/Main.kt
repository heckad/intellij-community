// "Convert 'Int.() -> Unit' to '(Int) -> Unit'" "true"
external interface E {
    fun boo(p: String.() -> Unit): In<caret>t.() -> Unit
}

// ERROR: Function types with receiver are prohibited in external declarations