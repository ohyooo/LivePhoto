plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp) apply false
}

// Android uses ART bytecode, not the removed desktop JVM distribution or JNI.
gradle.projectsEvaluated {
    val projectPaths = allprojects.map { it.path }.sorted()
    val targets = project(":core").extensions
        .getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
        .targets.map { "${it.name}:${it.platformType.name}" }.sorted()
    val appEnabled = project(":android").plugins.hasPlugin("com.android.application")
    tasks.register("verifyAndroidBuild") {
        group = "verification"
        doLast {
            check(projectPaths == listOf(":", ":android", ":core")) { "Unexpected Android module graph: $projectPaths" }
            check(appEnabled) { "Android must be an application directly consuming :core" }
            check(targets.count { it.endsWith(":androidJvm") } == 1 &&
                targets.none { it.endsWith(":native") || it.endsWith(":jvm") }) {
                "Unexpected Core Android targets: $targets"
            }
            println("Android projects: $projectPaths")
            println("Core Android targets: $targets")
        }
    }
}
