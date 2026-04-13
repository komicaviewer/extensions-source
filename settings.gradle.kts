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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "extensions-source"

// komica-common is a shared Android library, not an APK extension
include(":src:komica-common")
project(":src:komica-common").projectDir = File(rootDir, "src/komica-common")

// Dynamically include all APK extension modules under src/ (excludes komica-common)
File(rootDir, "src").takeIf { it.isDirectory }?.listFiles()
    ?.filter { it.isDirectory && File(it, "build.gradle.kts").exists() && it.name != "komica-common" }
    ?.forEach { dir ->
        val moduleName = ":src:${dir.name}"
        include(moduleName)
        project(moduleName).projectDir = dir
    }
