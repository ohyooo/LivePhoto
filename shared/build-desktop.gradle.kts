plugins { alias(libs.plugins.kotlin.multiplatform) }
kotlin {
    explicitApi()
    jvmToolchain(25)
    jvm("desktopJvm")
    sourceSets {
        val hostMain by creating {
            dependsOn(commonMain.get())
            dependencies { api(libs.okio); implementation(libs.kotlinx.coroutines) }
        }
        named("desktopJvmMain") { dependsOn(hostMain) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        val desktopTest by creating { dependsOn(commonTest.get()) }
        named("desktopJvmTest") {
            dependsOn(desktopTest)
            dependencies { implementation(libs.kotlin.test.junit); implementation(libs.kotlinx.coroutines) }
        }
    }
}
