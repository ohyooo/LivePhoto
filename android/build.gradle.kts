plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "livephoto.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.ohyooo.livephoto"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Test inputs belong only to the test APK, never to the application.
    sourceSets.named("androidTest") { assets.srcDir(layout.buildDirectory.dir("test-assets")) }
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
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.android.activity)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
}
