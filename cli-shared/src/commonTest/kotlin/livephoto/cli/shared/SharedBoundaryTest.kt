package livephoto.cli.shared

import livephoto.core.*
import kotlin.test.*

class SharedBoundaryTest {
    @Test fun identitySnapshotAndReceiptNeverExposePrivateTokens() {
        val identity = SourceIdentity(SourceId("private-path"), GenerationToken("private-generation"), 17uL, Digest("abc"))
        val snapshot = Snapshot(listOf(identity), GenerationToken("private-snapshot"))
        val receipt = Receipt("private-receipt", TransactionState.Committed, listOf(AssetId("a")), Atomicity.AssetSetRequired, "volatile")
        val json = CliJson.encode(listOf(identity, snapshot, receipt))
        assertFalse(json.contains("private-"))
        assertTrue(json.contains("\"size\":\"17\""))
        assertTrue(json.contains("\"sources\":"))
        assertTrue(json.contains("\"assetIds\":"))
    }

    @Test fun binarySourcesAndBytesAreRedactedWithoutReading() {
        val platform = TestPlatform()
        val source = platform.openSource("private-file")
        assertEquals("\"redacted\"", CliJson.encode(source))
        assertEquals("{\"byteLength\":3}", CliJson.encode(Bytes(byteArrayOf(1, 2, 3))))
    }

    @Test fun outputPathsAreResolvedOnlyThroughTheCliAdapter() {
        val asset = OutputAsset(AssetId("a"), AssetRole.PrimaryImage, accessReference = "private-reference",
            byteLength = 7uL, mime = "image/jpeg", imageFormat = ImageFormat.Jpeg, digest = Digest("abc"))
        var resolved: AssetId? = null
        val json = CliJson.encode(asset) { resolved = it; "output/a.jpg" }
        assertEquals(asset.id, resolved)
        assertTrue(json.contains("\"path\":\"output/a.jpg\""))
        assertFalse(json.contains("private-reference"))
    }

    @Test fun jsonSupportsNestedValuesControlsAndSurrogatesWithoutReflection() {
        val json = CliJson.encode(Value.ObjectValue(mapOf("a" to Value.ArrayValue(listOf(
            Value.Text("\u0000\uD83D\uDE00"), Value.Number("12.50"), Value.BooleanValue(true), Value.Null)))))
        assertEquals("{\"a\":[\"\\u0000\\ud83d\\ude00\",12.50,true,null]}", json)
        assertFailsWith<IllegalStateException> { CliJson.encode(object {}) }
    }

    @Test fun failedWritesAbortTransactionsAndCloseEveryBorrowedSource(): Unit = synchronous {
        val platform = TestPlatform()
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun create(request: CreateRequest): CoreResult<OperationResult> =
                CoreResult.Failure(CoreError(IssueCode("SENTINEL"), Stage.Plan, "test"))
        }
        assertEquals(3, CliEngine(platform, core).run(listOf("create", "--image", "image", "--video", "video",
            "--target", "google.motionphoto.v2", "--output-dir", "output")) {})
        assertEquals(2, platform.opened.size)
        platform.opened.forEach { assertIs<CoreResult.Failure>(it.size()) }
        assertEquals(TransactionState.Aborted, assertIs<CoreResult.Success<Receipt>>(platform.transactions.single().query()).value.state)
    }

    @Test fun environmentLogLevelAndExplicitOverrideUseOnlyInjectedPlatform(): Unit = synchronous {
        val platform = TestPlatform().also { it.environmentLevel = "trace" }
        val logs = mutableListOf<String>()
        val engine = CliEngine(platform, diagnostic = logs::add)
        assertEquals(0, engine.run(listOf("capabilities", "--target", "google.motionphoto.v2")) {})
        assertTrue(logs.any { it.startsWith("DEBUG ") })
        assertTrue(logs.any { it.contains("elapsed-ms=0") })
        logs.clear()
        assertEquals(0, engine.run(listOf("capabilities", "--target", "google.motionphoto.v2", "--log-level", "off")) {})
        assertTrue(logs.isEmpty())
    }
}
