plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    if (providers.gradleProperty("livephoto.jvm").orNull != "false") {
        jvm()
        jvmToolchain(25)
    }
    val nativeTarget = providers.gradleProperty("livephoto.nativeTarget").orNull
    when (val target = nativeTarget) {
        null -> Unit
        "linux-x64" -> linuxX64()
        "macos-arm64" -> macosArm64()
        "windows-x64" -> mingwX64().apply {
            compilations.getByName("main").cinterops.create("winio") {
                definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/winio.def"))
                includeDirs(rootProject.file("native-interop/windows"))
            }
        }
        else -> error("Unsupported livephoto.nativeTarget: $target")
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.okio)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        if (nativeTarget != null) {
            nativeMain.dependencies {
                implementation(project(":process"))
                implementation(libs.kotlinx.coroutines)
            }
        }
    }
}
