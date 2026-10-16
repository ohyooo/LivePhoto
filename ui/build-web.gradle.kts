plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    js {
        outputModuleName = "livephoto-ui"
        browser { commonWebpackConfig { outputFileName = "livephoto-ui.js" } }
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.shared.runtime)
            implementation(libs.compose.shared.foundation)
            implementation(libs.compose.shared.ui)
            implementation(libs.compose.shared.material3)
            implementation(libs.kotlinx.coroutines)
        }
    }
}
