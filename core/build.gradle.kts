plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()
    if (providers.gradleProperty("livephoto.jvm").orNull != "false") {
        jvm()
        jvmToolchain(25)
    }

    // Native is opt-in per host. Ordinary JVM/NAS builds must not download a
    // Native compiler or try to execute another platform's test binary.
    when (val nativeTarget = providers.gradleProperty("livephoto.nativeTarget").orNull) {
        null -> Unit
        "linux-x64" -> linuxX64()
        "macos-arm64" -> macosArm64()
        "windows-x64" -> mingwX64()
        else -> error("Unsupported livephoto.nativeTarget: $nativeTarget")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Packaged CLI conformance consumes synthetic fixtures produced by jvmTest.
// Track them as test outputs so a build-cache hit restores them alongside reports.
if (providers.gradleProperty("livephoto.jvm").orNull != "false") {
    tasks.named<Test>("jvmTest") {
        outputs.dir(layout.buildDirectory.dir("portable-heic-fixtures"))
        outputs.dir(layout.buildDirectory.dir("portable-windows-fixtures"))
        outputs.dir(layout.buildDirectory.dir("portable-apple-heif-fixtures"))
        outputs.dir(layout.buildDirectory.dir("reports/huawei-heic-fixtures"))
        outputs.dir(layout.buildDirectory.dir("reports/samsung-remux-fixtures"))
        outputs.dir(layout.buildDirectory.dir("reports/vivo-remux-fixtures"))
        outputs.dir(layout.buildDirectory.dir("reports/huawei-remux-fixtures"))
    }
}
