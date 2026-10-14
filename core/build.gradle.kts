plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    // Select only the current host; never execute another platform's test binary.
    when (val nativeTarget = providers.gradleProperty("livephoto.nativeTarget").orNull) {
        "linux-x64" -> linuxX64()
        "macos-arm64" -> macosArm64()
        "windows-x64" -> mingwX64()
        else -> error("Unsupported livephoto.nativeTarget: $nativeTarget")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // Test compilation only: Core main remains stdlib-only. The dependency
        // is on adapter main -> Core main, not another module's tests/build task.
        nativeTest.dependencies {
            implementation(project(":file-io"))
            implementation(libs.kotlinx.coroutines)
        }
    }
}
