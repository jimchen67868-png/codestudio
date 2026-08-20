package javax.lang.model

/**
 * Android's runtime has never shipped javax.lang.model.* — it's part of
 * the JDK's annotation-processing API (JSR 269), irrelevant to running
 * apps, and excluded from Android's stripped-down core library.
 *
 * ECJ's internal FileSystem class references SourceVersion in a static
 * initializer purely for host-JVM version detection (unrelated to actual
 * compilation), which throws NoClassDefFoundError on ART before any
 * source file is even looked at — a known, long-standing issue reported
 * against ECJ on Android/Termux across multiple ECJ versions since at
 * least 2019 (see termux/termux-packages#4704).
 *
 * This stub exists purely to give the classloader something to find. It
 * mirrors the real JDK enum's shape (values, latest()/latestSupported(),
 * isIdentifier/isName/isKeyword) closely enough that any straightforward
 * version-comparison logic in ECJ's static initializer resolves sensibly,
 * without needing the rest of the real javax.lang.model / annotation
 * processing API, none of which our batch-mode ECJ usage exercises.
 */
enum class SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6,
    RELEASE_7, RELEASE_8, RELEASE_9, RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13,
    RELEASE_14, RELEASE_15, RELEASE_16, RELEASE_17, RELEASE_18, RELEASE_19, RELEASE_20,
    RELEASE_21, RELEASE_22, RELEASE_23;

    companion object {
        // Reported as Java 17 — recent enough to satisfy any "is this a
        // modern JVM" check ECJ's module-support code might do, without
        // claiming a version so new it triggers code paths for JDK
        // features (like newer module-path handling) we can't back up.
        @JvmStatic
        fun latest(): SourceVersion = RELEASE_17

        @JvmStatic
        fun latestSupported(): SourceVersion = RELEASE_17

        @JvmStatic
        fun isIdentifier(name: CharSequence): Boolean {
            if (name.isEmpty()) return false
            if (!Character.isJavaIdentifierStart(name[0])) return false
            for (i in 1 until name.length) {
                if (!Character.isJavaIdentifierPart(name[i])) return false
            }
            return true
        }

        @JvmStatic
        fun isName(name: CharSequence): Boolean =
            name.toString().split(".").all { isIdentifier(it) }

        @JvmStatic
        fun isKeyword(s: CharSequence): Boolean = KEYWORDS.contains(s.toString())

        private val KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null",
            "var", "yield", "record", "sealed", "permits"
        )
    }
}
