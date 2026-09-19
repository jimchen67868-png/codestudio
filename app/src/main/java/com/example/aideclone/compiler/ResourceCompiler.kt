package com.example.aideclone.compiler

import com.reandroid.apk.FrameworkApk
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.xml.kxml2.KXmlParser
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ResourceCompileResult(
    val success: Boolean,
    val rawOutput: String,
    val tableBlock: TableBlock? = null,
    val packageBlock: PackageBlock? = null,
    val fileResources: Map<String, File> = emptyMap() // apk-relative path -> source file, for ApkBuilder to add
)

/** A source of resources to merge in: the app's own project, or a library's bundled res/. */
data class ResourceSource(val packageName: String, val resDir: File)

/**
 * Compiles res/ folders — the app's own AND every imported library's
 * bundled resources — into (a) one shared resource table + a generated R
 * class per package the compiler can resolve R.layout.xxx/R.id.xxx/
 * androidx.appcompat.R.drawable.xxx/etc. against, and (b) binary-encoded
 * resource files ApkBuilder folds into the final APK.
 *
 * Why libraries need this too: AndroidX/Jetpack libraries ship their own
 * internal resources (drawables, styles their widgets use) inside their
 * AAR, separate from their compiled classes.jar. A real Android build
 * merges ALL of these — the app's own resources and every library
 * dependency's — into one shared resource table sharing one package ID
 * (0x7f), then generates a per-library R class whose fields are NOT
 * compile-time-final (deliberately, so the actual numeric IDs can be
 * reassigned at this final link step without needing to recompile the
 * library's already-compiled bytecode, which only does non-inlined field
 * reads). That's what makes this approach valid: we don't need to know
 * or preserve any library's "original" resource IDs, just generate a
 * structurally-matching R class referencing our own consistent ones.
 *
 * Scope, deliberately staged given how much uncertainty remains in exact
 * ARSCLib API behavior without being able to test locally (same
 * situation the isolated Kotlin compiler work was in, which needed many
 * rounds of real-error-driven fixes):
 *   - values XML files: string, color, dimen, bool, integer entries —
 *     straightforward value resources, most likely to just work.
 *   - layout XML files (and similar): registers the file as a resource
 *     (so R.layout.foo compiles) and scans for android:id="@+id/x" to
 *     register id resources too. Layout content is copied through as
 *     literal text, not real binary-encoded XML — the resulting APK's
 *     layouts may not render correctly until that's added.
 *   - drawable/mipmap raw images (png etc.): registered as file
 *     resources, copied through as-is, no encoding needed.
 *   - NOT yet handled: styles/themes with parent inheritance, resource
 *     qualifiers (only default/no-qualifier folders), menu/anim/
 *     animator specifics beyond generic XML, vector drawables, attr/
 *     styleable resources (needed for custom view XML attributes).
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

    /**
     * Resource names aren't always valid identifiers as-is - style names
     * in particular conventionally use literal dots as a hierarchy
     * separator (e.g. "Theme.AppCompat.Light.DarkActionBar"), which is a
     * perfectly valid resource name but breaks Kotlin/Java syntax if
     * emitted verbatim as a field name (`const val Theme.AppCompat...`
     * parses as a dotted reference, not a declaration). Real aapt
     * replaces '.' with '_' when generating R fields for exactly this
     * reason, so field names and resource names diverge for styles by
     * design - that's expected, not a bug.
     */
    private fun sanitizeIdentifier(name: String): String = name.replace('.', '_').replace(':', '_')

    fun compileResources(
        projectRoot: File,
        frameworkApkFile: File?,
        packageName: String,
        libraries: List<ResourceSource> = emptyList()
    ): ResourceCompileResult {
        val log = StringBuilder()
        // Recursive search, not a direct File(projectRoot, "res") lookup:
        // standard Gradle projects (like ones opened from elsewhere, not
        // created via our own New Project flow) nest their actual module
        // content — including res/ — under app/src/main/res/, not at the
        // opened project root directly. Source file scanning already
        // searches recursively for exactly this reason.
        val appResDir = projectRoot.walkTopDown()
            .firstOrNull { it.isDirectory && it.name == "res" && !it.path.contains("/build/") }

        val sources = buildList {
            if (appResDir != null) add(ResourceSource(packageName, appResDir))
            addAll(libraries)
        }

        if (sources.isEmpty()) {
            return ResourceCompileResult(
                success = true,
                rawOutput = "No res/ folder found — nothing to compile, skipping."
            )
        }
        if (frameworkApkFile == null || !frameworkApkFile.exists()) {
            return ResourceCompileResult(
                success = false,
                rawOutput = "This project has resources to compile but no framework resources have " +
                    "been imported. Use \"Import Framework Resources\" (a real framework-res.apk, " +
                    "e.g. via: adb pull /system/framework/framework-res.apk)."
            )
        }

        return try {
            val frameworkApk = FrameworkApk.loadApkFile(frameworkApkFile)
            log.appendLine("Loaded framework resources: ${frameworkApkFile.name}")

            val tableBlock = TableBlock()
            // Without this, ARSCLib has no framework to resolve
            // android:-namespaced attributes or @android:/?android:attr/
            // references against during XML encoding below - every
            // single one fails with "Unknown attribute name" or
            // "Resource not found for" otherwise, regardless of how
            // correct the encoding call itself is.
            tableBlock.addFramework(frameworkApk.tableBlock)
            val packageBlock = tableBlock.newPackage(0x7f, packageName)
            val fileResources = mutableMapOf<String, File>()

            // pkg -> type -> (name -> resource id); used both to avoid
            // duplicate entries and to generate one R class per package.
            val registry = mutableMapOf<String, MutableMap<String, MutableMap<String, Int>>>()

            // pkg -> styleable name -> ordered attr names. R.styleable
            // isn't a real resources.arsc entry at all - it's a
            // compile-time-only int[] of attr IDs (plus per-attr index
            // constants) that real aapt generates purely for the R
            // class. Tracked separately here and emitted after the main
            // registry loop, once every attr's real numeric ID is known.
            val styleables = mutableMapOf<String, MutableMap<String, MutableList<String>>>()

            fun register(pkg: String, type: String, name: String) = run {
                val forPkg = registry.getOrPut(pkg) { mutableMapOf() }
                val existing = forPkg.getOrPut(type) { mutableMapOf() }
                if (existing.containsKey(name)) {
                    null
                } else {
                    val entry = packageBlock.getOrCreate("", type, name)
                    existing[name] = entry.resourceId
                    entry
                }
            }

            for (source in sources) {
                val resDir = source.resDir
                val pkg = source.packageName

                // --- values/*.xml: string, color, dimen, bool, integer,
                // plus style/attr (registered as IDs only - no attempt
                // to encode parent inheritance or item content yet, just
                // enough for R.style.xxx / R.attr.xxx to resolve instead
                // of throwing ClassNotFoundException/NoClassDefFoundError
                // at runtime for code, like AppCompatDelegateImpl, that
                // reads its own R$style fields) ---
                resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }?.forEach { valuesDir ->
                    valuesDir.listFiles { f -> f.extension == "xml" }?.forEach { xmlFile ->
                        try {
                            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
                            val children = doc.documentElement.childNodes
                            for (i in 0 until children.length) {
                                val node = children.item(i)
                                if (node !is Element) continue
                                when (node.tagName) {
                                    "style" -> {
                                        val name = node.getAttribute("name")
                                        if (name.isNotBlank()) register(pkg, "style", name)
                                    }
                                    "attr" -> {
                                        val name = node.getAttribute("name")
                                        if (name.isNotBlank()) register(pkg, "attr", name)
                                    }
                                    "declare-styleable" -> {
                                        // Nested <attr> children declare
                                        // custom view XML attributes
                                        // (e.g. app:showAsAction) - same
                                        // R.attr.xxx namespace as
                                        // top-level <attr>, just declared
                                        // inline here instead.
                                        val styleableName = node.getAttribute("name")
                                        val attrNames = mutableListOf<String>()
                                        val attrNodes = node.childNodes
                                        for (j in 0 until attrNodes.length) {
                                            val attrNode = attrNodes.item(j)
                                            if (attrNode !is Element || attrNode.tagName != "attr") continue
                                            val attrName = attrNode.getAttribute("name")
                                            if (attrName.isNotBlank()) {
                                                register(pkg, "attr", attrName)
                                                attrNames.add(attrName)
                                            }
                                        }
                                        if (styleableName.isNotBlank() && attrNames.isNotEmpty()) {
                                            styleables.getOrPut(pkg) { mutableMapOf() }[styleableName] = attrNames
                                        }
                                    }
                                    else -> {
                                        val resType = VALUE_RESOURCE_TAGS[node.tagName] ?: continue
                                        val name = node.getAttribute("name")
                                        if (name.isBlank()) continue
                                        val textValue = node.textContent ?: ""
                                        register(pkg, resType, name)?.setValueAsString(textValue)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            log.appendLine("Warning: failed to parse ${xmlFile.path}: ${e.message}")
                        }
                    }
                }

                // --- layout/menu/anim/xml/drawable/mipmap: file resources + @+id scan ---
                resDir.listFiles { f -> f.isDirectory }?.forEach { typeDir ->
                    if (typeDir.name.startsWith("values")) return@forEach // already handled above
                    val baseType = typeDir.name.substringBefore("-")
                    typeDir.listFiles { f -> f.isFile }?.forEach { resFile ->
                        // Nine-patch drawables (foo.9.png) have a
                        // compound extension - nameWithoutExtension only
                        // strips the trailing ".png", leaving ".9" in
                        // the identifier (e.g. "foo.9"), which isn't
                        // valid Kotlin/Java and breaks the whole
                        // generated R file's compile. Real aapt treats
                        // ".9.png" as one unit when deriving the
                        // resource name, same as here.
                        val entryName = if (resFile.name.endsWith(".9.png")) {
                            resFile.name.removeSuffix(".9.png")
                        } else {
                            resFile.nameWithoutExtension
                        }
                        // Namespaced by package so the same file-name
                        // pattern from different libraries doesn't
                        // collide in the final APK.
                        val apkPath = "res/${pkg.replace('.', '_')}/${typeDir.name}/${resFile.name}"

                        register(pkg, baseType, entryName)?.setValueAsString(apkPath)

                        // Android's resource loader only accepts XML-type
                        // file resources (layouts, vector drawables,
                        // menus, animators, xml/) in compiled binary
                        // form - a raw text copy throws
                        // FileNotFoundException("Corrupt XML binary
                        // file") at runtime the moment anything tries to
                        // inflate it. Compile via ARSCLib's own
                        // ResXmlDocument against the same packageBlock
                        // used for R generation, so @+id/@drawable/etc
                        // references in the XML resolve to the IDs we
                        // just assigned above.
                        if (resFile.extension == "xml") {
                            try {
                                val compiledFile = File(projectRoot, "build/compiled-res/$apkPath")
                                compiledFile.parentFile?.mkdirs()
                                val parser = KXmlParser()
                                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                                FileInputStream(resFile).use { input ->
                                    parser.setInput(input, null)
                                    val xmlDoc = ResXmlDocument()
                                    xmlDoc.setPackageBlock(packageBlock)
                                    xmlDoc.parse(parser)
                                    xmlDoc.writeBytes(compiledFile)
                                }
                                fileResources[apkPath] = compiledFile
                            } catch (e: Exception) {
                                // Fall back to the raw file rather than
                                // failing the whole build - it'll still
                                // throw at runtime if this specific
                                // resource is ever loaded, but everything
                                // else keeps working, and the log line
                                // below tells us exactly which file and
                                // why for the next round of fixes.
                                log.appendLine("Warning: failed to compile binary XML for ${resFile.path}: ${e.message} - copying raw (will likely fail at runtime if loaded)")
                                fileResources[apkPath] = resFile
                            }
                        } else {
                            fileResources[apkPath] = resFile
                        }

                        // Scan XML-based resources (layouts especially) for
                        // @+id/foo declarations, which implicitly declare new
                        // id-type resources not listed anywhere in values/.
                        if (resFile.extension == "xml") {
                            try {
                                val content = resFile.readText()
                                ID_ATTR_REGEX.findAll(content).forEach { match ->
                                    val idName = match.groupValues[1]
                                    register(pkg, "id", idName)?.setValueAsString(idName)
                                }
                            } catch (e: Exception) {
                                log.appendLine("Warning: failed to scan ids in ${resFile.path}: ${e.message}")
                            }
                        }
                    }
                }
                log.appendLine(
                    "Registered resources for $pkg: " +
                        (registry[pkg]?.entries?.joinToString { "${it.key}=${it.value.size}" } ?: "none")
                )
            }

            // --- Generate one R class per package, alongside the
            // project's own sources so the normal compile pass picks
            // them up automatically. Language must match whichever
            // compiler will actually run for this project (Kotlin-only
            // projects never look for .java files and vice versa, so
            // every generated R class needs the same language). ---
            val isKotlinProject = projectRoot.walkTopDown()
                .any { it.isFile && it.extension == "kt" && !it.path.contains("/build/") }

            for ((pkg, typeMap) in registry) {
                val pkgAttrIds = typeMap["attr"] ?: emptyMap()
                val pkgStyleables = styleables[pkg]

                fun sortedStyleableAttrs(attrNames: List<String>): List<Pair<String, Int>> =
                    attrNames.mapNotNull { name -> pkgAttrIds[name]?.let { id -> name to id } }
                        .sortedBy { it.second }

                val rFile: File
                val rSource: String
                if (isKotlinProject) {
                    rSource = buildString {
                        appendLine("package $pkg")
                        appendLine()
                        appendLine("object R {")
                        for ((type, entries) in typeMap) {
                            appendLine("    object $type {")
                            for ((name, id) in entries) {
                                appendLine("        const val ${sanitizeIdentifier(name)} = $id")
                            }
                            appendLine("    }")
                        }
                        if (pkgStyleables != null) {
                            appendLine("    object styleable {")
                            for ((styleableName, attrNames) in pkgStyleables) {
                                val sorted = sortedStyleableAttrs(attrNames)
                                val safeName = sanitizeIdentifier(styleableName)
                                appendLine("        val $safeName = intArrayOf(${sorted.joinToString(", ") { it.second.toString() }})")
                                sorted.forEachIndexed { index, (attrName, _) ->
                                    appendLine("        const val ${safeName}_${sanitizeIdentifier(attrName)} = $index")
                                }
                            }
                            appendLine("    }")
                        }
                        appendLine("}")
                    }
                    rFile = File(projectRoot, "build/generated/java/${pkg.replace('.', '/')}/R.kt")
                } else {
                    rSource = buildString {
                        appendLine("package $pkg;")
                        appendLine()
                        appendLine("public final class R {")
                        for ((type, entries) in typeMap) {
                            appendLine("    public static final class $type {")
                            for ((name, id) in entries) {
                                appendLine("        public static final int ${sanitizeIdentifier(name)} = $id;")
                            }
                            appendLine("    }")
                        }
                        if (pkgStyleables != null) {
                            appendLine("    public static final class styleable {")
                            for ((styleableName, attrNames) in pkgStyleables) {
                                val sorted = sortedStyleableAttrs(attrNames)
                                val safeName = sanitizeIdentifier(styleableName)
                                appendLine("        public static final int[] $safeName = { ${sorted.joinToString(", ") { it.second.toString() }} };")
                                sorted.forEachIndexed { index, (attrName, _) ->
                                    appendLine("        public static final int ${safeName}_${sanitizeIdentifier(attrName)} = $index;")
                                }
                            }
                            appendLine("    }")
                        }
                        appendLine("}")
                    }
                    rFile = File(projectRoot, "build/generated/java/${pkg.replace('.', '/')}/R.java")
                }
                rFile.parentFile?.mkdirs()
                rFile.writeText(rSource)
                log.appendLine("Generated ${rFile.name} for $pkg: ${rFile.path}")
            }

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
