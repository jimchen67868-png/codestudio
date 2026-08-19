pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Sora Editor is published via JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AIDEClone"
include(":app")
