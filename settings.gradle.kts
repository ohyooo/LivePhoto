pluginManagement {
    repositories {
        if (providers.gradleProperty("livephoto.android").orNull == "true" || providers.gradleProperty("livephoto.webUi").orNull == "true") google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("livephoto.android").orNull == "true" || providers.gradleProperty("livephoto.webUi").orNull == "true") google()
        mavenCentral()
        if (providers.gradleProperty("livephoto.web").orNull == "true") {
            // Kotlin's pinned Binaryen optimizer, resolved centrally without
            // allowing plugins to inject transient project repositories.
            ivy {
                name = "KotlinWasmBinaryen"
                url = uri("https://github.com/WebAssembly/binaryen/releases/download")
                patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
                metadataSources { artifact() }
                content { includeModule("com.github.webassembly", "binaryen") }
            }
        }
    }
}

rootProject.name = "LivePhoto"
require(providers.gradleProperty("livephoto.jvm").orNull in setOf(null, "false")) {
    "JVM runtime targets have been removed; select a Native host instead"
}
val androidEnabled = when (val selected = providers.gradleProperty("livephoto.android").orNull) {
    null, "false" -> false
    "true" -> true
    else -> error("livephoto.android must be true or false, not $selected")
}
val webEnabled = when (val selected = providers.gradleProperty("livephoto.web").orNull) {
    null, "false" -> false
    "true" -> true
    else -> error("livephoto.web must be true or false, not $selected")
}
val webUiEnabled = when (val selected = providers.gradleProperty("livephoto.webUi").orNull) {
    null, "false" -> false
    "true" -> true
    else -> error("livephoto.webUi must be true or false, not $selected")
}
require(!webUiEnabled || webEnabled) { "Web UI requires livephoto.web=true" }
if (webEnabled) {
    require(!androidEnabled && !providers.gradleProperty("livephoto.nativeTarget").isPresent) {
        "Build Web, Android and desktop Native separately"
    }
    include(":shared")
    project(":shared").buildFileName = "build-web.gradle.kts"
    if (webUiEnabled) {
        rootProject.buildFileName = "build-web-ui.gradle.kts"
        include(":ui", ":ui:worker")
        project(":ui").buildFileName = "build-web.gradle.kts"
    }
} else if (androidEnabled) {
    require(!providers.gradleProperty("livephoto.nativeTarget").isPresent) {
        "Build Android and desktop Native separately; omit livephoto.nativeTarget for Android"
    }
    rootProject.buildFileName = "build-android.gradle.kts"
    include(":shared", ":ui", ":android")
    project(":shared").buildFileName = "build-android.gradle.kts"
} else {
    require(providers.gradleProperty("livephoto.nativeTarget").orNull in
        setOf("linux-x64", "macos-arm64", "windows-x64")) {
        "Select -Plivephoto.nativeTarget=<host> or -Plivephoto.android=true"
    }
    include(":shared", ":cli")
}
