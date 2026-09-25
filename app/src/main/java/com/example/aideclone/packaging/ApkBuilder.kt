package com.example.aideclone.packaging

import com.android.apksig.ApkSigner
import com.example.aideclone.compiler.DexEngine
import com.example.aideclone.compiler.ResourceCompiler
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.xml.kxml2.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

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
        frameworkApkFile: File? = null,
        libraryResources: List<com.example.aideclone.compiler.ResourceSource> = emptyList()
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
            val resResult = ResourceCompiler.compileResources(projectRoot, frameworkApkFile, packageName, libraryResources)
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

            // Real manifest merging: parse the project's actual
            // AndroidManifest.xml directly into this AndroidManifestBlock,
            // rather than hand-building one field at a time. This is what
            // actually surfaced as broken beyond just the theme: a real
            // device test showed AutoClicker missing entirely from
            // Settings > Accessibility, and its overlay permission toggle
            // never turning on - because the from-scratch manifest never
            // declared the app's <uses-permission> entries or its
            // <service> (with the BIND_ACCESSIBILITY_SERVICE intent-filter
            // + meta-data) at all, only a bare activity.
            //
            // AndroidManifestBlock IS a ResXmlDocument (confirmed via
            // javap: "extends com.reandroid.arsc.chunk.xml.
            // ResXmlDocument", with neither setPackageBlock nor parse
            // overridden), so it inherits the exact same
            // setPackageBlock(packageBlock) + parse(parser) pattern
            // ResourceCompiler.kt already uses successfully for every
            // layout file - @string/@style/@drawable/@xml references in
            // the manifest resolve against the same packageBlock, and
            // android:-namespaced attributes resolve against the same
            // attached framework table, with no new unverified API.
            //
            // Falls back to the old bare-activity construction if the
            // project has no AndroidManifest.xml or it fails to parse,
            // rather than failing the whole build - consistent with
            // every other fallback in this codebase.
            val projectManifestFile = projectRoot.walkTopDown()
                .firstOrNull { it.isFile && it.name == "AndroidManifest.xml" && !it.path.contains("/build/") }
            var mergedRealManifest = false
            if (projectManifestFile != null) {
                try {
                    val parser = KXmlParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    FileInputStream(projectManifestFile).use { input ->
                        parser.setInput(input, null)
                        manifest.setPackageBlock(packageBlock)
                        manifest.parse(parser)
                    }
                    mergedRealManifest = true
                    log.appendLine("Merged real manifest from ${projectManifestFile.path} (permissions, services, theme, etc. all carried through)")

                    // DIAGNOSTIC (temporary): the merge completed without
                    // throwing, but the app still crashed with "You need
                    // to use a Theme.AppCompat theme" at runtime after
                    // this change - meaning parse() may have accepted
                    // android:theme syntactically without actually
                    // resolving/encoding it as a proper reference value.
                    // Trace exactly what's on the attribute now, using
                    // only already-confirmed APIs (searchAttributeByName
                    // on ResXmlElement; getValueType/getData/
                    // getValueAsString/decodeValue on ValueItem, all
                    // confirmed via javap earlier) rather than guessing
                    // again.
                    val appElementCheck = manifest.getOrCreateApplicationElement()
                    val themeAttrCheck = appElementCheck.searchAttributeByName("theme")
                    if (themeAttrCheck == null) {
                        log.appendLine("TRACE: <application> has NO 'theme' attribute at all after parse() - it was dropped entirely.")
                    } else {
                        log.appendLine(
                            "TRACE: <application> theme attribute after parse(): " +
                                "valueType=${themeAttrCheck.valueType}, data=0x${themeAttrCheck.data.toString(16)}, " +
                                "valueAsString=${themeAttrCheck.valueAsString}, decodeValue=${themeAttrCheck.decodeValue()}"
                        )
                    }
                } catch (e: Exception) {
                    log.appendLine("Warning: failed to parse ${projectManifestFile.path}: ${e.message} - falling back to a bare generated manifest (no permissions/services will be declared).")
                }
            } else {
                log.appendLine("Warning: no AndroidManifest.xml found in project - generating a bare manifest (no permissions/services will be declared).")
            }

            // Build-level settings, not sourced from the project manifest
            // (the sample project has no <uses-sdk> etc. of its own) -
            // set/overwritten unconditionally regardless of which path
            // above was taken.
            manifest.setVersionCode(1)
            manifest.setVersionName("0.1")
            manifest.setCompileSdkVersion(34)
            manifest.setCompileSdkVersionCodename("14")
            manifest.setPlatformBuildVersionCode(34)
            manifest.setPlatformBuildVersionName("14")
            manifest.setApplicationLabel(appNameEntry.getResourceId())
            manifest.setMinSdkVersion(24)
            manifest.setTargetSdkVersion(34)

            // Ensures the launcher activity exists even on the fallback
            // (no-real-manifest) path; a no-op / returns the existing
            // element when the real manifest above already declared it.
            manifest.getOrCreateMainActivity(mainActivityClass)
            log.appendLine("Manifest ready for $packageName / $mainActivityClass (real manifest merged: $mergedRealManifest)")

            // DIAGNOSTIC (temporary): re-check the theme attribute here,
            // after getOrCreateMainActivity/setApplicationLabel/etc. ran -
            // in case one of those calls resets or replaces the
            // <application> element that parse() populated, rather than
            // the problem being in parse() itself.
            run {
                val appElementFinal = manifest.getOrCreateApplicationElement()
                val themeAttrFinal = appElementFinal.searchAttributeByName("theme")
                if (themeAttrFinal == null) {
                    log.appendLine("TRACE (final, pre-write): <application> has NO 'theme' attribute.")
                } else {
                    log.appendLine(
                        "TRACE (final, pre-write): <application> theme attribute: " +
                            "valueType=${themeAttrFinal.valueType}, data=0x${themeAttrFinal.data.toString(16)}, " +
                            "valueAsString=${themeAttrFinal.valueAsString}, decodeValue=${themeAttrFinal.decodeValue()}"
                    )
                }
            }

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
