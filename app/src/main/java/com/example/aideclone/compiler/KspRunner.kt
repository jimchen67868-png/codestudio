package com.example.aideclone.compiler

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * SPIKE: can KSP2 (symbol-processing-aa-embeddable) start and finish a run
 * on ART at all? Everything goes through reflection because the KSP classes
 * live in their own dex bundle (ksp-isolated.jar), loaded with the
 * compiler's classloader as parent. No processors are run, just analysis
 * session setup over one tiny source file.
 */
object KspRunner {
    private const val BUNDLE_ASSET_NAME = "ksp-isolated.jar"
    private var cachedLoader: ClassLoader? = null

    private fun kspLoader(context: Context): ClassLoader {
        cachedLoader?.let { return it }
        val appContext = context.applicationContext
        val sdkDir = File(appContext.filesDir, "sdk").apply { mkdirs() }
        val bundleFile = File(sdkDir, BUNDLE_ASSET_NAME)
        val stampFile = File(sdkDir, "ksp-isolated.stamp")
        val stamp = appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime.toString()
        if (!bundleFile.exists() || !stampFile.exists() || stampFile.readText() != stamp) {
            bundleFile.delete()
            appContext.assets.open(BUNDLE_ASSET_NAME).use { input ->
                bundleFile.outputStream().use { output -> input.copyTo(output) }
            }
            stampFile.writeText(stamp)
        }
        bundleFile.setWritable(false)
        val optimizedDir = File(sdkDir, "ksp-dex-cache").apply { mkdirs() }
        val parent = IsolatedKotlinCompilerLoader.getClassLoader(appContext)
        val loader = DexClassLoader(bundleFile.absolutePath, optimizedDir.absolutePath, null, parent)
        cachedLoader = loader
        return loader
    }

    class KspResult(val ran: Boolean, val success: Boolean, val log: String, val javaDir: File)

    fun runProject(context: Context, projectRoot: File, androidJar: File, stdlib: File, extraLibs: List<File>): KspResult {
        val javaDir = File(projectRoot, "build/generated/ksp/java")
        javaDir.deleteRecursively()
        val libs = (listOf(androidJar, stdlib) + extraLibs).distinctBy { it.name }
        if (libs.none { it.name.startsWith("room-runtime") }) return KspResult(false, true, "", javaDir)
        val out = StringBuilder()
        var ok = false
        try {
            val l = kspLoader(context)
            context.cacheDir.listFiles { f -> f.name.startsWith("ksp-build") }?.forEach { if (System.currentTimeMillis() - it.lastModified() > 600000) it.deleteRecursively() }
            val tmp = File(context.cacheDir, "ksp-build-" + System.nanoTime()).apply { mkdirs() }
            val src = File(tmp, "src").apply { mkdirs() }
            projectRoot.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") && !it.relativeTo(projectRoot).path.startsWith("build/") }
                .forEach { it.copyTo(File(src, it.relativeTo(projectRoot).path), true) }
            val builderClass = l.loadClass("com.google.devtools.ksp.processing.KSPJvmConfig\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            fun set(name: String, argType: Class<*>, value: Any?) {
                builderClass.getMethod(name, argType).invoke(builder, value)
            }
            set("setModuleName", String::class.java, "project")
            set("setSourceRoots", List::class.java, listOf(src))
            set("setLibraries", List::class.java, libs)
            set("setProjectBaseDir", File::class.java, tmp)
            set("setOutputBaseDir", File::class.java, File(tmp, "out"))
            set("setCachesDir", File::class.java, File(tmp, "caches"))
            set("setClassOutputDir", File::class.java, File(tmp, "out/classes"))
            set("setKotlinOutputDir", File::class.java, File(tmp, "out/kotlin"))
            set("setJavaOutputDir", File::class.java, File(tmp, "out/java"))
            set("setResourceOutputDir", File::class.java, File(tmp, "out/res"))
            set("setLanguageVersion", String::class.java, "2.3")
            set("setApiVersion", String::class.java, "2.3")
            set("setJvmTarget", String::class.java, "17")
            val config = builderClass.getMethod("build").invoke(builder)
            val loggerIface = l.loadClass("com.google.devtools.ksp.processing.KSPLogger")
            val logger = Proxy.newProxyInstance(l, arrayOf(loggerIface)) { _, m, args ->
                when (m.name) {
                    "hashCode" -> 0
                    "equals" -> false
                    "toString" -> "ksp-logger"
                    else -> { out.appendLine("KSP." + m.name + ": " + (args?.joinToString { java.util.Objects.toString(it) } ?: "")); null }
                }
            }
            val spClass = l.loadClass("com.google.devtools.ksp.impl.KotlinSymbolProcessing")
            val cfgClass = l.loadClass("com.google.devtools.ksp.processing.KSPConfig")
            val ctor = spClass.getConstructor(cfgClass, List::class.java, loggerIface)
            val pc = l.loadClass("androidx.room.RoomKspProcessor\$Provider")
            val ksp = ctor.newInstance(config, listOf(pc.getDeclaredConstructor().newInstance()), logger)
            val exit = spClass.getMethod("execute").invoke(ksp)
            out.appendLine("KSP exit=$exit")
            ok = exit.toString() == "OK"
            if (ok) {
                fixContinuationWildcards(File(tmp, "out/java"), out)
                File(tmp, "out/java").takeIf { it.exists() }?.copyRecursively(javaDir, true)
            }
        } catch (t: Throwable) {
            val cause = if (t is InvocationTargetException) (t.cause ?: t) else t
            out.appendLine("KSP FAILED: $cause")
            out.appendLine(cause.stackTraceToString().lines().take(30).joinToString("\n"))
        }
        return KspResult(true, ok, out.toString(), javaDir)
    }

    fun selfTest(context: Context): String {
        val out = StringBuilder()
        val started = System.currentTimeMillis()
        try {
            val l = kspLoader(context)
            out.appendLine("bundle loaded in ${System.currentTimeMillis() - started} ms")

            val tmp = File(context.cacheDir, "ksp-selftest").apply { deleteRecursively(); mkdirs() }
            val src = File(tmp, "src").apply { mkdirs() }
            val roomDir = File("/storage/emulated/0/Download/ksp-room-libs")
            val roomJars = roomDir.listFiles { f -> f.name.endsWith(".jar") }?.toList() ?: emptyList()
            val roomMode = roomJars.any { it.name.startsWith("room-runtime") }
            out.appendLine("roomMode=$roomMode")
            val userSrc = File(roomDir, "src")
            val userFiles = userSrc.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }.toList()
            if (roomMode && userFiles.isNotEmpty()) {
                userFiles.forEach { it.copyTo(File(src, it.relativeTo(userSrc).path), true) }
                out.appendLine("user sources: " + userFiles.size)
            } else if (roomMode) {
                File(src, "Room.kt").writeText("""package demo

import androidx.room.*

@Entity(tableName = "notes")
data class Note(@PrimaryKey val id: Int, val text: String)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes") fun all(): List<Note>
    @Insert fun add(n: Note)
}

@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}
""")
            } else {
                File(src, "Hello.kt").writeText("package demo\n\nclass Hello {\n    fun hi(): Int = 1\n}\n")
            }
            val sdk = File(context.filesDir, "sdk")
            val libs = (listOf(File(sdk, "android.jar"), File(sdk, "kotlin-stdlib.jar")).filter { it.exists() } + roomJars).distinctBy { it.name }
            out.appendLine("libraries: " + libs.joinToString { it.name })

            val builderClass = l.loadClass("com.google.devtools.ksp.processing.KSPJvmConfig\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            fun set(name: String, argType: Class<*>, value: Any?) {
                builderClass.getMethod(name, argType).invoke(builder, value)
            }
            set("setModuleName", String::class.java, "selftest")
            set("setSourceRoots", List::class.java, listOf(src))
            set("setLibraries", List::class.java, libs)
            set("setProjectBaseDir", File::class.java, tmp)
            set("setOutputBaseDir", File::class.java, File(tmp, "out"))
            set("setCachesDir", File::class.java, File(tmp, "caches"))
            set("setClassOutputDir", File::class.java, File(tmp, "out/classes"))
            set("setKotlinOutputDir", File::class.java, File(tmp, "out/kotlin"))
            set("setJavaOutputDir", File::class.java, File(tmp, "out/java"))
            set("setResourceOutputDir", File::class.java, File(tmp, "out/res"))
            set("setLanguageVersion", String::class.java, "2.3")
            set("setApiVersion", String::class.java, "2.3")
            set("setJvmTarget", String::class.java, "17")
            val config = builderClass.getMethod("build").invoke(builder)
            out.appendLine("config built")

            val loggerIface = l.loadClass("com.google.devtools.ksp.processing.KSPLogger")
            val logger = Proxy.newProxyInstance(l, arrayOf(loggerIface)) { _, m, args ->
                when (m.name) {
                    "hashCode" -> 0
                    "equals" -> false
                    "toString" -> "ksp-logger"
                    else -> {
                        out.appendLine("KSP." + m.name + ": " + (args?.joinToString { java.util.Objects.toString(it) } ?: ""))
                        null
                    }
                }
            }

            val spClass = l.loadClass("com.google.devtools.ksp.impl.KotlinSymbolProcessing")
            val cfgClass = l.loadClass("com.google.devtools.ksp.processing.KSPConfig")
            val ctor = spClass.getConstructor(cfgClass, List::class.java, loggerIface)
            val providers: List<Any> = if (roomMode) {
                val pc = l.loadClass("androidx.room.RoomKspProcessor\$Provider")
                listOf(pc.getDeclaredConstructor().newInstance())
            } else emptyList()
            val ksp = ctor.newInstance(config, providers, logger)
            out.appendLine("KotlinSymbolProcessing constructed, executing...")
            val exit = spClass.getMethod("execute").invoke(ksp)
            out.appendLine("RESULT exit=$exit after ${System.currentTimeMillis() - started} ms")
            fixContinuationWildcards(File(tmp, "out/java"), out)
            tmp.walkTopDown().filter { it.isFile && it.path.contains("/out/") }.forEach { out.appendLine("generated: " + it.relativeTo(tmp)) }
            if (roomMode) {
                try {
                    val dst = File(roomDir, "out")
                    dst.deleteRecursively()
                    File(tmp, "out").copyRecursively(dst, true)
                    out.appendLine("copied output to " + dst.path)
                } catch (e: Exception) { out.appendLine("copy failed: $e") }
            }
        } catch (t: Throwable) {
            val cause = if (t is InvocationTargetException) (t.cause ?: t) else t
            out.appendLine("FAILED: $cause")
            out.appendLine(cause.stackTraceToString().lines().take(40).joinToString("\n"))
        }
        return out.toString()
    }
}

// Room 2.6.1 on-device drops the "? super" wildcard on the synthetic
// $completion parameter, which javac rejects as a name clash. Restore it.
private fun fixContinuationWildcards(dir: File, out: StringBuilder) {
    if (!dir.exists()) return
    val pat = Regex("""Continuation<(?!\?)(.+)>(\s+)[$]completion\b""")
    dir.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.forEach { f ->
        val text = f.readText()
        var n = 0
        val fixed = pat.replace(text) { m ->
            n++
            "Continuation<? super " + m.groupValues[1] + ">" + m.groupValues[2] + "\$completion"
        }
        if (n > 0) {
            f.writeText(fixed)
            out.appendLine("fixed " + n + " continuation params in " + f.name)
        } else if (text.contains("Continuation<") && !text.contains("Continuation<? super")) {
            out.appendLine("WARN: " + f.name + " has Continuation<> but no param matched")
        }
    }
}
