package com.example.aideclone.compiler

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File
import java.io.PrintStream

/**
 * Loads kotlin-compiler-embeddable from an ISOLATED dex+resources bundle
 * via its own dedicated DexClassLoader, rather than as a normal app
 * dependency merged into classes.dex.
 *
 * Why: the compiler's own KotlinCoreEnvironment bootstrapping needs to
 * discover its bundled extension-point config files (e.g.
 * META-INF/.../extensions/compiler.xml) via getResource()-based
 * self-location — a trick that only works if its code+resources remain
 * a distinguishable, separate classpath unit. Merged anonymously into
 * this app's own dex alongside ECJ/D8/ARSCLib/BouncyCastle/our own code,
 * that self-location breaks with "IllegalStateException: Resource not
 * found" / "Unable to find extension point configuration" — a
 * documented, confirmed issue also hit by Spring Boot fat-jar users
 * bundling this same library, fixed only by keeping it unpacked/separate.
 *
 * Since the compiler classes aren't on this app's own runtime classpath
 * at all (deliberately excluded from the main dependencies — see
 * build.gradle.kts), there's no way to reference them directly at
 * compile time; everything here goes through reflection.
 */
object IsolatedKotlinCompilerLoader {

    private const val BUNDLE_ASSET_NAME = "kotlin-compiler-isolated.jar"
    private var cachedClassLoader: ClassLoader? = null

    private fun getClassLoader(context: Context): ClassLoader {
        cachedClassLoader?.let { return it }

        val appContext = context.applicationContext
        val sdkDir = File(appContext.filesDir, "sdk").apply { mkdirs() }
        val bundleFile = File(sdkDir, BUNDLE_ASSET_NAME)

        // Simple existence check, not a content/size comparison:
        // AssetManager.openFd() (which would give a reliable size for
        // comparison) throws for assets AAPT decides to compress, which
        // a multi-MB .jar is a real candidate for — not worth the
        // fragility. This only matters again if kotlin-compiler-
        // embeddable's version changes; a stale extracted copy from a
        // previous build isn't a concern at the current stable version.
        if (!bundleFile.exists()) {
            appContext.assets.open(BUNDLE_ASSET_NAME).use { input ->
                bundleFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // Android refuses to load a DEX file for execution from a path
        // that's still writable by this app — a security measure (W^X)
        // against loading tampered/dynamically-downloaded code, throwing
        // "SecurityException: Writable dex file ... is not allowed."
        // Marking it read-only right after extraction (idempotent if
        // already set) is the standard, required fix.
        bundleFile.setWritable(false)

        val optimizedDir = File(sdkDir, "dex-cache").apply { mkdirs() }

        // Parent = this app's own classloader, so standard parent-first
        // delegation lets the isolated compiler find our stub classes
        // (javax.lang.model.SourceVersion, java.lang.management.*) and
        // share this app's own kotlin-stdlib classes rather than needing
        // its own separate copy.
        val loader = DexClassLoader(
            bundleFile.absolutePath,
            optimizedDir.absolutePath,
            null,
            IsolatedKotlinCompilerLoader::class.java.classLoader
        )
        cachedClassLoader = loader
        return loader
    }

    /**
     * Reflectively invokes K2JVMCompiler().exec(PrintStream, String...)
     * against classes loaded from the isolated classloader. Returns the
     * ExitCode enum constant's name ("OK", "COMPILATION_ERROR",
     * "INTERNAL_ERROR", etc.) as a plain String, since we can't
     * reference the real ExitCode type without a compile-time dependency
     * on the isolated classes.
     */
    fun execCompiler(context: Context, printStream: PrintStream, args: Array<String>): String {
        val loader = getClassLoader(context)
        val compilerClass = loader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        val compilerInstance = compilerClass.getDeclaredConstructor().newInstance()
        val execMethod = compilerClass.getMethod(
            "exec",
            PrintStream::class.java,
            Array<String>::class.java
        )
        val exitCode = execMethod.invoke(compilerInstance, printStream, args)
        return exitCode.toString()
    }
}
