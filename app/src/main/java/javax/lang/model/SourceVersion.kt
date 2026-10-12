package javax.lang.model

enum class SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6, RELEASE_7,
    RELEASE_8, RELEASE_9, RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13, RELEASE_14,
    RELEASE_15, RELEASE_16, RELEASE_17, RELEASE_18, RELEASE_19, RELEASE_20, RELEASE_21;

    companion object {
        private val KEYWORDS = setOf(
            "abstract","continue","for","new","switch","assert","default","if","package",
            "synchronized","boolean","do","goto","private","this","break","double",
            "implements","protected","throw","byte","else","import","public","throws",
            "case","enum","instanceof","return","transient","catch","extends","int",
            "short","try","char","final","interface","static","void","class","finally",
            "long","strictfp","volatile","const","float","native","super","while",
            "_","true","false","null")

        @JvmStatic fun latest(): SourceVersion = RELEASE_21
        @JvmStatic fun latestSupported(): SourceVersion = RELEASE_21
        @JvmStatic fun isKeyword(s: CharSequence): Boolean = KEYWORDS.contains(s.toString())
        @JvmStatic fun isIdentifier(s: CharSequence): Boolean {
            val t = s.toString()
            if (t.isEmpty() || !Character.isJavaIdentifierStart(t[0])) return false
            return t.all { Character.isJavaIdentifierPart(it) }
        }
        @JvmStatic fun isName(s: CharSequence): Boolean {
            val t = s.toString()
            return t.split(".").all { isIdentifier(it) && !isKeyword(it) }
        }
    }
}
