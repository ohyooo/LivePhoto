plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(25)
    android {
        namespace = "livephoto.ui"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 29
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation(libs.compose.shared.runtime)
            implementation(libs.compose.shared.foundation)
            implementation(libs.compose.shared.ui)
            implementation(libs.compose.shared.material3)
            implementation(libs.kotlinx.coroutines)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
