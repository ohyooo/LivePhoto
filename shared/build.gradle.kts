plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    explicitApi()
    val host = when (val target = providers.gradleProperty("livephoto.nativeTarget").orNull) {
        "linux-x64" -> linuxX64()
        "macos-arm64" -> macosArm64()
        "windows-x64" -> mingwX64()
        else -> error("Unsupported livephoto.nativeTarget: $target")
    }
    applyDefaultHierarchyTemplate()
    if (host.name != "mingwX64") {
        sourceSets.getByName("${host.name}Main").kotlin.srcDir("src/posixMain/kotlin")
        host.compilations.getByName("main").cinterops.create("posix") {
            definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/posix.def"))
            includeDirs("src/nativeInterop/cinterop")
        }
    } else {
        host.compilations.getByName("main").cinterops.create("winio") {
            definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/winio.def"))
            includeDirs(rootProject.file("native-interop/windows"))
        }
    }
    sourceSets {
        commonTest.dependencies { implementation(libs.kotlin.test) }
        val desktopTest by creating { dependsOn(commonTest.get()) }
        val hostMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(libs.okio)
                implementation(libs.kotlinx.coroutines)
            }
        }
        nativeMain { dependsOn(hostMain) }
        nativeTest {
            dependsOn(desktopTest)
            dependencies { implementation(libs.kotlinx.coroutines) }
        }
    }
}
