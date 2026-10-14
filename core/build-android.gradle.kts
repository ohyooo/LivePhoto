plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}

// The same commonMain sources; no duplicate protocol implementation or JNI API.
kotlin {
    explicitApi()
    jvmToolchain(25)
    android {
        namespace = "livephoto.core"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // Desktop FFmpeg test hosts are deliberately not wired into Android.
    // Android integration is exercised by :android unit/instrumentation tests.
}
