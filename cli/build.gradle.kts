plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(25) }
dependencies {
    implementation(project(":core"))
    testImplementation(libs.kotlin.test)
}
application { mainClass.set("livephoto.cli.MainKt") }
tasks.jar { archiveFileName.set("livephoto-cli.jar") }
