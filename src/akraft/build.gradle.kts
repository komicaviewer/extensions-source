ext {
    set("extName", "NewsHub: Akraft")
    set("extClass", ".AkraftSource")
    set("extVersionCode", 1)
    set("extVersionName", "0.0.1")
}
apply(from = "$rootDir/common.gradle")

dependencies {
    "implementation"(project(":src:komica-common"))
}
