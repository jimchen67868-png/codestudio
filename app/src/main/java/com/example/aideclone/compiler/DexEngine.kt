package com.example.aideclone.compiler

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import java.io.File

data class DexResult(val dexFile: File?, val log: String, val success: Boolean)

/**
 * Wraps D8 (the AOSP dexer) to turn a directory of compiled .class files
 * into a classes.dex, the format the Android runtime actually executes.
 * D8's public API is pure Kotlin/Java, so — unlike aapt2 — it runs
 * on-device with no native binary needed.
 *
 * D8 doesn't print diagnostics to a PrintWriter the way ECJ does — it
 * reports them through a DiagnosticsHandler callback. Without supplying
 * one, a failure only surfaces as a generic
 * "CompilationFailedException: Compilation failed to complete" with no
 * indication of what actually went wrong. This captures the real
 * per-diagnostic messages so failures are actually diagnosable.
 */
object DexEngine {

    fun dex(classesDir: File, outputDir: File, minApiLevel: Int = 24): DexResult {
        outputDir.mkdirs()
        val log = StringBuilder()

        val handler = object : DiagnosticsHandler {
            override fun info(diagnostic: Diagnostic) {
                log.appendLine("[D8 info] ${diagnostic.diagnosticMessage}")
            }
            override fun warning(diagnostic: Diagnostic) {
                log.appendLine("[D8 warning] ${diagnostic.diagnosticMessage}")
            }
            override fun error(diagnostic: Diagnostic) {
                log.appendLine("[D8 error] ${diagnostic.diagnosticMessage}")
            }
        }

        return try {
            val command = D8Command.builder(handler)
                .addProgramFiles(classesDir.toPath())
                .setOutput(outputDir.toPath(), OutputMode.DexIndexed)
                .setMinApiLevel(minApiLevel)
                .setMode(CompilationMode.DEBUG)
                .build()
            D8.run(command)
            val dexFile = File(outputDir, "classes.dex")
            DexResult(
                dexFile = if (dexFile.exists()) dexFile else null,
                log = log.toString(),
                success = dexFile.exists()
            )
        } catch (t: Throwable) {
            log.appendLine("Dex failed: $t")
            log.appendLine(t.stackTraceToString())
            DexResult(dexFile = null, log = log.toString(), success = false)
        }
    }
}
