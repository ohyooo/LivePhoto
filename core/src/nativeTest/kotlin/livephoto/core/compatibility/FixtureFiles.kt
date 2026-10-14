@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.core.compatibility

import kotlinx.cinterop.*
import platform.posix.*

/** Fixed ASCII relative CI fixture paths only; not a product path adapter.
 * Gzip is expanded by CI before tests, never by the application. */
private fun readFixture(path: String): ByteArray? {
    require(path.all { it.code in 1..127 })
    val file = fopen(path, "rb") ?: return null
    try {
        check(fseek(file, 0, SEEK_END) == 0)
        val length = ftell(file).toLong()
        check(length in 0..32_000_000)
        check(fseek(file, 0, SEEK_SET) == 0)
        val bytes = ByteArray(length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = fread(pinned.addressOf(offset), 1.convert(), (bytes.size - offset).convert(), file).toLong()
                check(count in 1..(bytes.size - offset).toLong())
                offset += count.toInt()
            }
        }
        check(fgetc(file) == EOF && ferror(file) == 0)
        return bytes
    } finally { check(fclose(file) == 0) }
}
internal actual fun protocolFixtureData(name: String): ByteArray {
    require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "..")
    return listOf("build/protocol-fixtures/v1/$name", "core/build/protocol-fixtures/v1/$name")
        .firstNotNullOfOrNull(::readFixture) ?: error("Required expanded protocol fixture missing")
}
internal actual fun encodedFixtureData(name: String): ByteArray {
    encodedFixtureResource(name) // Fixed whitelist, no arbitrary path interpretation.
    return listOf("build/encoded-media-fixtures/$name.bin", "core/build/encoded-media-fixtures/$name.bin")
        .firstNotNullOfOrNull(::readFixture) ?: error("Required expanded encoded fixture missing")
}
internal actual fun referenceFixtureData(name: String): ByteArray? {
    require(name.isNotBlank() && name.split('/').none { it == ".." } && '\\' !in name)
    return listOf("reference/$name", "../reference/$name").firstNotNullOfOrNull(::readFixture)
}
internal actual fun exportFixtureEvidence(name: String, bytes: ByteArray) {
    require(name in setOf("verified-captures.tsv", "asset-manifest.json"))
    writeFixture("reports/protocol-compatibility/$name", bytes)
}
internal actual fun exportSyntheticFixture(group: String, name: String, bytes: ByteArray) {
    writeFixture("${syntheticFixtureDirectory(group, name)}/$name", bytes)
}
private fun writeFixture(relative: String, bytes: ByteArray) {
    val file = listOf("build/$relative", "core/build/$relative")
        .firstNotNullOfOrNull { fopen(it, "wb") } ?: error("Prepared fixture evidence directory missing")
    try {
        if (bytes.isNotEmpty()) bytes.usePinned { pinned ->
            check(fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toLong() == bytes.size.toLong())
        }
        check(fflush(file) == 0)
    } finally { check(fclose(file) == 0) }
}
