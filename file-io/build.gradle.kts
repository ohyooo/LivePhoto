plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    if (providers.gradleProperty("livephoto.jvm").orNull != "false") {
        jvm()
        jvmToolchain(25)
    }
    when (val target = providers.gradleProperty("livephoto.nativeTarget").orNull) {
        null -> Unit
        "linux-x64" -> linuxX64()
        "macos-arm64" -> macosArm64()
        "windows-x64" -> mingwX64()
        else -> error("Unsupported livephoto.nativeTarget: $target")
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.okio)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
