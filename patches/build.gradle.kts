group = "dev.gcampatch.nativelocation"
version = "1.0.0"

patches {
    about {
        name = "Pixel Camera Native Location"
        description = "Forces Pixel Camera geotagging to use Android's own LocationManager " +
            "(GPS + network providers) instead of the Google Play Services Fused Location " +
            "Provider, with a last-known-location fallback for when no fresh fix has arrived yet."
        source = "https://github.com/salivo/pixelcamera-patches"
        author = "salivo"
        contact = "na"
        website = "https://github.com/salivo/pixelcamera-patches"
        license = "GPLv3"
    }
}

dependencies {
    compileOnly(libs.gson)
    compileOnly(libs.morphe.patcher)
    compileOnly(libs.smali)

    testImplementation(kotlin("test"))
    testImplementation(libs.morphe.patcher)
    testImplementation(libs.smali)
}

tasks.named("jar") {
    finalizedBy("buildAndroid")
}

tasks.named("assemble") {
    dependsOn("buildAndroid")
}
