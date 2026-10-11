package javax.tools

enum class StandardLocation : JavaFileManager.Location {
    CLASS_OUTPUT, SOURCE_OUTPUT, CLASS_PATH, SOURCE_PATH,
    ANNOTATION_PROCESSOR_PATH, ANNOTATION_PROCESSOR_MODULE_PATH,
    PLATFORM_CLASS_PATH, NATIVE_HEADER_OUTPUT, MODULE_SOURCE_PATH,
    UPGRADE_MODULE_PATH, SYSTEM_MODULES, MODULE_PATH, PATCH_MODULE_PATH;

    override fun getName(): String = name
    override fun isOutputLocation(): Boolean =
        this == CLASS_OUTPUT || this == SOURCE_OUTPUT || this == NATIVE_HEADER_OUTPUT
}
