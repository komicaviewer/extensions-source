ext {
    set("extName", "NewsHub: Komica2")
    set("extClass", ".Komica2Source")
    set("extVersionCode", 2)
    set("extVersionName", "0.0.2")
}
apply(from = "$rootDir/common.gradle")

dependencies {
    "implementation"(project(":src:komica-common"))
}
