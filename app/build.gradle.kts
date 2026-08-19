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
        versionName = "0.1-M1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    val soraVersion = "0.23.4"
    implementation("com.github.Rosemoe.sora-editor:editor:$soraVersion")
    implementation("com.github.Rosemoe.sora-editor:language-java:$soraVersion")
    implementation("com.github.Rosemoe.sora-editor:language-textmate:$soraVersion")

    // ECJ: Eclipse Compiler for Java, embeddable and pure-Java, so it runs
    // on-device without a JDK. This is the same approach AIDE itself uses
    // (javac isn't available on Android).
    implementation("org.eclipse.jdt:ecj:3.36.0")
}
