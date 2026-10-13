pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "LivePhoto"
val jvmEnabled = providers.gradleProperty("livephoto.jvm").orNull.let {
    when (it) {
        null, "true" -> true
        "false" -> false
        else -> error("livephoto.jvm must be true or false")
    }
}
if (!jvmEnabled) {
    require(providers.gradleProperty("livephoto.nativeTarget").orNull in
        setOf("linux-x64", "macos-arm64", "windows-x64")) {
        "Native-only builds require a supported livephoto.nativeTarget"
    }
}
include(":core")
if (jvmEnabled) include(":cli")
include(":cli-shared")
include(":file-io")
include(":cli-native")
