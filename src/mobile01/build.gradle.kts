ext {
    set("bundleName", "NewsHub: Mobile01")
    set("bundleVersionCode", 7)
    set("bundleVersionName", "0.1.6")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    add("implementation", project(":src:mobile01-source"))
}
