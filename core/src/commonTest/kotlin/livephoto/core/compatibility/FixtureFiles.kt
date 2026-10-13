package livephoto.core.compatibility

/** Test-only IO. No filesystem or decompression dependency enters the Core library. */
internal expect fun protocolFixtureData(name: String): ByteArray
internal expect fun referenceFixtureData(name: String): ByteArray?
internal expect fun exportFixtureEvidence(name: String, bytes: ByteArray)
internal expect fun exportSyntheticFixture(group: String, name: String, bytes: ByteArray)

internal fun syntheticFixtureDirectory(group: String, name: String): String = when (group) {
    "google-heic" -> {
        require(name in setOf("primary.heic", "motion.mp4", "motion.heic", "damaged.heic", "manifest.txt"))
        "portable-heic-fixtures"
    }
    "huawei-heic" -> {
        require(name in setOf("carrier.heic", "movie.mp4", "manifest.txt"))
        "reports/huawei-heic-fixtures"
    }
    else -> error("Unknown synthetic fixture group")
}
internal fun fixtureRunId(): String = kotlin.random.Random.nextBytes(16).joinToString("") {
    val value = it.toInt() and 255
    "0123456789abcdef"[value shr 4].toString() + "0123456789abcdef"[value and 15]
}
