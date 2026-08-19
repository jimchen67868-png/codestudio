package com.example.aideclone.compiler

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.io.File

/**
 * Wraps D8 (the AOSP dexer) to turn a directory of compiled .class files
 * into a classes.dex, the format the Android runtime actually executes.
 * D8's public API is pure Kotlin/Java, so — unlike aapt2 — it runs
 * on-device with no native binary needed.
 */
object DexEngine {

    fun dex(classesDir: File, outputDir: File, minApiLevel: Int = 24): File {
        outputDir.mkdirs()
        val command = D8Command.builder()
            .addProgramFiles(classesDir.toPath())
            .setOutput(outputDir.toPath(), OutputMode.DexIndexed)
            .setMinApiLevel(minApiLevel)
            .setMode(CompilationMode.DEBUG)
            .build()
        D8.run(command)
        return File(outputDir, "classes.dex")
    }
}
