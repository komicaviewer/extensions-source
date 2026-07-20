ext {
    set("bundleName", "NewsHub: Komica2")
    set("bundleVersionCode", 4)
    set("bundleVersionName", "0.4.0")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    "implementation"(project(":src:twocat-komica2"))
    "implementation"(project(":src:sora-komica2"))
    "implementation"(project(":src:zawarudo-komica2"))
}
