ext {
    set("extName", "NewsHub: komica2 twocat")
    set("extClass", ".Komica2TwocatSource")
    set("extVersionCode", 1)
    set("extVersionName", "0.0.1")
}
apply(from = "$rootDir/common.gradle")

dependencies {
    "implementation"(project(":src:komica-common"))
}
