ext {
    set("extName", "NewsHub: Nagatoyuki")
    set("extClass", ".NagatoyukiSource")
    set("extVersionCode", 1)
    set("extVersionName", "0.0.1")
}
apply(from = "$rootDir/common.gradle")

dependencies {
    "implementation"(project(":src:komica-common"))
}
