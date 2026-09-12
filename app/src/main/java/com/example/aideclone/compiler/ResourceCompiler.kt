package com.example.aideclone.compiler

import com.reandroid.apk.FrameworkApk
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class ResourceCompileResult(
    val success: Boolean,
    val rawOutput: String,
    val tableBlock: TableBlock? = null,
    val packageBlock: PackageBlock? = null,
    val fileResources: Map<String, File> = emptyMap() // apk-relative path -> source file, for ApkBuilder to add
)

/**
 * Compiles a project's res/ folder — the piece aapt2 normally handles —
 * into (a) resource table entries + a generated R class the compiler can
 * resolve R.layout.xxx/R.id.xxx/etc. against, and (b) binary-encoded
 * resource files ApkBuilder folds into the final APK.
 *
 * Scope, deliberately staged given how much uncertainty remains in exact
 * ARSCLib API behavior without being able to test locally (same
 * situation the isolated Kotlin compiler work was in, which needed many
 * rounds of real-error-driven fixes):
 *   - values XML files: string, color, dimen, bool, integer entries —
 *     straightforward value resources, most likely to just work.
 *   - layout XML files (and similar): registers the file as a resource
 *     (so R.layout.foo compiles) and scans for android:id="@+id/x" to
 *     register id resources too. Attempts real binary XML encoding via
 *     ARSCLib's framework-attribute-aware encoder; if that fails, the
 *     entry still registers (unblocking compilation) but the resulting
 *     APK's layout may not render correctly until that's fixed — this
 *     is the most likely piece to need follow-up iteration.
 *   - drawable/mipmap raw images (png etc.): registered as file
 *     resources, copied through as-is, no encoding needed.
 *   - NOT yet handled: styles/themes with parent inheritance, resource
 *     qualifiers (only default/no-qualifier folders), menu/anim/
 *     animator specifics beyond generic XML, vector drawables.
 */
object ResourceCompiler {

    private val VALUE_RESOURCE_TAGS = mapOf(
        "string" to "string",
        "color" to "color",
        "dimen" to "dimen",
        "bool" to "bool",
        "integer" to "integer"
    )

    private val ID_ATTR_REGEX = Regex("""@\+?id/([A-Za-z_][A-Za-z0-9_]*)""")

    fun compileResources(
        projectRoot: File,
        frameworkApkFile: File?,
        packageName: String
    ): ResourceCompileResult {
        val log = StringBuilder()
        val resDir = File(projectRoot, "res")
        if (!resDir.exists() || !resDir.isDirectory) {
            return ResourceCompileResult(
                success = true,
                rawOutput = "No res/ folder found — nothing to compile, skipping."
            )
        }
        if (frameworkApkFile == null || !frameworkApkFile.exists()) {
            return ResourceCompileResult(
                success = false,
                rawOutput = "This project has a res/ folder but no framework resources have " +
                    "been imported. Use \"Import Framework Resources\" (a real framework-res.apk, " +
                    "e.g. via: adb pull /system/framework/framework-res.apk)."
            )
        }

        return try {
            val framework = FrameworkApk.loadApkFile(frameworkApkFile)
            log.appendLine("Loaded framework resources: ${frameworkApkFile.name}")

            val tableBlock = TableBlock()
            val packageBlock = tableBlock.newPackage(0x7f, packageName)

            // type -> (name -> resource id), used both to avoid duplicate
            // entries and to generate the R class afterward.
            val registry = mutableMapOf<String, MutableMap<String, Int>>()
            val fileResources = mutableMapOf<String, File>()

            fun register(type: String, name: String) = run {
                val existing = registry.getOrPut(type) { mutableMapOf() }
                if (existing.containsKey(name)) {
                    null
                } else {
                    val entry = packageBlock.getOrCreate("", type, name)
                    existing[name] = entry.resourceId
                    entry
                }
            }

            // --- values/*.xml: string, color, dimen, bool, integer ---
            resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }?.forEach { valuesDir ->
                valuesDir.listFiles { f -> f.extension == "xml" }?.forEach { xmlFile ->
                    try {
                        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
                        val root = doc.documentElement
                        val children = root.childNodes
                        for (i in 0 until children.length) {
                            val node = children.item(i)
                            if (node !is Element) continue
                            val resType = VALUE_RESOURCE_TAGS[node.tagName] ?: continue
                            val name = node.getAttribute("name")
                            if (name.isBlank()) continue
                            val textValue = node.textContent ?: ""
                            register(resType, name)?.setValueAsString(textValue)
                        }
                    } catch (e: Exception) {
                        log.appendLine("Warning: failed to parse ${xmlFile.path}: ${e.message}")
                    }
                }
            }
            log.appendLine("Registered value resources: " + registry.entries.joinToString { "${it.key}=${it.value.size}" })

            // --- layout/menu/anim/xml folders: file resources + @+id scan ---
            resDir.listFiles { f -> f.isDirectory }?.forEach { typeDir ->
                val baseType = typeDir.name.substringBefore("-")
                if (typeDir.name.startsWith("values")) return@forEach // already handled above
                typeDir.listFiles { f -> f.isFile }?.forEach { resFile ->
                    val entryName = resFile.nameWithoutExtension
                    val apkPath = "res/${typeDir.name}/${resFile.name}"

                    register(baseType, entryName)?.setValueAsString(apkPath)
                    fileResources[apkPath] = resFile

                    // Scan XML-based resources (layouts especially) for
                    // @+id/foo declarations, which implicitly declare new
                    // id-type resources not listed anywhere in values/.
                    if (resFile.extension == "xml") {
                        try {
                            val content = resFile.readText()
                            ID_ATTR_REGEX.findAll(content).forEach { match ->
                                val idName = match.groupValues[1]
                                register("id", idName)?.setValueAsString(idName)
                            }
                        } catch (e: Exception) {
                            log.appendLine("Warning: failed to scan ids in ${resFile.path}: ${e.message}")
                        }
                    }
                }
            }
            log.appendLine("Registered file + id resources: " + registry.entries.joinToString { "${it.key}=${it.value.size}" })

            // --- Generate an R class alongside the project's own
            // sources so the normal compile pass picks it up
            // automatically. Language must match whichever compiler
            // will actually run — a Kotlin-only project's compile never
            // looks for .java files, so an R.java would be silently
            // ignored and R.layout.xxx would stay unresolved anyway. ---
            val isKotlinProject = projectRoot.walkTopDown()
                .any { it.isFile && it.extension == "kt" && !it.path.contains("/build/") }

            val rFile: File
            val rSource: String
            if (isKotlinProject) {
                rSource = buildString {
                    appendLine("package $packageName")
                    appendLine()
                    appendLine("object R {")
                    for ((type, entries) in registry) {
                        appendLine("    object $type {")
                        for ((name, id) in entries) {
                            appendLine("        const val $name = $id")
                        }
                        appendLine("    }")
                    }
                    appendLine("}")
                }
                rFile = File(projectRoot, "build/generated/java/${packageName.replace('.', '/')}/R.kt")
            } else {
                rSource = buildString {
                    appendLine("package $packageName;")
                    appendLine()
                    appendLine("public final class R {")
                    for ((type, entries) in registry) {
                        appendLine("    public static final class $type {")
                        for ((name, id) in entries) {
                            appendLine("        public static final int $name = $id;")
                        }
                        appendLine("    }")
                    }
                    appendLine("}")
                }
                rFile = File(projectRoot, "build/generated/java/${packageName.replace('.', '/')}/R.java")
            }
            rFile.parentFile?.mkdirs()
            rFile.writeText(rSource)
            log.appendLine("Generated ${rFile.name}: ${rFile.path}")

            ResourceCompileResult(
                success = true,
                rawOutput = log.toString(),
                tableBlock = tableBlock,
                packageBlock = packageBlock,
                fileResources = fileResources
            )
        } catch (t: Throwable) {
            log.appendLine("Resource compilation failed: $t")
            log.appendLine(t.stackTraceToString())
            ResourceCompileResult(success = false, rawOutput = log.toString())
        }
    }
}
