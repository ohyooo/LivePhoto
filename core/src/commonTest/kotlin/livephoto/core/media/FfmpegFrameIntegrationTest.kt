package livephoto.core.media

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.selectFrame
import livephoto.core.memory.MemoryOutputTransaction
import kotlin.test.*

/** Real decoder tests on explicit synthetic fixtures; not device compatibility certification. */
class FfmpegFrameIntegrationTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    private suspend fun fixture(host: MediaTestHost, path: String, colors: Boolean = true) {
        val process = host.execute(frameFixtureArguments(host.executable, path, colors))
        assertEquals(0, process.code, process.output); assertFalse(process.ioFailed)
    }
    @Test fun realVfrBFrameSelectionMatchesExactPtsAndNeverSelectsSyncInstead(): Unit = runMediaTest { host ->
        val file = host.path("B frames VFR.mp4")
        fixture(host, file)
        val input = host.source(file)
        try {
            val reader = BinaryReader(input, context); val range = ByteRange(0uL, reader.identity().orThrow().size)
            val before = sha256Range(reader, range).orThrow(); val video = BmffVideoProbe(reader).probe(range).orThrow()
            val track = video.tracks.single()
            assertTrue(track.samples.map { it.presentationTime } != track.samples.map { it.presentationTime }.sorted(), "Fixture must actually contain reordered B frames")
            assertTrue(track.samples.map { it.duration }.distinct().size > 1, "Fixture must actually be VFR")
            val selected = selectFrame(video, CoverPosition.FrameIndex(3uL)); assertFalse(selected.sample.isSync)
            val core = DefaultLivePhotoCore(host.backend)
            val byIndexRun = core.extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.FrameIndex(3uL), ImageEncoding(ImageFormat.Jpeg), MemoryOutputTransaction(context, "frame-index"), context))
            val byIndex = assertIs<CoreResult.Success<FrameResult>>(byIndexRun, byIndexRun.toString()).value
            try {
                assertEquals(selected.time, byIndex.actualTime); assertEquals(3uL, byIndex.actualFrameIndex)
                val byTimeRun = core.extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.Timestamp(selected.time, Selection.Exact), ImageEncoding(ImageFormat.Jpeg), MemoryOutputTransaction(context, "frame-time"), context))
                val byTime = assertIs<CoreResult.Success<FrameResult>>(byTimeRun, byTimeRun.toString()).value
                try { assertEquals(byIndex.operation.output.assets.single().digest, byTime.operation.output.assets.single().digest) }
                finally { byTime.operation.output.assets.forEach { it.readableSource?.close() } }
                val zeroRun = core.extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.FrameIndex(0uL), ImageEncoding(ImageFormat.Jpeg), MemoryOutputTransaction(context, "frame-zero"), context))
                val zero = assertIs<CoreResult.Success<FrameResult>>(zeroRun, zeroRun.toString()).value
                try { assertNotEquals(zero.operation.output.assets.single().digest, byIndex.operation.output.assets.single().digest) }
                finally { zero.operation.output.assets.forEach { it.readableSource?.close() } }
                assertTrue(byIndex.operation.execution.any { it.stage == Stage.EncodeImage }); assertTrue(byIndex.operation.execution.none { it.transcoded || it.remuxed })
                assertEquals(before, sha256Range(reader, range).orThrow())
            } finally { byIndex.operation.output.assets.forEach { it.readableSource?.close() } }
        } finally { input.close() }
    }
    @Test fun unknownColorCannotBeSilentlyAssumedSdr(): Unit = runMediaTest { host ->
        val file = host.path("unknown color.mp4")
        fixture(host, file, colors = false)
        val input = host.source(file); val output = MemoryOutputTransaction(context, "frame-unknown")
        try {
            val run = DefaultLivePhotoCore(host.backend).extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.FrameIndex(0uL), ImageEncoding(ImageFormat.Jpeg), output, context))
            assertEquals("HDR_PRESERVATION_UNAVAILABLE", assertIs<CoreResult.Failure>(run).error.code.value)
            assertTrue(output.committedAssets().isEmpty())
        } finally { input.close() }
    }
    @Test fun originalReferenceColorAndMetadataMustNotBeGuessed(): Unit = runMediaTest { host ->
        val input = host.source(host.reference("video.mp4")); val output = MemoryOutputTransaction(context, "frame-reference")
        try {
            val run = DefaultLivePhotoCore(host.backend).extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.FrameIndex(0uL), ImageEncoding(ImageFormat.Jpeg), output, context))
            val error = assertIs<CoreResult.Failure>(run).error.code.value
            assertTrue(error in setOf("UNSAFE_METADATA_REWRITE", "HDR_PRESERVATION_UNAVAILABLE", "CAPABILITY_UNSUPPORTED"), error)
            assertTrue(output.committedAssets().isEmpty())
        } finally { input.close() }
    }
}
