package livephoto.core.media

import livephoto.core.*
import livephoto.core.ffmpeg.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.binary.BinaryReader
import livephoto.core.implementation.RangeSource
import kotlin.test.*

class MemoryFfmpegRuntimeTest {
    private val context = Context(Limits(1024u, 1024u))
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
    @Test fun browserMountRangesRequireTheCapturedSourceAndPreserveBorrowedOwnership(): Unit = runImmediate {
        val original = MemoryBinarySource(Bytes(byteArrayOf(1, 2, 3)), SourceId("browser-file"))
        val source = object : BinarySource by original {
            override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = error("Resolving a view must not read media bytes")
        }
        val view = RangeSource(BinaryReader(source, context), ByteRange(1u, 2u))
        val nested = RangeSource(BinaryReader(view, context), ByteRange(1u, 1u))
        assertEquals(ByteRange(0u, 3u), value(browserMediaInputRange(source, source)))
        assertEquals(ByteRange(2u, 1u), value(browserMediaInputRange(nested, source)))
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(browserMediaInputRange(original, source)).error.code.value)
        assertIs<CoreResult.Failure>(browserMediaInputRange(RangeSource(BinaryReader(source, context), ByteRange(2u, 2u)), source))
        nested.close(); view.close()
        assertIs<CoreResult.Success<SourceIdentity>>(source.identity())
        original.close()
    }
    @Test fun privateOutputPatchAndReadersNeverChangeTheInput(): Unit = runImmediate {
        val input = byteArrayOf(1, 2, 3)
        val runtime = MemoryFfmpegRuntime { args, bytes, _, name, timeout, _ ->
            assertEquals(listOf("-i", "input.bin", "output.bin"), args)
            assertEquals("output.bin", name); assertEquals(120_000L, timeout)
            assertContentEquals(input, value(bytes.readAt(0u, 3u)).toByteArray())
            MemoryFfmpegResult(FfmpegProcessResult(0, "ok"), Bytes(byteArrayOf(4, 5, 6)))
        }
        val files = value(runtime.openFiles(MemoryBinarySource(Bytes(input), SourceId("test")), context, "output.bin", true))
        assertEquals(0, runtime.execute(listOf("ffmpeg", "-i", files.input, files.output), 600_000, context).code)
        val frozen = value(files.openOutput())
        value(files.patchOutput(listOf(FfmpegPatch(ByteRange(1u, 1u), Bytes(byteArrayOf(9))))))
        assertContentEquals(byteArrayOf(4, 5, 6), value(frozen.readAt(0u, 3u)).toByteArray())
        val patched = value(files.openOutput())
        assertContentEquals(byteArrayOf(4, 9, 6), value(patched.readAt(0u, 3u)).toByteArray())
        assertIs<CoreResult.Failure>(files.patchOutput(listOf(FfmpegPatch(ByteRange(2u, 2u), Bytes(byteArrayOf(1, 1))))))
        assertContentEquals(byteArrayOf(1, 2, 3), input)
        frozen.close(); patched.close(); value(files.close())
    }
    @Test fun budgetAndConcurrentWorkspacesAreRejectedBeforeExecutor(): Unit = runImmediate {
        val runtime = MemoryFfmpegRuntime { _, _, _, _, _, _ -> error("Must not execute") }
        assertIs<CoreResult.Failure>(runtime.openFiles(MemoryBinarySource(Bytes(ByteArray(1025)), SourceId("large")), context, "output.bin", true))
        val oversized = object : BinarySource {
            private val identity = SourceIdentity(SourceId("over-memory-budget"), GenerationToken("fixed"), BROWSER_MEDIA_INPUT_LIMIT.toULong() + 1u)
            override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(identity)
            override suspend fun size(): CoreResult<ULong> = CoreResult.Success(identity.size)
            override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = error("Must reject before reading")
            override suspend fun close() {}
        }
        assertEquals("RESOURCE_LIMIT_EXCEEDED", assertIs<CoreResult.Failure>(runtime.openFiles(oversized,
            Context(Limits(512uL * 1024u * 1024u, 1024u)), "output.bin", true)).error.code.value)
        val source = MemoryBinarySource(Bytes(byteArrayOf(1)), SourceId("small"))
        val files = value(runtime.openFiles(source, context, "output.bin", true))
        assertIs<CoreResult.Failure>(runtime.openFiles(source, context, "output.bin", true))
        assertTrue(runtime.execute(listOf("ffmpeg", "-i", "https://example.invalid/input", "output.bin"), 1000, context).ioFailed)
        value(files.close()); value(value(runtime.openFiles(source, context, "output.bin", true)).close())
        val media = object : BinarySource by source {
            override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = error("Opening must borrow, not materialize, the input")
        }
        value(value(runtime.openFiles(media, context.copy(limits = context.limits.copy(maxMetadataBytes = 1u)), "output.bin", true)).close())
    }
    @Test fun cancellationAndOversizedOutputNeverExposeAFile(): Unit = runImmediate {
        var calls = 0
        val runtime = MemoryFfmpegRuntime { _, _, _, _, _, _ -> calls++; MemoryFfmpegResult(FfmpegProcessResult(0, ""), Bytes(ByteArray(1025))) }
        val files = value(runtime.openFiles(MemoryBinarySource(Bytes(byteArrayOf(1)), SourceId("source")), context, "output.bin", true))
        assertTrue(runtime.execute(listOf("ffmpeg"), 1000, context.copy(cancellation = Cancellation { true })).cancelled)
        assertEquals(0, calls)
        assertTrue(runtime.execute(listOf("ffmpeg"), 1000, context).outputLimited)
        assertIs<CoreResult.Failure>(files.openOutput()); value(files.close())
    }
    @Test fun browserCapabilitiesNameActualWasmExecution(): Unit = runImmediate {
        val backend = BrowserFfmpegBackend { _, _, _, _, _, _ -> error("No media work") }
        assertEquals(listOf("ffmpeg-wasm"), backend.capabilities().backendIds)
        assertEquals(setOf(Operation.Probe, Operation.Trim, Operation.Remux, Operation.Transcode, Operation.ExtractFrame), backend.capabilities().operations.map { it.operation }.toSet())
        assertTrue(backend.capabilities().operations.all { it.implementation == Implementation.Experimental })
        assertTrue(backend.capabilities().operations.filter { it.operation in setOf(Operation.Trim, Operation.Remux, Operation.Transcode) }
            .all { it.conditions.any { condition -> condition.key == "wasmAudioProfile" } })
        val source = MemoryBinarySource(Bytes(GoogleFixtures.video(aac = true, audioConfig = byteArrayOf(0x11, 0x88.toByte())).bytes), SourceId("mono"))
        val staging = object : StagingArea {
            override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = error("Unsupported mono must not stage")
            override suspend fun openForRead(id: AssetId): CoreResult<BinarySource> = error("Unsupported mono must not stage")
        }
        try {
            val result = backend.remux(BackendJob(Operation.Remux, listOf(ResourceRef(SourceSet.Single(source))), remuxContainer = VideoContainer.Mp4,
                context = Context(Limits(4_000_000u, 4_000_000u)), destination = staging))
            assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(result).error.code.value)
        } finally { source.close() }
    }
}
