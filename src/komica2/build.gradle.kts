ext {
    set("bundleName", "NewsHub: Komica2")
    set("bundleVersionCode", 2)
    set("bundleVersionName", "0.2.0")
    set("engineFlavor", "komica2")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    "implementation"(project(":src:twocat"))
    "implementation"(project(":src:sora"))
    "implementation"(project(":src:zawarudo"))
}
