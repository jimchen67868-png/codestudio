import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.aideclone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.aideclone"
        // 26, not 24: kotlin-compiler-embeddable bundles IntelliJ platform
        // code that uses java.lang.invoke.MethodHandle.invoke/invokeExact
        // directly. D8 can't dex those calls below API 26 — this isn't a
        // desugaring gap that can be worked around, ART itself needs to
        // support MethodHandle natively. This only raises the floor for
        // running AIDEClone itself; it's independent of the minSdk set
        // for whatever projects it builds (still 24, see ApkBuilder).
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-M3"
    }

    // Fixed debug signing key, checked into the repo. Without this, AGP
    // auto-generates a random debug.keystore per machine/CI run, so every
    // GitHub Actions build gets a DIFFERENT signature — and Android
    // refuses to install an update whose signature doesn't match what's
    // already installed. That silent "App not installed" failure (easy to
    // miss/dismiss) is what made it look like builds weren't taking effect.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "aideclone123"
            keyAlias = "aideclone-debug"
            keyPassword = "aideclone123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // kotlinOptions { jvmTarget = "17" } is now a hard error under Kotlin
    // 2.4.10 (was just a deprecation warning earlier this session) —
    // migrated to the new compilerOptions DSL, set via the top-level
    // `kotlin { }` extension block below instead.

    buildFeatures {
        viewBinding = true
    }

    // isDebuggable=false (needed for real R8 shrinking, see buildTypes
    // below) also activates AGP's "lint vital" check, normally skipped
    // for debuggable builds. That check's own bundled Kotlin metadata
    // reader is older than our Kotlin 2.2.x dependencies and fails
    // parsing them — a different component than our project's own Kotlin
    // plugin version, not fixable the same way. We don't need Play
    // Store-style lint enforcement for a personal sideloaded app anyway.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // Bouncy Castle's several jars (bcprov/bcpkix/bcutil) all ship an
    // identical META-INF/versions/9/OSGI-INF/MANIFEST.MF path — harmless
    // duplication, but Gradle's resource merger doesn't like it, so we
    // just tell it which copies to drop. Similarly, kotlin-compiler-
    // embeddable bundles its own copy of Kotlin's built-in metadata
    // (*.kotlin_builtins), which duplicates our own kotlin-stdlib
    // dependency's copies — wildcarded since there are many individual
    // files (kotlin.kotlin_builtins, coroutines, ranges, text, etc.) and
    // they'd otherwise surface one CI failure at a time.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
            // IMPORTANT: pickFirsts, not excludes, for these two.
            // *.kotlin_builtins files are pre-serialized built-in type
            // metadata (kotlin.kotlin_builtins, collections, ranges,
            // etc.) that the Kotlin compiler reads at RUNTIME to
            // bootstrap JvmBuiltIns. Excluding them entirely (our
            // earlier fix for a duplicate-file packaging conflict)
            // removes them from the APK altogether, forcing the
            // compiler onto a slow reflection-based fallback path
            // (kotlin-reflect's JvmBuiltIns.getCustomizer() ->
            // DeserializationComponentsForJava -> module-by-classloader
            // bootstrapping) that then breaks on Android's classloader
            // model. pickFirsts keeps ONE copy (deduplicated, not
            // removed), which should let normal bootstrapping succeed.
            pickFirsts += setOf(
                "**/*.kotlin_builtins",
                "**/*.kotlin_module"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    // Sora Editor: the code editor engine (syntax highlighting, large-file
    // handling, IME support). This is the same engine several AIDE-alike
    // projects use instead of writing a text engine from scratch.
    // Note: the library migrated its Maven group from
    // io.github.Rosemoe.sora-editor to io.github.rosemoe (lowercase) in
    // mid-2025; the old group no longer resolves.
    implementation(platform("io.github.rosemoe:editor-bom:0.24.4"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-java")
    implementation("io.github.rosemoe:language-textmate")

    // ECJ: Eclipse Compiler for Java, embeddable and pure-Java, so it runs
    // on-device without a JDK. This is the same approach AIDE itself uses
    // (javac isn't available on Android).
    implementation("org.eclipse.jdt:ecj:3.36.0")

    // M3: dex + resource/manifest packaging + signing, all pure-JVM so it
    // runs on-device with no native binaries required.
    // D8: the AOSP dexer, turns .class files into classes.dex.
    implementation("com.android.tools:r8:8.5.10")
    // ARSCLib: pure-Java replacement for aapt2 — builds resources.arsc and
    // binary AndroidManifest.xml without needing a native ARM aapt2 binary.
    implementation("io.github.reandroid:ARSCLib:1.4.0")
    // apksig: the same library `apksigner` itself is built on; pure Java.
    implementation("com.android.tools.build:apksig:8.5.2")
    // Bouncy Castle: generates a self-signed debug cert on-device. Android's
    // runtime doesn't include the standard JDK's X.509 cert-builder classes,
    // so we can't use plain java.security for this the way desktop keytool does.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // kotlin.jvm.internal.Reflection's static initializer runs once per
    // classloader and tries to locate kotlin.reflect.jvm.internal.
    // ReflectionFactoryImpl. Since Reflection is part of kotlin-stdlib
    // (shared with the isolated compiler bundle via parent-classloader
    // delegation, not duplicated there), whichever classloader touches
    // it FIRST determines whether reflection ends up permanently
    // "available" or falls back to a limited stub for the rest of the
    // process — and normal Kotlin app code triggers this early, well
    // before the isolated compiler bundle is ever loaded. Adding
    // kotlin-reflect only to the isolated bundle wasn't enough; it has
    // to be on this app's own classpath from the start too. (Unlike
    // kotlin-compiler-embeddable, kotlin-reflect doesn't have the
    // IntelliJ-platform self-location architecture that required
    // isolation in the first place, so bundling it normally here is safe.)
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10")

    // NOTE: kotlin-compiler-embeddable is deliberately NOT declared here.
    // It's isolated into its own separate dex bundle instead — see
    // buildIsolatedKotlinCompilerBundle below and
    // IsolatedKotlinCompilerLoader.kt. Merging it into this app's own
    // classes.dex broke its internal KotlinCoreEnvironment bootstrapping
    // ("Unable to find extension point configuration"), which relies on
    // discovering its own bundled resource files via getResource()-based
    // self-location — a trick that only works if its code remains a
    // distinguishable, separate classpath unit. This is a documented,
    // confirmed requirement (the same root cause Spring Boot fat-jar
    // users hit with this exact library, fixed only by keeping its jars
    // unpacked/separate rather than merged).
}

// The embedded Kotlin compiler needs kotlin-stdlib.jar as an explicit
// classpath entry when compiling user Kotlin source (compiling against
// android.jar alone isn't enough — Kotlin code always implicitly
// references kotlin.* runtime classes). Our own app's kotlin-stdlib gets
// merged into classes.dex at build time, which isn't usable as a
// classpath entry for an external tool expecting real .class-in-jar
// content — so we bundle a standalone copy as an asset instead.
val kotlinStdlibBundle: Configuration by configurations.creating

dependencies {
    kotlinStdlibBundle("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
}

tasks.register<Copy>("bundleKotlinStdlib") {
    from(kotlinStdlibBundle) { include("kotlin-stdlib-*.jar") }
    into("src/main/assets")
    rename { "kotlin-stdlib.jar" }
}

// Isolates kotlin-compiler-embeddable into its own separate dex+resources
// bundle, loaded at runtime via a dedicated DexClassLoader (see
// IsolatedKotlinCompilerLoader.kt) rather than merged into this app's own
// classes.dex. See the NOTE in the dependencies block above for why.
//
// Two things have to be preserved for the isolated compiler to actually
// work, not just its .class files:
//   1. Its bytecode, dexed via D8 (D8's CLI accepts whole jars directly
//      and dexes only their .class entries).
//   2. Its bundled NON-class resources (extension-point XML configs
//      etc.) — D8 silently drops these since dexing only processes
//      .class files. They're copied through as-is into the same bundle,
//      since that's literally the exact resource the compiler crashes
//      looking for if it's missing.
val isolatedKotlinCompiler: Configuration by configurations.creating
val r8Tool: Configuration by configurations.creating

dependencies {
    isolatedKotlinCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    // The compiler's OWN internal CLI-argument-parsing code
    // (ArgumentUtilsKt) uses real Kotlin reflection (KProperty.javaField
    // etc.) to inspect its own argument classes at a static initializer
    // — unrelated to our -no-reflect compiler flag, which only controls
    // whether reflect gets added to the classpath for code WE compile.
    // Without this, static init fails with "IllegalStateException: Java
    // field should be present for property fragments (Kotlin reflection
    // is not available)" before any source file is even looked at.
    isolatedKotlinCompiler("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
    // javax.xml.stream (JSR-173 StAX API) — Android has never shipped
    // this JDK package at all. kotlin-compiler-embeddable already
    // bundles its own real StAX implementation (a relocated/shaded copy
    // of Woodstox, visible as org.jetbrains.kotlin.org.codehaus.stax2.*
    // in stack traces) which it uses to parse its own bundled plugin
    // descriptor XML files — it just needs the STANDARD interface
    // definitions to implement against. A real dependency here, not a
    // hand-written stub, since getting a ~50-method interface's exact
    // parsing semantics right by hand would be far more error-prone than
    // using the actual standard API definitions.
    isolatedKotlinCompiler("javax.xml.stream:stax-api:1.0-2")
    r8Tool("com.android.tools:r8:8.5.10")
}

tasks.register("buildIsolatedKotlinCompilerBundle") {
    val dexOutputDir = layout.buildDirectory.dir("isolated-kotlin-compiler-dex").get().asFile
    val bundleOutput = file("src/main/assets/kotlin-compiler-isolated.jar")
    val sourceJars = isolatedKotlinCompiler

    doLast {
        dexOutputDir.deleteRecursively()
        dexOutputDir.mkdirs()

        val androidLibArgs = android.bootClasspath.flatMap { listOf("--lib", it.absolutePath) }
        val jarFiles = sourceJars.files.toList()

        project.javaexec {
            classpath = r8Tool
            mainClass.set("com.android.tools.r8.D8")
            args = listOf(
                "--min-api", "26",
                "--output", dexOutputDir.absolutePath
            ) + androidLibArgs + jarFiles.map { it.absolutePath }
        }

        bundleOutput.parentFile.mkdirs()
        if (bundleOutput.exists()) bundleOutput.delete()

        ZipOutputStream(bundleOutput.outputStream()).use { zos ->
            val writtenEntries = mutableSetOf<String>()

            fun writeEntry(name: String, bytes: ByteArray) {
                if (writtenEntries.add(name)) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }

            // classes.dex, classes2.dex, etc. — must come from D8's
            // output, which is what DexClassLoader actually executes.
            dexOutputDir.listFiles { f -> f.extension == "dex" }
                ?.sortedBy { it.name }
                ?.forEach { writeEntry(it.name, it.readBytes()) }

            // Everything else, INCLUDING the original .class file bytes
            // (not just non-class resources) — carried through as
            // passive zip entries alongside the actual executable
            // classes.dex. This is the real fix for
            // "IllegalStateException: Resource not found: /some/Class
            // .class": DEX format has no concept of individual browsable
            // per-class resources at all, so PathUtil's
            // getResource()-based self-location can never succeed
            // against dexed code alone, no matter how it's packaged or
            // isolated. Keeping the original .class bytes present too
            // (unused for actual class loading — DexClassLoader always
            // prefers its dex code for that) gives getResource() a real
            // zip entry to find, satisfying the self-location check.
            // First entry wins on path collisions across jars (mirrors
            // the app's own pickFirsts behavior above).
            jarFiles.forEach { jarFile ->
                ZipFile(jarFile).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory }
                        .forEach { entry ->
                            zip.getInputStream(entry).use { input ->
                                writeEntry(entry.name, input.readBytes())
                            }
                        }
                }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("bundleKotlinStdlib")
    dependsOn("buildIsolatedKotlinCompilerBundle")
}
