plugins {
    id("lib-multisrc")
}

baseVersionCode = 4

dependencies {
    api(project(":lib:megaup-extractor"))
}
