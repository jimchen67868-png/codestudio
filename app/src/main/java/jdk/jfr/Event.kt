package jdk.jfr

open class Event {
    open fun begin() {}
    open fun end() {}
    open fun commit() {}
    open fun isEnabled(): Boolean = false
    open fun shouldCommit(): Boolean = false
    open fun set(index: Int, value: Any?) {}
}
