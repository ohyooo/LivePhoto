plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// Android uses ART bytecode, not the removed desktop JVM distribution or JNI.
gradle.projectsEvaluated {
    val projectPaths = allprojects.map { it.path }.sorted()
    val targets = project(":shared").extensions
        .getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
        .targets.map { "${it.name}:${it.platformType.name}" }.sorted()
    val appEnabled = project(":android").plugins.hasPlugin("com.android.application")
    val uiTargets = project(":ui").extensions
        .getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
        .targets.map { "${it.name}:${it.platformType.name}" }.sorted()
    tasks.register("verifyAndroidBuild") {
        group = "verification"
        doLast {
            check(projectPaths == listOf(":", ":android", ":shared", ":ui")) { "Unexpected Android module graph: $projectPaths" }
            check(appEnabled) { "Android must consume shared UI and Core" }
            check(targets.count { it.endsWith(":androidJvm") } == 1 &&
                targets.none { it.endsWith(":native") || it.endsWith(":jvm") }) {
                "Unexpected Core Android targets: $targets"
            }
            check(uiTargets.count { it.endsWith(":androidJvm") } == 1 &&
                uiTargets.none { it.endsWith(":native") || it.endsWith(":jvm") }) {
                "Unexpected shared UI Android targets: $uiTargets"
            }
            println("Android projects: $projectPaths")
            println("Core Android targets: $targets")
            println("Shared UI Android targets: $uiTargets")
        }
    }
}
