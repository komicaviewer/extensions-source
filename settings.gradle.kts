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
    "hackernews",
    "komica",
    "komica2",
    "nagatoyuki",
    "ptt",
    "sora-komica",
    "sora-komica2",
    "twocat-komica",
    "twocat-komica2",
    "wtako",
    "zawarudo-komica2",
).forEach { module ->
    include(":src:$module")
    project(":src:$module").projectDir = File(rootDir, "src/$module")
}
