package javax.lang.model.element

enum class Modifier {
    PUBLIC, PROTECTED, PRIVATE, ABSTRACT, DEFAULT, STATIC, SEALED, NON_SEALED,
    FINAL, TRANSIENT, VOLATILE, SYNCHRONIZED, NATIVE, STRICTFP;

    override fun toString(): String = name.lowercase().replace('_', '-')
}
