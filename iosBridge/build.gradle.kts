import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    explicitApi()
    val xcframework = XCFramework("LivePhotoCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "LivePhotoCore"
            isStatic = true
            binaryOption("bundleId", "com.ohyooo.livephoto.core")
            // Keep the Objective-C / Swift surface limited to iosBridge.
            // Core is linked as an implementation detail, not exported.
            xcframework.add(this)
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.kotlinx.coroutines)
        }
        iosTest.dependencies { implementation(libs.kotlin.test) }
    }
}
