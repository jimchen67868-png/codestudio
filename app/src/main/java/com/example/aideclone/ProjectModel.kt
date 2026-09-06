package com.example.aideclone

import java.io.File
import java.util.Properties

/**
 * Represents a single node in the project file tree: either a source
 * directory or a file.
 */
data class FileNode(
    val file: File,
    val depth: Int
) {
    val isDirectory: Boolean get() = file.isDirectory
    val name: String get() = file.name
}

/**
 * Wraps a project root directory on disk and knows how to flatten it
 * into a displayable, indent-aware list for the RecyclerView, respecting
 * expand/collapse state per directory. Also carries the small bit of
 * project metadata (package name, app name) that M3's build pipeline
 * needs to assemble a manifest — stored in a plain .properties file
 * rather than anything resembling a real Gradle project model, which is
 * still out of scope.
 */
class ProjectModel(val rootDir: File) {

    private val expandedDirs = mutableSetOf<String>()
    private val propsFile = File(rootDir, "project.properties")

    val packageName: String
    val appName: String
    val mainActivityClass: String

    init {
        expandedDirs.add(rootDir.absolutePath)
        val props = Properties()
        // exists() can report true (based on directory-listing metadata)
        // even when actually OPENING the file fails — e.g. reading into
        // shared storage without "All files access" granted throws
        // FileNotFoundException/EACCES despite exists() saying yes. Any
        // read failure here should fall back to defaults, not crash the
        // whole app — this used to be unguarded and did exactly that.
        try {
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
        } catch (_: Exception) {
            // Fall through with an empty Properties — defaults below apply.
        }
        packageName = props.getProperty("packageName", "com.example.app")
        appName = props.getProperty("appName", rootDir.name)
        mainActivityClass = props.getProperty("mainActivityClass", "$packageName.MainActivity")
    }

    fun toggleExpand(node: FileNode) {
        if (!node.isDirectory) return
        val path = node.file.absolutePath
        if (expandedDirs.contains(path)) expandedDirs.remove(path) else expandedDirs.add(path)
    }

    fun isExpanded(node: FileNode): Boolean =
        expandedDirs.contains(node.file.absolutePath)

    /** Flattens the visible (expanded) tree into an ordered list for display. */
    fun buildVisibleList(): List<FileNode> {
        val result = mutableListOf<FileNode>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles()
                ?.filterNot { it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
            for (child in children) {
                val node = FileNode(child, depth)
                result.add(node)
                if (child.isDirectory && isExpanded(node)) {
                    walk(child, depth + 1)
                }
            }
        }
        walk(rootDir, 0)
        return result
    }

    companion object {
        enum class Language { JAVA, KOTLIN }

        /**
         * Creates a project skeleton under [parent]/[name] with a
         * conventional src/main/java layout and a minimal real
         * android.app.Activity (needed since M3, so the compiled +
         * packaged APK is actually launchable), in either Java or
         * Kotlin depending on [language].
         *
         * Note: compiling this skeleton requires android.jar on the
         * classpath (see MainActivity's "Import android.jar" action)
         * regardless of language — android.app.Activity isn't
         * resolvable against a plain JDK, and that's just as true for
         * Kotlin compilation (KotlinCompileEngine) as it is for ECJ.
         */
        fun createNewProject(
            parent: File,
            name: String,
            packageName: String,
            language: Language = Language.JAVA
        ): ProjectModel {
            val root = File(parent, name)
            val srcDir = File(root, "src/main/java/" + packageName.replace('.', '/'))
            srcDir.mkdirs()

            val mainActivityClass = "$packageName.MainActivity"

            when (language) {
                Language.JAVA -> {
                    val mainClass = File(srcDir, "MainActivity.java")
                    if (!mainClass.exists()) {
                        mainClass.writeText(
                            """
                            package $packageName;

                            import android.app.Activity;
                            import android.os.Bundle;
                            import android.widget.TextView;

                            public class MainActivity extends Activity {
                                @Override
                                protected void onCreate(Bundle savedInstanceState) {
                                    super.onCreate(savedInstanceState);
                                    TextView view = new TextView(this);
                                    view.setText("Hello from $name");
                                    setContentView(view);
                                }
                            }
                            """.trimIndent()
                        )
                    }
                }
                Language.KOTLIN -> {
                    val mainClass = File(srcDir, "MainActivity.kt")
                    if (!mainClass.exists()) {
                        mainClass.writeText(
                            """
                            package $packageName

                            import android.app.Activity
                            import android.os.Bundle
                            import android.widget.TextView

                            class MainActivity : Activity() {
                                override fun onCreate(savedInstanceState: Bundle?) {
                                    super.onCreate(savedInstanceState)
                                    val view = TextView(this)
                                    view.text = "Hello from $name"
                                    setContentView(view)
                                }
                            }
                            """.trimIndent()
                        )
                    }
                }
            }

            val propsFile = File(root, "project.properties")
            if (!propsFile.exists()) {
                val props = Properties()
                props.setProperty("packageName", packageName)
                props.setProperty("appName", name)
                props.setProperty("mainActivityClass", mainActivityClass)
                propsFile.outputStream().use { props.store(it, "AIDEClone project metadata") }
            }

            return ProjectModel(root)
        }
    }
}
