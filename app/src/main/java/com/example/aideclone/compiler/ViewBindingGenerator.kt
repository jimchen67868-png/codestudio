package com.example.aideclone.compiler

import java.io.File

/** Generates ViewBinding-style Kotlin classes from res/layout XML. */
object ViewBindingGenerator {
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<([A-Za-z][\\w.]*)(\\s[^<>]*)?>")
    private val ID = Regex("android:id\\s*=\\s*\"@\\+id/(\\w+)\"")

    private fun fq(tag: String): String = when {
        tag.contains('.') -> tag
        tag == "WebView" -> "android.webkit.WebView"
        tag in setOf("View", "ViewGroup", "ViewStub", "SurfaceView", "TextureView") -> "android.view.$tag"
        tag in setOf("merge", "include", "fragment") -> "android.view.View"
        else -> "android.widget.$tag"
    }

    private fun camel(s: String): String {
        val p = s.split('_').filter { it.isNotEmpty() }
        return p.first() + p.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun pascal(s: String) = s.split('_').filter { it.isNotEmpty() }
        .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

    fun generate(layoutDirs: List<File>, packageName: String, outRoot: File): Int {
        val outDir = File(outRoot, packageName.replace('.', '/') + "/databinding")
        outDir.deleteRecursively(); outDir.mkdirs()
        val files = layoutDirs.filter { it.isDirectory }
            .flatMap { d -> d.listFiles { f -> f.name.endsWith(".xml") }?.toList() ?: emptyList() }
            .distinctBy { it.name }
        for (f in files) {
            val layout = f.nameWithoutExtension
            val cls = pascal(layout) + "Binding"
            val xml = f.readText().replace(COMMENT, "")
            val tags = TAG.findAll(xml).filter { !it.groupValues[1].startsWith("?") }.toList()
            if (tags.isEmpty()) continue
            val rootType = fq(tags.first().groupValues[1])
            val fields = LinkedHashMap<String, String>()
            for (t in tags) {
                val id = ID.find(t.groupValues[2])?.groupValues?.get(1) ?: continue
                if (!fields.containsKey(id)) fields[id] = fq(t.groupValues[1])
            }
            val sb = StringBuilder()
            sb.append("package $packageName.databinding\n\n")
            sb.append("import android.view.LayoutInflater\nimport android.view.View\nimport android.view.ViewGroup\n")
            sb.append("import $packageName.R\n\n")
            sb.append("class $cls private constructor(\n    val root: $rootType")
            for ((id, type) in fields) sb.append(",\n    val ${camel(id)}: $type")
            sb.append("\n) {\n    companion object {\n")
            sb.append("        @JvmStatic fun inflate(inflater: LayoutInflater): $cls = inflate(inflater, null, false)\n\n")
            sb.append("        @JvmStatic fun inflate(inflater: LayoutInflater, parent: ViewGroup?, attachToParent: Boolean): $cls {\n")
            sb.append("            val v = inflater.inflate(R.layout.$layout, parent, false)\n")
            sb.append("            if (attachToParent && parent != null) parent.addView(v)\n")
            sb.append("            return bind(v)\n        }\n\n")
            sb.append("        @JvmStatic fun bind(view: View): $cls = $cls(view as $rootType")
            for ((id, type) in fields) sb.append(",\n            view.findViewById<$type>(R.id.$id)")
            sb.append(")\n    }\n}\n")
            File(outDir, "$cls.kt").writeText(sb.toString())
        }
        return files.size
    }
}
