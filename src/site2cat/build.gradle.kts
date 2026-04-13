ext {
    set("extName", "NewsHub: 2cat")
    set("extClass", ".Site2catSource")
    set("extVersionCode", 1)
    set("extVersionName", "0.0.1")
}
apply(from = "$rootDir/common.gradle")

dependencies {
    "implementation"(project(":src:komica-common"))
}
