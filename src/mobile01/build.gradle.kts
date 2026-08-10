ext {
    set("bundleName", "NewsHub: Mobile01")
    set("bundleVersionCode", 3)
    set("bundleVersionName", "0.1.2")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    add("implementation", project(":src:mobile01-source"))
}
