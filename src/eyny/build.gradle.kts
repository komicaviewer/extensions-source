ext {
    set("bundleName", "NewsHub: EYNY 伊莉討論區")
    set("bundleVersionCode", 4)
    set("bundleVersionName", "0.1.3")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    add("implementation", project(":src:eyny-source"))
}
