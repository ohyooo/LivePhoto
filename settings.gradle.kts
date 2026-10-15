pluginManagement {
    repositories {
        if (providers.gradleProperty("livephoto.android").orNull == "true") google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("livephoto.android").orNull == "true") google()
        mavenCentral()
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
if (androidEnabled) {
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
