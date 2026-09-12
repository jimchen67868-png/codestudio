package com.example.aideclone.packaging

import com.android.apksig.ApkSigner
import com.example.aideclone.compiler.DexEngine
import com.example.aideclone.compiler.ResourceCompiler
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File

data class BuildResult(val success: Boolean, val apkFile: File?, val log: String)

/**
 * M3's dex + resource + signing pipeline:
 *   1. Dex the .class files M2's CompileEngine produced (D8).
 *   2. Build a binary AndroidManifest.xml + resources.arsc with
 *      ARSCLib — a pure-Java aapt2 replacement, so no native ARM binary
 *      is needed on-device. Real res/ folder content (layouts, strings,
 *      drawables) is handled by ResourceCompiler; this just wires its
 *      output into the final APK, or falls back to a minimal
 *      app_name-only table for projects with no res/ folder at all.
 *   3. Assemble dex + manifest + resources into an unsigned APK.
 *   4. Sign it with apksig, using an on-device generated debug key
 *      (KeystoreManager).
 */
object ApkBuilder {

    fun build(
        projectRoot: File,
        packageName: String,
        mainActivityClass: String,
        appName: String,
        signingStorageDir: File,
        extraLibraries: List<File> = emptyList(),
        frameworkApkFile: File? = null
    ): BuildResult {
        val log = StringBuilder()
        val buildDir = File(projectRoot, "build").apply { mkdirs() }
        val logFile = File(buildDir, "build-log.txt")

        fun finish(result: BuildResult): BuildResult {
            // Always write the full log to a real file — much easier to
            // scroll/select/copy by opening it in the editor than reading
            // an AlertDialog on a phone screen.
            try {
                logFile.writeText(result.log)
            } catch (_: Exception) {
                // Non-fatal: the dialog still has the text even if this fails.
            }
            return result
        }

        try {
            val classesDir = File(projectRoot, "build/classes")
            if (!classesDir.exists() || classesDir.listFiles().isNullOrEmpty()) {
                return finish(BuildResult(false, null, "No compiled classes found — run Compile first."))
            }

            log.appendLine("Dexing ${classesDir.path} ...")
            val dexOutputDir = File(projectRoot, "build/dex")
            val dexResult = DexEngine.dex(classesDir, dexOutputDir, extraLibraries = extraLibraries)
            log.append(dexResult.log)
            if (!dexResult.success || dexResult.dexFile == null) {
                log.appendLine("Dexing failed — see D8 diagnostics above.")
                return finish(BuildResult(false, null, log.toString()))
            }
            log.appendLine("Dex OK: ${dexResult.dexFile.length()} bytes")

            val apkModule = ApkModule()
            val manifest = AndroidManifestBlock()

            // Real res/ folder compilation (layouts, strings, drawables,
            // etc.) — falls back to a minimal table with just app_name
            // for plain projects with no res/ folder at all.
            val resResult = ResourceCompiler.compileResources(projectRoot, frameworkApkFile, packageName)
            log.appendLine(resResult.rawOutput)
            if (!resResult.success) {
                return finish(BuildResult(false, null, log.toString()))
            }

            val tableBlock = resResult.tableBlock ?: TableBlock()
            val packageBlock = resResult.packageBlock ?: tableBlock.newPackage(0x7f, packageName)
            val appNameEntry = packageBlock.getOrCreate("", "string", "app_name")
            appNameEntry.setValueAsString(appName)

            apkModule.setTableBlock(tableBlock)
            apkModule.setManifest(manifest)

            // Fold in the actual resource files (layouts, drawables,
            // etc.) ResourceCompiler registered, alongside the dex below.
            resResult.fileResources.forEach { (apkPath, sourceFile) ->
                apkModule.add(ByteInputSource(sourceFile.readBytes(), apkPath))
            }

            manifest.setPackageName(packageName)
            manifest.setVersionCode(1)
            manifest.setVersionName("0.1")
            manifest.setCompileSdkVersion(34)
            manifest.setCompileSdkVersionCodename("14")
            manifest.setPlatformBuildVersionCode(34)
            manifest.setPlatformBuildVersionName("14")
            manifest.setApplicationLabel(appNameEntry.getResourceId())
            manifest.setMinSdkVersion(24)
            manifest.setTargetSdkVersion(34)

            // Declares mainActivityClass as the launcher activity
            // (MAIN/LAUNCHER intent filter) — this must be a real
            // android.app.Activity subclass compiled with android.jar on
            // the classpath, or the resulting APK will crash on launch.
            manifest.getOrCreateMainActivity(mainActivityClass)
            log.appendLine("Manifest built for $packageName / $mainActivityClass")

            apkModule.add(ByteInputSource(dexResult.dexFile.readBytes(), "classes.dex"))

            val unsignedApk = File(buildDir, "$packageName-unsigned.apk")
            if (unsignedApk.exists()) unsignedApk.delete()
            apkModule.writeApk(unsignedApk)
            log.appendLine("Unsigned APK written: ${unsignedApk.path}")

            val identity = KeystoreManager.getOrCreate(signingStorageDir)
            val signedApk = File(buildDir, "$packageName.apk")

            val signerConfig = ApkSigner.SignerConfig.Builder(
                "debugkey",
                identity.privateKey,
                listOf(identity.certificate)
            ).build()

            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setMinSdkVersion(24)
                .build()
                .sign()

            log.appendLine("Signed APK written: ${signedApk.path}")
            return finish(BuildResult(true, signedApk, log.toString()))
        } catch (t: Throwable) {
            // See CompileEngine's matching comment: D8/ARSCLib running
            // inside the app process (rather than as a desktop build tool,
            // which is what they're designed for) can throw Error subtypes
            // like NoClassDefFoundError, not just Exception. Catching
            // Throwable here is what turns "the whole app crashes" into
            // "Build APK shows an error dialog with the real stack trace".
            log.appendLine("Build failed: ${t}")
            log.appendLine(t.stackTraceToString())
            return finish(BuildResult(false, null, log.toString()))
        }
    }
}
