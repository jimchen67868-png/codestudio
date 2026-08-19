package com.example.aideclone

import java.io.File

/**
 * Represents a single node in the project file tree: either a source
 * directory or a file. This is intentionally simple for M1 — no build
 * graph, no manifest parsing yet. That comes in M3 when we build the
 * dex/resource pipeline.
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
 * expand/collapse state per directory.
 */
class ProjectModel(val rootDir: File) {

    private val expandedDirs = mutableSetOf<String>()

    init {
        // Root starts expanded so the user sees top-level files immediately.
        expandedDirs.add(rootDir.absolutePath)
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
        /**
         * Creates a bare-bones project skeleton under [parent]/[name] with a
         * conventional src/main/java layout. Real Gradle-style templates
         * (manifest, res/, build config) land in M4; this just gives the
         * editor something real to open for now.
         */
        fun createNewProject(parent: File, name: String, packageName: String): ProjectModel {
            val root = File(parent, name)
            val srcDir = File(root, "src/main/java/" + packageName.replace('.', '/'))
            srcDir.mkdirs()
            val mainClass = File(srcDir, "MainActivity.java")
            if (!mainClass.exists()) {
                mainClass.writeText(
                    """
                    package $packageName;

                    public class MainActivity {
                        public static void main(String[] args) {
                            System.out.println("Hello from $name");
                        }
                    }
                    """.trimIndent()
                )
            }
            return ProjectModel(root)
        }
    }
}
