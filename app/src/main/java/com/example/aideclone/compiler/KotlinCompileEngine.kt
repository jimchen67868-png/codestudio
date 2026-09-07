package com.example.aideclone.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Compiles every .kt file under a project root using the embeddable
 * Kotlin compiler (kotlin-compiler-embeddable), mirroring CompileEngine's
 * (ECJ/Java) interface and reusing its CompileResult/CompileDiagnostic
 * types so the rest of the app — diagnostics panel, inline squiggles,
 * build log — doesn't need to know which language compiled a project.
 *
 * Uses K2JVMCompiler's simple exec(PrintStream, vararg String) entry
 * point (the same one `kotlinc` itself calls) rather than wiring up a
 * custom MessageCollector, since that requires touching more
 * version-sensitive internal API surface than this simple CLI-style
 * entry point does. Diagnostics are regex-parsed from the printed
 * output, same approach as CompileEngine uses for ECJ.
 *
 * -no-stdlib is important: without it, the compiler tries to locate its
 * own bundled stdlib via a "kotlin home" directory layout that doesn't
 * exist in this environment. We supply kotlin-stdlib.jar explicitly on
 * -cp instead (see MainActivity's ensureKotlinStdlib()).
 *
 * Expect this to need iteration once actually run on-device — the
 * embedded Kotlin compiler is far larger and more complex than ECJ was,
 * and ECJ alone needed several rounds of Android-specific fixes
 * (bootclasspath detection, annotation processor init, missing
 * javax.lang.model classes). This hasn't been runtime-tested at all yet.
 */
object KotlinCompileEngine {

    private val DIAG_LINE = Regex(
        """^(.+\.kt):\s*(\d+):\s*(\d+):\s*(error|warning):\s*(.*)$"""
    )

    fun compileProject(projectRoot: File, androidJar: File?, kotlinStdlib: File): CompileResult {
        if (androidJar == null || !androidJar.exists()) {
            return CompileResult(
                success = false,
                diagnostics = emptyList(),
                rawOutput = "android.jar not imported — needed on the Kotlin " +
                    "compiler's classpath too, same as for Java. Use \"Import android.jar\"."
            )
        }
        if (!kotlinStdlib.exists()) {
            return CompileResult(
                success = false,
                diagnostics = emptyList(),
                rawOutput = "Bundled kotlin-stdlib.jar asset missing from the app " +
                    "package — this is a build-time bug (see bundleKotlinStdlib " +
                    "Gradle task), not something the project itself can fix."
            )
        }

        val sourceFiles = projectRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("/build/") }
            .toList()

        if (sourceFiles.isEmpty()) {
            return CompileResult(
                success = false,
                diagnostics = emptyList(),
                rawOutput = "No .kt files found under ${projectRoot.path}"
            )
        }

        val outputDir = File(projectRoot, "build/classes").apply {
            deleteRecursively()
            mkdirs()
        }

        // A dedicated, empty directory for -kotlin-home below — not the
        // android.jar cache folder, which isn't semantically a Kotlin
        // installation home even though it happened to work as "any
        // existing directory". Nothing actually needs to live in here:
        // with -no-stdlib/-no-reflect/-no-jdk all set, the compiler
        // shouldn't try to load anything FROM this path, it just needs a
        // real File to construct KotlinPathsFromHomeDir with instead of
        // running its broken getResource()-based auto-detection.
        val kotlinHomeDir = File(projectRoot, "build/kotlin-home").apply { mkdirs() }

        val classpath = listOf(androidJar, kotlinStdlib)
            .joinToString(File.pathSeparator) { it.absolutePath }

        val args = arrayOf(
            "-cp", classpath,
            "-d", outputDir.absolutePath,
            "-no-stdlib",
            // Skips JDK auto-detection ("no class roots found in the JDK
            // path: /apex/com.android.art") — the compiler tries to
            // locate a real JDK via java.home, which on Android points
            // to ART's own runtime module, not anything JDK-shaped. We
            // don't need this since android.jar (passed via -cp above)
            // already covers java.lang.* etc. for Android's runtime.
            "-no-jdk",
            // Bypasses PathUtil.getKotlinPathsForCompiler()'s
            // auto-detection, which tries to locate its own .class file
            // as a browsable resource via getResource() to figure out
            // "where am I installed" — a trick that works for desktop
            // JAR-based classloading but has no equivalent on Android's
            // DEX-based classloading (individual classes aren't
            // browsable resources there at all), causing
            // "IllegalStateException: Resource not found". Confirmed
            // necessary: removing this flag reproduces that crash.
            "-kotlin-home", kotlinHomeDir.absolutePath,
            "-no-reflect",
            "-jvm-target", "1.8",
            *sourceFiles.map { it.absolutePath }.toTypedArray()
        )

        val outBytes = ByteArrayOutputStream()
        val printStream = PrintStream(outBytes, true, "UTF-8")

        val exitCode = try {
            K2JVMCompiler().exec(printStream, *args)
        } catch (t: Throwable) {
            // Same reasoning as CompileEngine/ApkBuilder: catch Throwable,
            // not just Exception, since Android-incompatible internals can
            // throw Error subtypes like NoClassDefFoundError.
            printStream.println("Internal Kotlin compiler error: $t")
            printStream.println(t.stackTraceToString())
            ExitCode.INTERNAL_ERROR
        }

        val rawOutput = outBytes.toString("UTF-8")
        val diagnostics = parseDiagnostics(rawOutput)
        val outputHasClasses = outputDir.walkTopDown().any { it.isFile && it.extension == "class" }

        return CompileResult(
            success = exitCode == ExitCode.OK && outputHasClasses,
            diagnostics = diagnostics,
            rawOutput = rawOutput
        )
    }

    private fun parseDiagnostics(output: String): List<CompileDiagnostic> {
        val result = mutableListOf<CompileDiagnostic>()
        for (line in output.lines()) {
            val match = DIAG_LINE.find(line.trim()) ?: continue
            val (path, lineStr, _, severityStr, message) = match.destructured
            val severity = if (severityStr == "error") {
                CompileDiagnostic.Severity.ERROR
            } else {
                CompileDiagnostic.Severity.WARNING
            }
            result.add(
                CompileDiagnostic(
                    filePath = path,
                    line = lineStr.toIntOrNull() ?: 1,
                    severity = severity,
                    message = message
                )
            )
        }
        return result
    }
}
