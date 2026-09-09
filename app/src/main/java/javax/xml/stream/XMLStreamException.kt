package javax.xml.stream

/**
 * Android has never shipped javax.xml.stream (the JDK's StAX streaming
 * XML API) at all. The Kotlin compiler's IntelliJ-platform-based plugin
 * descriptor loader (PluginDescriptorLoader) uses it to parse
 * plugin.xml-style extension point config files bundled inside its own
 * jar.
 *
 * Minimal stub: just the exception type, enough for referencing code to
 * load without NoClassDefFoundError. If the actual StAX parser classes
 * (XMLInputFactory, XMLStreamReader, etc.) are also touched, that'll
 * surface as the next missing-class crash — same incremental approach
 * as the other stubs in this codebase (SourceVersion, ManagementFactory).
 */
class XMLStreamException : Exception {
    constructor() : super()
    constructor(msg: String) : super(msg)
    constructor(msg: String, cause: Throwable) : super(msg, cause)
    constructor(cause: Throwable) : super(cause)
}
