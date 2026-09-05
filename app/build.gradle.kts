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
    kotlinOptions {
        jvmTarget = "17"
    }

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
                "META-INF/NOTICE.txt",
                "**/*.kotlin_builtins",
                "**/*.kotlin_module"
            )
        }
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

    // Kotlin compiler, embeddable variant (a relocated/shaded build meant
    // for exactly this — invoking it programmatically from another JVM
    // tool, which is also how Maven/Ant's Kotlin plugins work without
    // Gradle's daemon). Much larger and more complex than ECJ; expect
    // similar on-device compatibility issues to what ECJ needed fixed,
    // likely more of them given its size.
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.20")
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
    kotlinStdlibBundle("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
}

tasks.register<Copy>("bundleKotlinStdlib") {
    from(kotlinStdlibBundle) { include("kotlin-stdlib-*.jar") }
    into("src/main/assets")
    rename { "kotlin-stdlib.jar" }
}

tasks.named("preBuild") {
    dependsOn("bundleKotlinStdlib")
}
