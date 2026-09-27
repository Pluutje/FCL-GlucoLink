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
        // Required for MPAndroidChart (com.github.PhilJay:MPAndroidChart) —
        // that library is only published on JitPack, not Maven Central.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "FCLGlucoLink"
include(":app")
