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

    fun selfTest(context: Context): String {
        val out = StringBuilder()
        val started = System.currentTimeMillis()
        try {
            val l = kspLoader(context)
            out.appendLine("bundle loaded in ${System.currentTimeMillis() - started} ms")

            val tmp = File(context.cacheDir, "ksp-selftest").apply { deleteRecursively(); mkdirs() }
            val src = File(tmp, "src").apply { mkdirs() }
            File(src, "Hello.kt").writeText("package demo\n\nclass Hello {\n    fun hi(): Int = 1\n}\n")
            val sdk = File(context.filesDir, "sdk")
            val libs = listOf(File(sdk, "android.jar"), File(sdk, "kotlin-stdlib.jar")).filter { it.exists() }
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
                        out.appendLine("KSP." + m.name + ": " + (args?.joinToString { it.toString() } ?: ""))
                        null
                    }
                }
            }

            val spClass = l.loadClass("com.google.devtools.ksp.impl.KotlinSymbolProcessing")
            val cfgClass = l.loadClass("com.google.devtools.ksp.processing.KSPConfig")
            val ctor = spClass.getConstructor(cfgClass, List::class.java, loggerIface)
            val ksp = ctor.newInstance(config, emptyList<Any>(), logger)
            out.appendLine("KotlinSymbolProcessing constructed, executing...")
            val exit = spClass.getMethod("execute").invoke(ksp)
            out.appendLine("RESULT exit=$exit after ${System.currentTimeMillis() - started} ms")
        } catch (t: Throwable) {
            val cause = if (t is InvocationTargetException) (t.cause ?: t) else t
            out.appendLine("FAILED: $cause")
            out.appendLine(cause.stackTraceToString().lines().take(40).joinToString("\n"))
        }
        return out.toString()
    }
}
