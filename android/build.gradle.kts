import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "livephoto.android"
    compileSdk {
        version = release(libs.versions.compile.sdk.get().toInt()) {
            minorApiLevel = libs.versions.compile.minor.get().toInt()
        }
    }
    defaultConfig {
        applicationId = "com.ohyooo.livephoto"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
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

// AGP can return only a report path on a connected-test failure. Keep evidence
// visible even when a CI driver stops after the failed Gradle invocation.
abstract class ExplainConnectedFailures : DefaultTask() {
    // The instrumentation task creates this directory, and startup failure may leave no report.
    @get:Internal
    abstract val resultsDirectory: DirectoryProperty

    @TaskAction
    fun reportFailures() {
        val reports = resultsDirectory.get().asFile.walkTopDown()
            .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }
            .sortedBy { it.path }.toList()
        if (reports.isEmpty()) {
            logger.lifecycle("No connected Android test XML found; check emulator and test runner startup.")
            return
        }
        val builder = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        var total = 0
        var failed = 0
        for (report in reports) {
            val parsed = try { builder.parse(report) } catch (error: Exception) {
                logger.warn("Could not read a connected Android test report (${error.javaClass.simpleName}).")
                continue
            }
            val cases = parsed.getElementsByTagName("testcase")
            total += cases.length
            for (index in 0 until cases.length) {
                val case = cases.item(index) as org.w3c.dom.Element
                val failure = case.getElementsByTagName("failure").item(0)
                    ?: case.getElementsByTagName("error").item(0) ?: continue
                failed++
                if (failed > 12) continue
                logger.lifecycle("Android test failed: ${case.getAttribute("classname")}.${case.getAttribute("name")}")
                failure.textContent.lineSequence().take(18).forEach { line ->
                    logger.lifecycle("  ${line.take(360)}")
                }
            }
        }
        logger.lifecycle("Connected Android results: $total test cases, $failed failures/errors (${reports.size} XML reports).")
    }
}
val explainConnectedFailures = tasks.register<ExplainConnectedFailures>("explainConnectedFailures") {
    group = "verification"
    resultsDirectory.set(layout.buildDirectory.dir("outputs/androidTest-results/connected"))
}
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    finalizedBy(explainConnectedFailures)
}
