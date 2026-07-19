ext {
    set("bundleName", "NewsHub: Komica")
    set("bundleVersionCode", 2)
    set("bundleVersionName", "0.2.0")
    set("engineFlavor", "komica")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    "implementation"(project(":src:twocat"))
    "implementation"(project(":src:sora"))
}
