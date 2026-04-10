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

// Dynamically include all extension modules under src/
File(rootDir, "src").takeIf { it.isDirectory }?.listFiles()
    ?.filter { it.isDirectory && File(it, "build.gradle.kts").exists() }
    ?.forEach { dir ->
        val moduleName = ":src:${dir.name}"
        include(moduleName)
        project(moduleName).projectDir = dir
    }
