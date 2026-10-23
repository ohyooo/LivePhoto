@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    wasmJs {
        outputModuleName = "livephoto-worker"
        browser { commonWebpackConfig { outputFileName = "livephoto-worker.js" } }
        binaries.executable()
    }
    sourceSets {
        commonMain {
            // Compile the existing application helpers; no Compose runtime or second protocol implementation.
            kotlin.srcDir("../src/commonMain/kotlin")
            kotlin.include("**/PhotoAnalysis.kt", "**/VideoExtraction.kt", "**/CleanSplit.kt", "**/PhotoConversion.kt",
                "**/PhotoKey.kt", "**/PhotoRepair.kt", "**/Presentation.kt", "**/CoreMediaEditing.kt")
            dependencies { implementation(project(":shared")); implementation(libs.kotlinx.coroutines) }
        }
        wasmJsMain.dependencies { implementation(libs.kotlinx.browser) }
    }
}
