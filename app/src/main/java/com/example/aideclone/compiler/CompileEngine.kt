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
 * Critical Android-specific detail: ECJ normally auto-detects its boot
 * classpath by inspecting the *running* JVM (looking for standard JDK
 * classes like javax.lang.model.SourceVersion). On Android's ART runtime
 * those classes simply don't exist — ART isn't a desktop JVM — so that
 * auto-detection throws NoClassDefFoundError before ECJ ever looks at a
 * source file. The fix is to always pass an explicit -bootclasspath, which
 * skips the auto-detection path entirely. android.jar is the right value
 * for it: it's Android's equivalent of rt.jar, defining java.lang.Object,
 * String, etc. as they exist on-device, not just the app-level APIs.
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

    fun compileProject(projectRoot: File, androidJar: File?, extraLibraries: List<File> = emptyList()): CompileResult {
        if (androidJar == null || !androidJar.exists()) {
            return CompileResult(
                success = false,
                diagnostics = emptyList(),
                rawOutput = "android.jar not imported. It's required as ECJ's " +
                    "-bootclasspath on Android — without an explicit bootclasspath, " +
                    "ECJ tries to auto-detect one from the host JVM, which crashes " +
                    "with NoClassDefFoundError on ART. Use \"Import android.jar\"."
            )
        }

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

        // Diagnostic preamble: confirms exactly what this run believes
        // about the bootclasspath file, so a bad import (wrong path,
        // truncated copy, unreadable file) is visible directly in the
        // Build Output instead of needing to guess from ECJ's downstream
        // "cannot be resolved" errors.
        outWriter.write(
            "androidJar path: ${androidJar.absolutePath}\n" +
                "  exists=${androidJar.exists()} canRead=${androidJar.canRead()} " +
                "length=${androidJar.length()} bytes\n" +
                (if (extraLibraries.isNotEmpty()) {
                    "extra libraries: ${extraLibraries.joinToString(", ") { it.name }}\n"
                } else "") +
                "\n"
        )

        // -1.8 target/source keeps this compatible with typical Android
        // Java sources; -proceedOnError so one broken file doesn't abort
        // the whole batch (we want a full diagnostics list, not just the
        // first error). -bootclasspath (not -classpath) is what avoids
        // ECJ's crash-prone auto-detection — see class doc above.
        // AndroidX/other library jars go on -classpath, not
        // -bootclasspath — android.jar defines the platform's own core
        // types, additional libraries are ordinary application deps.
        val classpathArgs: Array<String> = if (extraLibraries.isNotEmpty()) {
            arrayOf("-classpath", extraLibraries.joinToString(File.pathSeparator) { it.absolutePath })
        } else {
            emptyArray()
        }

        val args = arrayOf(
            "-1.8",
            "-source", "1.8",
            "-target", "1.8",
            "-d", outputDir.absolutePath,
            "-proceedOnError",
            "-verbose",
            // We never use annotation processors, but ECJ unconditionally
            // tries to initialize its APT subsystem unless told not to —
            // and that touches javax.annotation.processing.*, another
            // desktop-JDK-only package absent on Android, causing another
            // NoClassDefFoundError before compilation starts. -proc:none
            // is the standard javac/ecj flag to skip that entirely.
            "-proc:none",
            "-bootclasspath", androidJar.absolutePath,
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

        // Belt-and-suspenders on top of the parsed diagnostics: if ECJ (or
        // our own catch block above) wrote an internal-crash marker that
        // didn't match the structured per-file diagnostic format, don't
        // report success just because 0 diagnostics were parsed from it.
        val hasInternalError = rawOutput.contains("Internal compiler error")
        val outputHasClasses = outputDir.walkTopDown().any { it.isFile && it.extension == "class" }

        return CompileResult(
            success = success && !hasInternalError && outputHasClasses,
            diagnostics = diagnostics,
            rawOutput = rawOutput
        )
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
