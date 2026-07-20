ext {
    set("bundleName", "NewsHub: Komica2")
    set("bundleVersionCode", 3)
    set("bundleVersionName", "0.3.0")
    set("engineFlavor", "komica2")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    "implementation"(project(":src:twocat"))
    "implementation"(project(":src:sora"))
    "implementation"(project(":src:zawarudo"))
}
