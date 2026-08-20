plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.aideclone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.aideclone"
        minSdk = 24
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
        create("debug") {
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

    // Bouncy Castle's several jars (bcprov/bcpkix/bcutil) all ship an
    // identical META-INF/versions/9/OSGI-INF/MANIFEST.MF path — harmless
    // duplication, but Gradle's resource merger doesn't like it, so we
    // just tell it which copies to drop.
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
}
