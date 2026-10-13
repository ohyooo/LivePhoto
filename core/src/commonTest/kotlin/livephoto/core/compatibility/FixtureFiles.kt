package livephoto.core.compatibility

/** Test-only IO. No filesystem or decompression dependency enters the Core library. */
internal expect fun protocolFixtureData(name: String): ByteArray
internal expect fun referenceFixtureData(name: String): ByteArray?
internal expect fun exportFixtureEvidence(name: String, bytes: ByteArray)
