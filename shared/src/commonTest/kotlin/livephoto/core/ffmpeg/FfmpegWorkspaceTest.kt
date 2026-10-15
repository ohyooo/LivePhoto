package livephoto.core.ffmpeg

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class FfmpegWorkspaceTest {
    private val context = Context(Limits(1000uL, 1000uL))
    private fun source() = MemoryBinarySource(Bytes(byteArrayOf(1)), SourceId("common-ffmpeg-input"))
    private class Files : FfmpegFiles {
        override val input = "opaque-input"
        override val output = "opaque-output"
        override val outputLimit = 100uL
        var closed = 0
        var cleanupFailure = false
        var sizeResult: CoreResult<ULong> = CoreResult.Failure(CoreError(IssueCode("FFMPEG_OUTPUT_MISSING"), Stage.Verify, "Missing"))
        override suspend fun outputSize(): CoreResult<ULong> = sizeResult
        override suspend fun openOutput(): CoreResult<BinarySource> = error("Unused")
        override suspend fun patchOutput(patches: List<FfmpegPatch>): CoreResult<Unit> = error("Unused")
        override suspend fun close(): CoreResult<Unit> {
            closed++
            return if (cleanupFailure) CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.Remux, "Cleanup failed")) else CoreResult.Success(Unit)
        }
    }
    private class Runtime(val files: Files = Files(), val execute: suspend () -> FfmpegProcessResult = { FfmpegProcessResult(0, "trace") }) : FfmpegRuntime {
        var calls = 0
        override suspend fun execute(arguments: List<String>, timeoutMillis: Long, context: Context): FfmpegProcessResult {
            calls++; assertEquals(600_000L, timeoutMillis); assertEquals(listOf("argv", "no-shell"), arguments)
            return execute()
        }
        override suspend fun openFiles(source: BinarySource, context: Context, outputName: String, producesOutput: Boolean): CoreResult<FfmpegFiles> = CoreResult.Success(files)
    }
    @Test fun commonProcessClassificationPreservesPriorityAndStage(): Unit = runImmediate {
        for ((process, code) in listOf(
            FfmpegProcessResult(9, "", cancelled = true, timedOut = true) to "CANCELLED",
            FfmpegProcessResult(9, "", timedOut = true, outputLimited = true) to "BACKEND_TIMEOUT",
            FfmpegProcessResult(9, "", outputLimited = true, ioFailed = true) to "RESOURCE_LIMIT_EXCEEDED",
            FfmpegProcessResult(9, "", ioFailed = true) to "IO_READ_FAILED",
            FfmpegProcessResult(9, "") to "DECODE_FAILED",
            FfmpegProcessResult(null, "") to "DECODE_FAILED")) {
            val source = source(); val runtime = Runtime(execute = { process })
            val result = attempt { FfmpegWorkspace.use(runtime, source, BinaryReader(source, context), Stage.Trim, "Fixture") {
                run(listOf("argv", "no-shell"), "DECODE_FAILED", Stage.Verify)
            } }
            val error = assertIs<CoreResult.Failure>(result).error
            assertEquals(code, error.code.value); assertEquals(Stage.Verify, error.stage)
            assertEquals(1, runtime.calls); assertEquals(1, runtime.files.closed)
        }
    }
    @Test fun cancellationBeforeAllocationNeverOpensStorage(): Unit = runImmediate {
        val source = source(); val runtime = Runtime()
        val result = attempt { FfmpegWorkspace.use(runtime, source, BinaryReader(source, context.copy(cancellation = Cancellation { true })), Stage.Trim, "Fixture") { error("Must not run") } }
        assertEquals("CANCELLED", assertIs<CoreResult.Failure>(result).error.code.value)
        assertEquals(0, runtime.files.closed); assertEquals(0, runtime.calls)
    }
    @Test fun missingOutputUsesOperationErrorAndCleanupAlwaysRuns(): Unit = runImmediate {
        val source = source(); val runtime = Runtime()
        val result = attempt { FfmpegWorkspace.use(runtime, source, BinaryReader(source, context), Stage.Remux, "Fixture") { outputSize("ENCODE_FAILED", Stage.EncodeImage) } }
        val error = assertIs<CoreResult.Failure>(result).error
        assertEquals("ENCODE_FAILED", error.code.value); assertEquals(Stage.EncodeImage, error.stage)
        assertEquals(1, runtime.files.closed)
    }
    @Test fun cleanupFailurePreventsSuccessfulResult(): Unit = runImmediate {
        val source = source(); val runtime = Runtime().also { it.files.cleanupFailure = true }
        val result = attempt { FfmpegWorkspace.use(runtime, source, BinaryReader(source, context), Stage.DecodeFrame, "Fixture", cleanupStage = Stage.EncodeImage) { "not-published" } }
        val error = assertIs<CoreResult.Failure>(result).error
        assertEquals("IO_WRITE_FAILED", error.code.value); assertEquals(Stage.EncodeImage, error.stage)
    }
    @Test fun sourceChangesTakePrecedenceOverProcessFailure(): Unit = runImmediate {
        val memory = source(); var changed = false
        val source = object : BinarySource by memory {
            override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(memory.identity().orThrow().let {
                if (changed) it.copy(generation = GenerationToken("changed")) else it
            })
        }
        val runtime = Runtime(execute = { changed = true; FfmpegProcessResult(9, "", timedOut = true) })
        val result = attempt { FfmpegWorkspace.use(runtime, source, BinaryReader(source, context), Stage.Trim, "Fixture") { run(listOf("argv", "no-shell"), "DECODE_FAILED") } }
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(result).error.code.value)
        assertEquals(1, runtime.files.closed)
    }
    @Test fun successfulProcessReturnsBoundedDiagnosticsAndLeavesInputBorrowed(): Unit = runImmediate {
        val source = source(); val runtime = Runtime()
        val result = FfmpegWorkspace.use(runtime, source, BinaryReader(source, context), Stage.Trim, "Fixture") { run(listOf("argv", "no-shell"), "DECODE_FAILED") }
        assertEquals("trace", result.output); assertEquals(1, runtime.files.closed)
        assertEquals(1uL, source.size().orThrow())
    }
    @Test fun verifierFailureStillClosesWorkspaceAndLeavesInputBorrowed(): Unit = runImmediate {
        val source = source(); val runtime = Runtime()
        val result = attempt { FfmpegWorkspace.use(runtime, source, BinaryReader(source, context), Stage.Trim, "Fixture") {
            fail("POSTCONDITION_FAILED", "Verifier refused output", Stage.Verify)
        } }
        val error = assertIs<CoreResult.Failure>(result).error
        assertEquals("POSTCONDITION_FAILED", error.code.value); assertEquals(Stage.Verify, error.stage)
        assertEquals(1, runtime.files.closed); assertEquals(1uL, source.size().orThrow())
    }
    @Test fun unexpectedAdapterFailureIsStructuredAndWorkspaceIsClosed(): Unit = runImmediate {
        val source = source(); val runtime = Runtime(execute = { error("Fixture IO failure") })
        val result = attempt { FfmpegWorkspace.use(runtime, source, BinaryReader(source, context), Stage.Transcode, "Fixture", ioStage = Stage.EncodeImage) {
            run(listOf("argv", "no-shell"), "ENCODE_FAILED")
        } }
        val error = assertIs<CoreResult.Failure>(result).error
        assertEquals("IO_READ_FAILED", error.code.value); assertEquals(Stage.EncodeImage, error.stage)
        assertEquals(1, runtime.files.closed)
    }
    @Test fun cancellationAfterAllocationDoesNotReachEvenAnUncooperativeRuntime(): Unit = runImmediate {
        var cancelled = false
        val source = source(); val runtime = Runtime()
        val reader = BinaryReader(source, context.copy(cancellation = Cancellation { cancelled }))
        val result = attempt { FfmpegWorkspace.use(runtime, source, reader, Stage.Transcode, "Fixture") {
            cancelled = true
            run(listOf("argv", "no-shell"), "ENCODE_FAILED")
        } }
        assertEquals("CANCELLED", assertIs<CoreResult.Failure>(result).error.code.value)
        assertEquals(0, runtime.calls); assertEquals(1, runtime.files.closed)
    }
    @Test fun sourceChangeBeforeInvocationNeverStartsProcess(): Unit = runImmediate {
        val memory = source(); var changed = false
        val source = object : BinarySource by memory {
            override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(memory.identity().orThrow().let {
                if (changed) it.copy(generation = GenerationToken("changed")) else it
            })
        }
        val runtime = Runtime()
        val result = attempt { FfmpegWorkspace.use(runtime, source, BinaryReader(source, context), Stage.Trim, "Fixture") {
            changed = true
            run(listOf("argv", "no-shell"), "REMUX_FAILED")
        } }
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(result).error.code.value)
        assertEquals(0, runtime.calls); assertEquals(1, runtime.files.closed)
    }
    @Test fun oversizedOutputUsesRequestedOperationStage(): Unit = runImmediate {
        val source = source(); val runtime = Runtime()
        runtime.files.sizeResult = CoreResult.Failure(CoreError(IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Verify, "Too large"))
        val result = attempt { FfmpegWorkspace.use(runtime, source, BinaryReader(source, context), Stage.DecodeFrame, "Fixture") {
            outputSize("ENCODE_FAILED", Stage.EncodeImage)
        } }
        val error = assertIs<CoreResult.Failure>(result).error
        assertEquals("RESOURCE_LIMIT_EXCEEDED", error.code.value); assertEquals(Stage.EncodeImage, error.stage)
        assertEquals(1, runtime.files.closed)
    }
}
