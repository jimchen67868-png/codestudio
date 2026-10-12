package javax.lang.model.element

enum class ElementKind {
    PACKAGE, ENUM, CLASS, ANNOTATION_TYPE, INTERFACE, CONSTRUCTOR, METHOD, FIELD,
    ENUM_CONSTANT, PARAMETER, LOCAL_VARIABLE, EXCEPTION_PARAMETER, STATIC_INIT,
    INSTANCE_INIT, TYPE_PARAMETER, OTHER, RESOURCE_VARIABLE, MODULE, RECORD,
    RECORD_COMPONENT, BINDING_VARIABLE;

    fun isClass(): Boolean = this == CLASS || this == ENUM || this == RECORD
    fun isInterface(): Boolean = this == INTERFACE || this == ANNOTATION_TYPE
    fun isField(): Boolean = this == FIELD || this == ENUM_CONSTANT
}
