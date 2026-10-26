plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}
kotlin {
    jvmToolchain(25)
    jvm("desktop")
    sourceSets {
        named("desktopMain") { dependencies {
            implementation(project(":ui"))
            implementation(libs.compose.desktop.runtime)
            implementation(libs.compose.shared.material3)
            implementation(libs.kotlinx.coroutines)
        } }
        named("desktopTest") { dependencies {
            implementation(libs.kotlin.test.junit)
            implementation(libs.compose.desktop.test)
        } }
    }
}
val architecture = System.getProperty("os.arch")
val javafxPlatform = when {
    System.getProperty("os.name").startsWith("Windows") && architecture in setOf("amd64", "x86_64") -> "win"
    System.getProperty("os.name").startsWith("Mac") && architecture in setOf("aarch64", "arm64") -> "mac-aarch64"
    System.getProperty("os.name").startsWith("Linux") && architecture in setOf("amd64", "x86_64") -> "linux"
    else -> error("Unsupported desktop host architecture")
}
dependencies {
    listOf(libs.javafx.base, libs.javafx.graphics, libs.javafx.media, libs.javafx.swing).forEach { library ->
        val coordinate = library.get()
        add("desktopMainImplementation", "${coordinate.module.group}:${coordinate.module.name}:${coordinate.versionConstraint.requiredVersion}:$javafxPlatform") { isTransitive = false }
    }
}
compose.desktop {
    application {
        from(kotlin.targets["desktop"])
        mainClass = "livephoto.desktop.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        nativeDistributions {
            packageName = "LivePhoto"
            packageVersion = "0.1.0"
            modules("java.desktop", "java.xml", "java.logging", "java.management", "java.naming", "java.net.http", "jdk.unsupported", "jdk.unsupported.desktop", "jdk.crypto.ec")
        }
    }
}
