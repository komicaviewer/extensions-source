ext {
    set("libraryNamespace", "tw.kevinzhang.newshub.extension.twocat")
    set("engineFlavors", listOf("komica", "komica2"))
}
apply(from = "$rootDir/library.gradle")
