ext {
    set("bundleName", "NewsHub: Komica2")
    set("bundleVersionCode", 10)
    set("bundleVersionName", "0.4.6")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    "implementation"(project(":src:twocat-komica2"))
    "implementation"(project(":src:sora-komica2"))
    "implementation"(project(":src:zawarudo-komica2"))
}
