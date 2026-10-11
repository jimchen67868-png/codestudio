package javax.tools

interface Diagnostic<S> {
    enum class Kind { ERROR, WARNING, MANDATORY_WARNING, NOTE, OTHER }
}
