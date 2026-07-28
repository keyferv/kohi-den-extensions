plugins {
    id("lib-multisrc")
}

baseVersionCode = 1

dependencies {
    api(project(":lib:playlist-utils"))
    api(project(":lib:m3u8-server"))
}
