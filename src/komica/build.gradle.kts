ext {
    set("bundleName", "NewsHub: Komica")
    set("bundleVersionCode", 7)
    set("bundleVersionName", "0.3.4")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    "implementation"(project(":src:twocat-komica"))
    "implementation"(project(":src:sora-komica"))
    "implementation"(project(":src:akraft"))
    "implementation"(project(":src:nagatoyuki"))
    "implementation"(project(":src:wtako"))
}
