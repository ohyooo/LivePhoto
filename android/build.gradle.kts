plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "livephoto.android"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "com.ohyooo.livephoto"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.environmentVariable("LIVEPHOTO_VERSION_CODE").map(String::toInt).orElse(1).get()
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Test inputs belong only to the test APK, never to the application.
    sourceSets.named("androidTest") { assets.directories.add("build/test-assets") }
    buildFeatures { compose = true }
    val suppliedKey = providers.environmentVariable("LIVEPHOTO_SIGNING_KEYSTORE").orNull
    if (suppliedKey != null) {
        signingConfigs.getByName("debug") {
            storeFile = file(suppliedKey)
            storePassword = providers.environmentVariable("LIVEPHOTO_SIGNING_STORE_PASSWORD").get()
            keyPassword = providers.environmentVariable("LIVEPHOTO_SIGNING_KEY_PASSWORD").get()
            keyAlias = providers.environmentVariable("LIVEPHOTO_SIGNING_KEY_ALIAS").get()
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    buildTypes.named("release") {
        signingConfig = signingConfigs.getByName("debug")
        isDebuggable = false
        // AGP 9.3+: code optimization/obfuscation and resource shrinking,
        // including the platform's default optimized keep rules.
        optimization { enable = true }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.android.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
