ext {
    set("bundleName", "NewsHub: Mobile01")
    set("bundleVersionCode", 4)
    set("bundleVersionName", "0.1.3")
}
apply(from = "$rootDir/bundle.gradle")

dependencies {
    add("implementation", project(":src:mobile01-source"))
}
