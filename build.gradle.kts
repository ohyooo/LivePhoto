plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

// Web builds reuse the host's existing Node.js instead of adding a download
// repository, keeping FAIL_ON_PROJECT_REPOS intact for all targets.
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        project.extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download = false
    }
}

// Capture the graph during configuration; the action retains immutable values
// only, without accessing Project at execution time (configuration cache).
gradle.projectsEvaluated {
    val projectPaths = allprojects.map { it.path }.sorted()
    val platformTargets = subprojects.flatMap { module ->
        module.extensions.findByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
            ?.targets?.map { "${module.path}:${it.name}:${it.platformType.name}" }.orEmpty()
    }.sorted()
    tasks.register("verifyNativeOnlyBuild") {
        group = "verification"
        description = "Reject JVM modules and targets in a Native-only build."
        doLast {
            check(projectPaths == listOf(":", ":cli", ":shared")) {
                "Unexpected module graph: $projectPaths"
            }
            check(platformTargets.none { it.endsWith(":jvm") || it.endsWith(":androidJvm") }) {
                "JVM targets remain: $platformTargets"
            }
            check(platformTargets.count { it.endsWith(":native") } == 2) {
                "Expected two host Native targets: $platformTargets"
            }
            println("Native-only projects: $projectPaths")
            println("Native-only targets: $platformTargets")
        }
    }
    tasks.register("verifyWebBuild") {
        group = "verification"
        description = "Verify the headless JS library graph has no UI or Native adapter."
        doLast {
            check(projectPaths == listOf(":", ":shared")) { "Unexpected Web projects: $projectPaths" }
            check(platformTargets.count { it.endsWith(":js") } == 1 && platformTargets.count { it.endsWith(":wasm") } == 1 &&
                platformTargets.none { it.endsWith(":native") || it.endsWith(":jvm") || it.endsWith(":androidJvm") }) {
                "Unexpected Web targets: $platformTargets"
            }
            println("Web library projects: $projectPaths")
            println("Web library targets: $platformTargets")
        }
    }
}
