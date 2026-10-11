package jdk.jfr

open class EventType {
    open fun isEnabled(): Boolean = false

    companion object {
        @JvmStatic
        fun getEventType(c: Class<*>): EventType = EventType()
    }
}
