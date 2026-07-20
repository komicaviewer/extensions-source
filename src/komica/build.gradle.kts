ext {
    set("bundleName", "NewsHub: Komica")
    set("bundleVersionCode", 4)
    set("bundleVersionName", "0.3.1")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    "implementation"(project(":src:twocat-komica"))
    "implementation"(project(":src:sora-komica"))
    "implementation"(project(":src:akraft"))
    "implementation"(project(":src:nagatoyuki"))
    "implementation"(project(":src:wtako"))
}
