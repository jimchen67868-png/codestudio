package com.example.aideclone.compiler

import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Compiles every .java file under a project root using ECJ in batch mode.
 *
 * Uses the public org.eclipse.jdt.core.compiler.batch.BatchCompiler API
 * rather than the internal org.eclipse.jdt.internal.compiler.batch.Main
 * class — BatchCompiler is the supported embeddable entry point and its
 * compile() signature is stable: (String[] args, PrintWriter out,
 * PrintWriter err, CompilationProgress progress), with progress safely
 * null when we don't need progress callbacks.
 *
 * Note on diagnostics: BatchCompiler only exposes console text, not
 * structured problem objects — getting real IProblem callbacks means
 * going lower-level into ECJ's Compiler API and supplying a custom
 * ICompilerRequestor, which is a reasonable follow-up but overkill for
 * M2. Instead we regex-parse ECJ's structured console output (it's
 * stable across versions: "N. ERROR in <path> (at line L)").
 */
object CompileEngine {

    private val PROBLEM_HEADER = Regex(
        """^\d+\.\s+(ERROR|WARNING)\s+in\s+(.+?)\s+\(at line (\d+)\)$"""
    )

    fun compileProject(projectRoot: File, classpath: List<File> = emptyList()): CompileResult {
        val sourceFiles = collectJavaFiles(projectRoot)
        if (sourceFiles.isEmpty()) {
            return CompileResult(
                success = false,
                diagnostics = emptyList(),
                rawOutput = "No .java files found under ${projectRoot.path}"
            )
        }

        val outputDir = File(projectRoot, "build/classes").apply {
            deleteRecursively()
            mkdirs()
        }

        val outWriter = StringWriter()
        val errWriter = StringWriter()

        // -1.8 target/source keeps this compatible with typical Android
        // Java sources; -proceedOnError so one broken file doesn't abort
        // the whole batch (we want a full diagnostics list, not just the
        // first error). Classpath entries (e.g. an imported android.jar)
        // let sources reference Android SDK classes like android.app.Activity.
        val classpathArgs: Array<String> = if (classpath.isNotEmpty()) {
            arrayOf("-classpath", classpath.joinToString(File.pathSeparator) { it.absolutePath })
        } else {
            emptyArray()
        }

        val args = arrayOf(
            "-1.8",
            "-source", "1.8",
            "-target", "1.8",
            "-d", outputDir.absolutePath,
            "-proceedOnError",
            *classpathArgs,
            *sourceFiles.map { it.absolutePath }.toTypedArray()
        )

        val success = try {
            BatchCompiler.compile(args, PrintWriter(outWriter), PrintWriter(errWriter), null)
        } catch (t: Throwable) {
            // Deliberately catching Throwable, not just Exception: ECJ
            // internals (and D8/ARSCLib in ApkBuilder) can throw Error
            // subtypes like NoClassDefFoundError on-device, which a plain
            // Exception catch would miss — letting those propagate crashes
            // the whole host app instead of just failing this compile.
            outWriter.write("\nInternal compiler error: $t\n${t.stackTraceToString()}\n")
            false
        }

        val rawOutput = outWriter.toString() + errWriter.toString()
        val diagnostics = parseDiagnostics(rawOutput)

        return CompileResult(success = success, diagnostics = diagnostics, rawOutput = rawOutput)
    }

    private fun collectJavaFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "java" && !it.path.contains("/build/") }
            .forEach { result.add(it) }
        return result
    }

    private fun parseDiagnostics(output: String): List<CompileDiagnostic> {
        val diagnostics = mutableListOf<CompileDiagnostic>()
        val lines = output.lines()

        var i = 0
        while (i < lines.size) {
            val match = PROBLEM_HEADER.find(lines[i].trim())
            if (match != null) {
                val (severityStr, path, lineStr) = match.destructured
                val severity = if (severityStr == "ERROR")
                    CompileDiagnostic.Severity.ERROR else CompileDiagnostic.Severity.WARNING

                // Collect message text until the next "----------" separator
                // or next problem header, skipping the echoed source line
                // and the "^" caret-pointer line.
                val messageLines = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size &&
                    !lines[j].startsWith("----------") &&
                    PROBLEM_HEADER.find(lines[j].trim()) == null
                ) {
                    val trimmed = lines[j].trim()
                    val isCaretLine = trimmed.isNotEmpty() && trimmed.all { it == '^' }
                    if (trimmed.isNotEmpty() && !isCaretLine) messageLines.add(trimmed)
                    j++
                }
                // The last non-empty line in the block is ECJ's actual
                // message; earlier lines are the echoed source snippet.
                val message = messageLines.lastOrNull() ?: "Unknown compiler error"

                diagnostics.add(
                    CompileDiagnostic(
                        filePath = path.trim(),
                        line = lineStr.toIntOrNull() ?: 1,
                        severity = severity,
                        message = message
                    )
                )
                i = j
            } else {
                i++
            }
        }
        return diagnostics
    }
}
