package com.example.aideclone.compiler

/**
 * Holds the most recent compile run so EditorActivity can pull up
 * inline squiggles for whatever file it opens, without re-running the
 * compiler or wiring up a ContentProvider for what is in-process data.
 * Cleared implicitly on process death, which is fine — a fresh Compile
 * run is one tap away.
 */
object CompileResultStore {
    @Volatile
    var lastResult: CompileResult? = null
        private set

    fun update(result: CompileResult) {
        lastResult = result
    }

    fun diagnosticsFor(filePath: String): List<CompileDiagnostic> =
        lastResult?.diagnostics?.filter { it.filePath == filePath } ?: emptyList()
}
