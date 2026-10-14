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
require(providers.gradleProperty("livephoto.jvm").orNull in setOf(null, "false")) {
    "JVM runtime targets have been removed; select a Native host instead"
}
require(providers.gradleProperty("livephoto.nativeTarget").orNull in
    setOf("linux-x64", "macos-arm64", "windows-x64")) {
    "Use -Plivephoto.nativeTarget=linux-x64, macos-arm64 or windows-x64 for your host"
}
include(":core")
include(":cli-shared")
include(":file-io")
include(":cli-native")
include(":process")
