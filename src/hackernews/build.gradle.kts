ext {
    set("extName", "NewsHub: Hacker News")
    set("extVersionCode", 4)
    set("extVersionName", "0.1.3")
}
apply(from = "$rootDir/common.gradle")

dependencies {
    add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    add("testImplementation", "com.squareup.okhttp3:mockwebserver:4.12.0")
}
