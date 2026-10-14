package livephoto.core.compatibility

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.Base64

private const val MAX_FIXTURE_BYTES = 32_000_000
internal actual fun encodedFixtureData(name: String): ByteArray {
    val resource = "/${encodedFixtureResource(name)}"
    val stream = requireNotNull(object {}.javaClass.getResourceAsStream(resource)) { "Required encoded fixture missing" }
    return stream.use {
        val encoded = it.readNBytes(40_001)
        require(encoded.size <= 40_000)
        Base64.getDecoder().decode(encoded.toString(Charsets.US_ASCII).trim())
    }
}
internal actual fun protocolFixtureData(name: String): ByteArray {
    require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "..")
    val resource = "/protocol-compatibility/v1/$name"
    val type = object {}.javaClass
    val direct = type.getResourceAsStream(resource)
    val compressed = direct == null && name.endsWith(".fixture")
    val stream = direct ?: if (compressed) type.getResourceAsStream("$resource.gz") else null
    return requireNotNull(stream) { "Required fixture missing" }.use {
        (if (compressed) GZIPInputStream(it) else it).use { input ->
            input.readNBytes(MAX_FIXTURE_BYTES + 1).also { bytes -> require(bytes.size <= MAX_FIXTURE_BYTES) }
        }
    }
}
internal actual fun referenceFixtureData(name: String): ByteArray? {
    require(name.isNotBlank() && name.split('/').none { it == ".." } && '\\' !in name)
    val path = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .map { it.resolve("reference/$name") }.firstOrNull(Files::isRegularFile) ?: return null
    require(Files.size(path) <= MAX_FIXTURE_BYTES)
    return Files.readAllBytes(path)
}
internal actual fun exportFixtureEvidence(name: String, bytes: ByteArray) {
    require(name in setOf("verified-captures.tsv", "asset-manifest.json"))
    val directory = Path.of("build", "reports", "protocol-compatibility")
    Files.createDirectories(directory)
    Files.write(directory.resolve(name), bytes)
}
internal actual fun exportSyntheticFixture(group: String, name: String, bytes: ByteArray) {
    val directory = Path.of("build", syntheticFixtureDirectory(group, name))
    Files.createDirectories(directory)
    Files.write(directory.resolve(name), bytes)
}
