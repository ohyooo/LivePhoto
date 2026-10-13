plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    if (providers.gradleProperty("livephoto.jvm").orNull != "false") {
        jvm()
        jvmToolchain(25)
    }
    val host = when (val target = providers.gradleProperty("livephoto.nativeTarget").orNull) {
        null -> null
        "linux-x64" -> linuxX64()
        "macos-arm64" -> macosArm64()
        "windows-x64" -> mingwX64()
        else -> error("Unsupported livephoto.nativeTarget: $target")
    }
    host?.binaries?.executable {
        baseName = "LivePhoto"
        entryPoint = "livephoto.cli.nativeapp.main"
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":cli-shared"))
            implementation(project(":file-io"))
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        if (host != null) {
            nativeMain.dependencies { implementation(libs.kotlinx.coroutines) }
        }
    }
}
