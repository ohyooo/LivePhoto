plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}
kotlin {
    jvmToolchain(25)
    jvm("desktop")
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
