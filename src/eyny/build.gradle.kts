ext {
    set("bundleName", "NewsHub: EYNY 伊莉討論區")
    set("bundleVersionCode", 1)
    set("bundleVersionName", "0.1.0")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    add("implementation", project(":src:eyny-source"))
}
