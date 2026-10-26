package livephoto.core.compatibility

import java.nio.file.Files
import java.nio.file.Path

private fun readFixture(paths: List<String>): ByteArray? = paths.map(Path::of).firstOrNull { Files.isRegularFile(it) }?.let {
    check(Files.size(it) in 0..32_000_000); Files.readAllBytes(it)
}
internal actual fun protocolFixtureData(name: String): ByteArray {
    require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "..")
    return readFixture(listOf("build/protocol-fixtures/v1/$name", "shared/build/protocol-fixtures/v1/$name")) ?: error("Required expanded protocol fixture missing")
}
internal actual fun encodedFixtureData(name: String): ByteArray {
    encodedFixtureResource(name)
    return readFixture(listOf("build/encoded-media-fixtures/$name.bin", "shared/build/encoded-media-fixtures/$name.bin")) ?: error("Required expanded encoded fixture missing")
}
internal actual fun referenceFixtureData(name: String): ByteArray? {
    require(name.isNotBlank() && name.split('/').none { it == ".." } && '\\' !in name)
    return readFixture(listOf("reference/$name", "../reference/$name"))
}
internal actual fun exportFixtureEvidence(name: String, bytes: ByteArray) {
    require(name in setOf("verified-captures.tsv", "asset-manifest.json"))
    Files.write(Path.of("build/reports/protocol-compatibility/$name"), bytes)
}
internal actual fun exportSyntheticFixture(group: String, name: String, bytes: ByteArray) {
    Files.write(Path.of("build/${syntheticFixtureDirectory(group, name)}/$name"), bytes)
}
