plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    explicitApi()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()

    sourceSets {
        // In this mode nativeMain/nativeTest are only hierarchy nodes. Their
        // default directories implement desktop FFmpeg/process adapters.
        named("nativeMain") { kotlin.setSrcDirs(emptyList<String>()) }
        named("nativeTest") { kotlin.setSrcDirs(emptyList<String>()) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
