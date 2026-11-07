plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}

// The same commonMain sources; no duplicate protocol implementation or JNI API.
kotlin {
    explicitApi()
    jvmToolchain(25)
    android {
        namespace = "livephoto.shared"
        compileSdk {
            version = release(libs.versions.compile.sdk.get().toInt()) {
                minorApiLevel = libs.versions.compile.minor.get().toInt()
            }
        }
        minSdk = libs.versions.min.sdk.get().toInt()
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // Desktop FFmpeg test hosts are deliberately not wired into Android.
    // Android integration is exercised by :android unit/instrumentation tests.
}
