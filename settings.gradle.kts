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

listOf(
    "akraft",
    "gamer",
    "komica",
    "komica2",
    "nagatoyuki",
    "sora",
    "twocat",
    "wtako",
    "zawarudo",
).forEach { module ->
    include(":src:$module")
    project(":src:$module").projectDir = File(rootDir, "src/$module")
}
