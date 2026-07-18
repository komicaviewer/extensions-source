ext {
    set("extName", "NewsHub: Komica2 Zawarudo")
    set("extClass", ".Komica2ZawarudoSource")
    set("extVersionCode", 1)
    set("extVersionName", "0.0.1")
}
apply(from = "$rootDir/common.gradle")

dependencies {
    "implementation"(project(":src:komica-common"))
}
