ext {
    set("bundleName", "NewsHub: EYNY 伊莉討論區")
    set("bundleVersionCode", 8)
    set("bundleVersionName", "0.1.7")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    add("implementation", project(":src:eyny-source"))
}
