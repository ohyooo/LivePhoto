plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

gradle.projectsEvaluated {
    val modules = allprojects.map { it.path }.sorted()
    val targets = subprojects.flatMap { module ->
        module.extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
            .targets.filter { it.name != "metadata" }
            .map { "${module.path}:${it.name}:${it.platformType.name}" }
    }.sorted()
    tasks.register("verifyIosBuild") {
        group = "verification"
        description = "Verify that the iOS build is isolated from desktop Native, Android, and Web."
        doLast {
            check(modules == listOf(":", ":iosBridge", ":shared")) { "Unexpected iOS modules: $modules" }
            val expected = listOf(":iosBridge", ":shared").flatMap { module ->
                listOf("iosArm64", "iosSimulatorArm64").map { "$module:$it:native" }
            }.sorted()
            check(targets == expected) { "Unexpected iOS targets: $targets" }
            println("iOS-only targets: $targets")
        }
    }
}
