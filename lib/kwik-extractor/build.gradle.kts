plugins {
    id("lib-android")
}

dependencies {
    implementation(project(":lib:cloudflare-interceptor"))
    implementation(project(":lib:playlist-utils"))
    implementation(project(":lib:unpacker"))
    implementation(libs.jsunpacker) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}
