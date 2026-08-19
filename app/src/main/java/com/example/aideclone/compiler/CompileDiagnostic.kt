package com.example.aideclone.compiler

/**
 * A single compiler-reported problem, normalized from ECJ's console output
 * into structured data the UI can consume (log panel rows + inline
 * squiggles keyed by file path + line).
 */
data class CompileDiagnostic(
    val filePath: String,
    val line: Int,          // 1-based, matches ECJ's "at line N" output
    val severity: Severity,
    val message: String
) {
    enum class Severity { ERROR, WARNING }
}

/** Full result of a compile run across the whole project. */
data class CompileResult(
    val success: Boolean,
    val diagnostics: List<CompileDiagnostic>,
    val rawOutput: String
)
