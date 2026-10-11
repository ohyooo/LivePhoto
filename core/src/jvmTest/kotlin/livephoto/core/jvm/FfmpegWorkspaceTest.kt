package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.memory.MemoryBinarySource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FfmpegWorkspaceTest {
    private val context = Context(Limits(200_000uL, 80_000uL))
    private fun reader(bytes: ByteArray = byteArrayOf(1, 2, 3), context: Context = this.context) =
        BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("workspace")), context)
    private fun failure(result: CoreResult<*>, code: String, stage: Stage? = null) {
        val error = assertIs<CoreResult.Failure>(result).error
        assertEquals(code, error.code.value)
        if (stage != null) assertEquals(stage, error.stage)
    }

    @Test fun shortReadsAreMaterializedExactlyAndBorrowedSourceSurvivesCleanup(): Unit = runImmediate {
        val bytes = ByteArray(70_001) { (it % 251).toByte() }
        val memory = MemoryBinarySource(Bytes(bytes), SourceId("short-workspace"))
        val source = object : BinarySource by memory {
            override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = memory.readAt(offset, minOf(length, 997u))
        }
        val inputReader = BinaryReader(source, context)
        lateinit var directory: Path
        val returned = FfmpegWorkspace.use(inputReader, Stage.Remux, "Remux") {
            directory = input.parent
            assertContentEquals(bytes, Files.readAllBytes(input))
            assertEquals(80_000uL, outputLimit)
            Files.write(output, byteArrayOf(9))
            assertEquals(1uL, outputSize("REMUX_FAILED"))
            "verified"
        }
        assertEquals("verified", returned)
        assertFalse(Files.exists(directory))
        assertContentEquals(bytes, inputReader.readBuffer(0uL, 65_536u).orThrow().toByteArray() +
            inputReader.readBuffer(65_536uL, (bytes.size - 65_536).toUInt()).orThrow().toByteArray())
    }

    @Test fun failedVerificationCleansOnlyOwnedTemporaryFiles(): Unit = runImmediate {
        lateinit var directory: Path
        val result = attempt {
            FfmpegWorkspace.use(reader(), Stage.Trim, "Trim") {
                directory = input.parent
                Files.write(output, byteArrayOf(8))
                fail("POSTCONDITION_FAILED", "Independent verifier rejected output", Stage.Verify)
            }
        }
        failure(result, "POSTCONDITION_FAILED", Stage.Verify)
        assertFalse(Files.exists(directory))
    }

    @Test fun ioFailureIsStructuredAndTemporaryFilesAreCleaned(): Unit = runImmediate {
        lateinit var directory: Path
        val result = attempt {
            FfmpegWorkspace.use(reader(), Stage.Transcode, "Transcode") {
                directory = input.parent
                throw IOException("fixture")
            }
        }
        failure(result, "IO_READ_FAILED", Stage.Transcode)
        assertFalse(Files.exists(directory))
    }

    @Test fun insufficientStorageAndCancellationCannotEnterWorkspaceBody(): Unit = runImmediate {
        for ((limited, code) in listOf(
            context.copy(limits = context.limits.copy(maxSpoolBytes = 3uL)) to "RESOURCE_LIMIT_EXCEEDED",
            context.copy(limits = context.limits.copy(maxSpoolBytes = 2uL)) to "RESOURCE_LIMIT_EXCEEDED",
            context.copy(limits = context.limits.copy(maxOutputBytes = 0uL)) to "RESOURCE_LIMIT_EXCEEDED",
            context.copy(cancellation = Cancellation { true }) to "CANCELLED",
        )) {
            failure(attempt { FfmpegWorkspace.use(reader(context = limited), Stage.Trim, "Trim") { error("Must not allocate or invoke FFmpeg") } }, code)
        }
    }

    @Test fun outputAllowanceSubtractsInputAndNeverOverflows(): Unit = runImmediate {
        for ((limits, expected) in listOf(Limits(8uL, 100uL) to 5uL,
            Limits(ULong.MAX_VALUE, ULong.MAX_VALUE) to Long.MAX_VALUE.toULong())) {
            FfmpegWorkspace.use(reader(context = context.copy(limits = limits)), Stage.Remux, "Remux") { assertEquals(expected, outputLimit) }
        }
    }

    @Test fun decodeOnlyDoesNotReserveAnUnneededOutputBudget(): Unit = runImmediate {
        FfmpegWorkspace.use(reader(context = Context(Limits(3uL, 0uL))), Stage.Validate, "Probe", producesOutput = false) {
            assertEquals(0uL, outputLimit)
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(input))
            assertFalse(Files.exists(output))
        }
        failure(attempt {
            FfmpegWorkspace.use(reader(context = Context(Limits(2uL, 0uL))), Stage.Validate, "Probe", producesOutput = false) { error("Insufficient input storage") }
        }, "RESOURCE_LIMIT_EXCEEDED", Stage.Read)
    }

    @Test fun missingOrOversizedImageIsRejectedAtEncodingStage(): Unit = runImmediate {
        lateinit var directory: Path
        FfmpegWorkspace.use(reader(context = context.copy(limits = Limits(8uL, 2uL))), Stage.DecodeFrame, "Frame", "frame.jpg") {
            directory = input.parent
            failure(attempt { outputSize("ENCODE_FAILED", Stage.EncodeImage) }, "ENCODE_FAILED", Stage.EncodeImage)
            Files.write(output, byteArrayOf(1, 2, 3))
            failure(attempt { outputSize("ENCODE_FAILED", Stage.EncodeImage) }, "RESOURCE_LIMIT_EXCEEDED", Stage.EncodeImage)
        }
        assertFalse(Files.exists(directory))
    }

    @Test fun processStatusHasConsistentPrecedenceAndRetainsOperationStage(): Unit = runImmediate {
        FfmpegWorkspace.use(reader(), Stage.DecodeFrame, "Frame") {
            for ((process, code) in listOf(
                ProcessResult(7, "fixture", cancelled = true, timedOut = true, outputLimited = true, ioFailed = true) to "CANCELLED",
                ProcessResult(7, "fixture", timedOut = true, outputLimited = true) to "BACKEND_TIMEOUT",
                ProcessResult(7, "fixture", outputLimited = true, ioFailed = true) to "RESOURCE_LIMIT_EXCEEDED",
                ProcessResult(7, "fixture", ioFailed = true) to "IO_READ_FAILED",
                ProcessResult(7, "fixture") to "ENCODE_FAILED",
                ProcessResult(null, "") to "ENCODE_FAILED",
            )) failure(attempt { checkProcess(process, "ENCODE_FAILED", Stage.EncodeImage) }, code, Stage.EncodeImage)
            checkProcess(ProcessResult(0, ""), "DECODE_FAILED")
        }
    }

    @Test fun changedSourceIsRejectedBeforeInvocationAndBeforeReportingProcessFailure(): Unit = runImmediate {
        val memory = MemoryBinarySource(Bytes(byteArrayOf(1, 2, 3)), SourceId("changing-workspace"))
        var changed = false
        val source = object : BinarySource by memory {
            override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(memory.identity().orThrow().copy(
                generation = GenerationToken(if (changed) "changed" else "original")))
        }
        FfmpegWorkspace.use(BinaryReader(source, context), Stage.Trim, "Trim") {
            changed = true
            failure(attempt { run(listOf("must-not-execute-ffmpeg"), "REMUX_FAILED") }, "SOURCE_CHANGED")
            failure(attempt { checkProcess(ProcessResult(7, "fixture"), "REMUX_FAILED") }, "SOURCE_CHANGED")
        }
    }

    @Test fun cancellationAfterMaterializationIsObservedBeforeProcessStart(): Unit = runImmediate {
        var cancelled = false
        val inputReader = reader(context = context.copy(cancellation = Cancellation { cancelled }))
        lateinit var directory: Path
        val result = attempt {
            FfmpegWorkspace.use(inputReader, Stage.Transcode, "Transcode") {
                directory = input.parent
                cancelled = true
                run(listOf("must-not-execute-ffmpeg"), "ENCODE_FAILED")
            }
        }
        failure(result, "CANCELLED")
        assertFalse(Files.exists(directory))
    }

    @Test fun missingExecutableUsesRequestedFailureStageAndCleansWorkspace(): Unit = runImmediate {
        lateinit var directory: Path
        val result = attempt {
            FfmpegWorkspace.use(reader(), Stage.DecodeFrame, "Frame", "frame.jpg") {
                directory = input.parent
                run(listOf("livephoto-definitely-nonexistent-ffmpeg"), "ENCODE_FAILED", Stage.EncodeImage)
            }
        }
        failure(result, "ENCODE_FAILED", Stage.EncodeImage)
        assertFalse(Files.exists(directory))
    }
}
