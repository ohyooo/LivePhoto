@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        project.extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download = false
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
        project.extensions.getByType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().download = false
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
        project.extensions.getByType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>()
            .downloadBaseUrl.set(null as String?)
    }
}
gradle.projectsEvaluated {
    val paths = allprojects.map { it.path }.sorted()
    val targets = subprojects.flatMap { module ->
        module.extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
            .targets.map { "${module.path}:${it.platformType.name}" }
    }
    tasks.register("verifyWebBuild") {
        group = "verification"
        doLast {
            check(paths == listOf(":", ":shared", ":ui")) { "Unexpected Web UI projects: $paths" }
            check(targets.count { it.endsWith(":js") } == 1 && targets.count { it.endsWith(":wasm") } == 2 &&
                targets.none { it.endsWith(":native") || it.endsWith(":jvm") || it.endsWith(":androidJvm") }) {
                "Unexpected Web UI targets: $targets"
            }
            println("Shared Compose Web UI projects: $paths")
        }
    }
}
