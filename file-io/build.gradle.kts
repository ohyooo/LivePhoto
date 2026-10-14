plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    val nativeTarget = providers.gradleProperty("livephoto.nativeTarget").orNull
    when (val target = nativeTarget) {
        "linux-x64" -> linuxX64()
        "macos-arm64" -> macosArm64()
        // Native FFmpeg depends on :process, which already owns these SDK bindings.
        // A second cinterop for the same header can be deduplicated to an empty library.
        "windows-x64" -> mingwX64()
        else -> error("Unsupported livephoto.nativeTarget: $target")
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.okio)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        nativeMain.dependencies {
            implementation(project(":process"))
            implementation(libs.kotlinx.coroutines)
        }
    }
}
