plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    when (val target = providers.gradleProperty("livephoto.nativeTarget").orNull) {
        "linux-x64" -> linuxX64()
        "macos-arm64" -> macosArm64()
        "windows-x64" -> mingwX64()
        else -> error("Unsupported livephoto.nativeTarget: $target")
    }
    sourceSets {
        commonMain.dependencies { api(project(":core")) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
