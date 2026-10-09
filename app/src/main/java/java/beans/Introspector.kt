package java.beans

/** Minimal stub: only the JavaBeans name helper; Android has no bean introspection. */
object Introspector {
    @JvmStatic fun decapitalize(name: String?): String? {
        if (name == null || name.isEmpty()) return name
        if (name.length > 1 && name[0].isUpperCase() && name[1].isUpperCase()) return name
        return name.substring(0, 1).lowercase() + name.substring(1)
    }
}
