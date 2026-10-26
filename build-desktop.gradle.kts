plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
gradle.projectsEvaluated {
    val paths = allprojects.map { it.path }.sorted()
    val targets = subprojects.flatMap { module ->
        module.extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
            .targets.map { "${module.path}:${it.platformType.name}" }
    }
    tasks.register("verifyDesktopBuild") {
        group = "verification"
        doLast {
            check(paths == listOf(":", ":desktop", ":shared", ":ui")) { "Unexpected desktop projects: $paths" }
            check(targets.count { it.endsWith(":jvm") } == 3 && targets.all { it.endsWith(":jvm") || it.endsWith(":common") }) {
                "Unexpected Compose Desktop targets: $targets"
            }
            println("Compose Desktop projects: $paths")
        }
    }
}
