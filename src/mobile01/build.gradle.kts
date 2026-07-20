ext {
    set("bundleName", "NewsHub: Mobile01")
    set("bundleVersionCode", 1)
    set("bundleVersionName", "0.1.0")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    add("implementation", project(":src:mobile01-source"))
}
