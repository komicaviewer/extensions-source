ext {
    set("bundleName", "NewsHub: Komica")
    set("bundleVersionCode", 6)
    set("bundleVersionName", "0.3.3")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    "implementation"(project(":src:twocat-komica"))
    "implementation"(project(":src:sora-komica"))
    "implementation"(project(":src:akraft"))
    "implementation"(project(":src:nagatoyuki"))
    "implementation"(project(":src:wtako"))
}
