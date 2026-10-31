pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "psphere-desktop"

// Shared sync engine (pure Kotlin), also used by the Android app.
includeBuild("../shared")
