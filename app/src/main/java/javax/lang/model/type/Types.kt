package javax.lang.model.type

interface TypeMirror
interface TypeVisitor<R, P>
interface ArrayType : TypeMirror
interface DeclaredType : TypeMirror
interface ErrorType : DeclaredType
interface ExecutableType : TypeMirror
interface IntersectionType : TypeMirror
interface NoType : TypeMirror
interface NullType : TypeMirror
interface PrimitiveType : TypeMirror
interface TypeVariable : TypeMirror
interface UnionType : TypeMirror
interface WildcardType : TypeMirror
