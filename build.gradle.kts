plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Capture the graph during configuration; the action retains immutable values
// only, without accessing Project at execution time (configuration cache).
gradle.projectsEvaluated {
    val jvmDisabled = providers.gradleProperty("livephoto.jvm").orNull == "false"
    val projectPaths = allprojects.map { it.path }.sorted()
    val platformTargets = subprojects.flatMap { module ->
        module.extensions.findByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
            ?.targets?.map { "${module.path}:${it.name}:${it.platformType.name}" }.orEmpty()
    }.sorted()
    tasks.register("verifyNativeOnlyBuild") {
        group = "verification"
        description = "Reject JVM modules and targets in a Native-only build."
        doLast {
            check(jvmDisabled) { "Use -Plivephoto.jvm=false for Native-only verification" }
            check(":cli" !in projectPaths) { "JVM CLI module is still included" }
            check(platformTargets.none { it.endsWith(":jvm") || it.endsWith(":androidJvm") }) {
                "JVM targets remain: $platformTargets"
            }
            check(platformTargets.count { it.endsWith(":native") } == 4) {
                "Expected four host Native targets: $platformTargets"
            }
            println("Native-only projects: $projectPaths")
            println("Native-only targets: $platformTargets")
        }
    }
}
