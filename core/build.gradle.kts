plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Packaged CLI conformance consumes synthetic fixtures produced by jvmTest.
// Track them as test outputs so a build-cache hit restores them alongside reports.
tasks.named<Test>("jvmTest") {
    outputs.dir(layout.buildDirectory.dir("portable-heic-fixtures"))
    outputs.dir(layout.buildDirectory.dir("portable-windows-fixtures"))
}
