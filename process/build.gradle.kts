plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    explicitApi()
    val host = when (val target = providers.gradleProperty("livephoto.nativeTarget").orNull) {
        "linux-x64" -> linuxX64()
        "macos-arm64" -> macosArm64()
        "windows-x64" -> mingwX64()
        else -> error("Process adapter requires a supported livephoto.nativeTarget: $target")
    }
    // The same POSIX implementation is compiled for either Unix host. Keep the
    // default hierarchy intact; this is a shared source directory, not a JVM target.
    if (host.name != "mingwX64") {
        sourceSets.getByName("${host.name}Main").kotlin.srcDir("src/posixMain/kotlin")
    }
    sourceSets {
        commonMain.dependencies { implementation(libs.kotlinx.coroutines) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
