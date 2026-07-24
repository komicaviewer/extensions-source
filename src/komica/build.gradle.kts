ext {
    set("bundleName", "NewsHub: Komica")
    set("bundleVersionCode", 5)
    set("bundleVersionName", "0.3.2")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    "implementation"(project(":src:twocat-komica"))
    "implementation"(project(":src:sora-komica"))
    "implementation"(project(":src:akraft"))
    "implementation"(project(":src:nagatoyuki"))
    "implementation"(project(":src:wtako"))
}
