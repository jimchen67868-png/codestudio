package javax.management

/** Android has no JMX. Minimal type so classes referencing it can load. */
open class Notification(private val typeName: String, val source: Any?, val sequenceNumber: Long) {
    fun getType(): String = typeName
}
