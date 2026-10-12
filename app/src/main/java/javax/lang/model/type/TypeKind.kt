package javax.lang.model.type

enum class TypeKind {
    BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE, VOID, NONE, NULL, ARRAY,
    DECLARED, ERROR, TYPEVAR, WILDCARD, PACKAGE, EXECUTABLE, OTHER, UNION,
    INTERSECTION, MODULE;

    fun isPrimitive(): Boolean = ordinal <= DOUBLE.ordinal
}
