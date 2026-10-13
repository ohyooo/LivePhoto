package livephoto.adapters.files

import livephoto.core.*
import livephoto.core.ffmpeg.*
import livephoto.core.memory.MemoryBinarySource
import okio.Path.Companion.toPath
import kotlin.test.*

class FileFfmpegWorkspaceTest {
    private fun input(bytes: ByteArray = byteArrayOf(1, 2, 3)): BinarySource = MemoryBinarySource(Bytes(bytes), SourceId("workspace-input"))
    @Test fun ownsCopiesOnlyAndCountsRetainedInputSpool() = sandbox { box ->
        val context = Context(Limits(10uL, 100uL))
        val source = input()
        val files = result(FileFfmpegWorkspace.open(box.root.toString(), source, context, "frame.jpg", true, 4uL))
        assertEquals(3uL, files.outputLimit)
        assertContentEquals(byteArrayOf(1, 2, 3), box.contents(files.input.toPath()))
        failure(files.outputSize(), "FFMPEG_OUTPUT_MISSING")
        result(files.close())
        assertTrue(box.fs.list(box.root).isEmpty())
        assertEquals(3uL, result(source.size()))
    }
    @Test fun unsignedAndZeroBudgetFailuresCreateNothing() = sandbox { box ->
        for ((maximum, retained) in listOf(2uL to 0uL, 3uL to 0uL, 10uL to ULong.MAX_VALUE, 4uL to 2uL)) {
            failure(FileFfmpegWorkspace.open(box.root.toString(), input(), Context(Limits(maximum, 100uL)), "output.bin", true, retained), "RESOURCE_LIMIT_EXCEEDED")
            assertTrue(box.fs.list(box.root).isEmpty())
        }
    }
    @Test fun decodeOnlyUsesNoOutputReservation() = sandbox { box ->
        val files = result(FileFfmpegWorkspace.open(box.root.toString(), input(), Context(Limits(3uL, 0uL)), "output.bin", false))
        assertEquals(0uL, files.outputLimit)
        failure(files.openOutput(), "FFMPEG_OUTPUT_MISSING")
        result(files.close())
    }
    @Test fun frozenOutputDetectsContentChangesAndPatchRequiresClosedReaders() = sandbox { box ->
        val files = result(FileFfmpegWorkspace.open(box.root.toString(), input(), testContext, "output.bin", true))
        val output = files.output.toPath()
        box.fs.openReadWrite(output, mustCreate = true).use { it.write(0, byteArrayOf(4, 5, 6), 0, 3) }
        val source = result(files.openOutput())
        val patch = FfmpegPatch(ByteRange(1uL, 1uL), Bytes(byteArrayOf(9)))
        failure(files.patchOutput(listOf(patch)), "INVALID_ARGUMENT")
        box.fs.openReadWrite(output, mustExist = true).use { it.write(1, byteArrayOf(7), 0, 1) }
        failure(source.readAt(0uL, 3u), "SOURCE_CHANGED")
        source.close()
        result(files.patchOutput(listOf(patch)))
        val fresh = result(files.openOutput())
        assertContentEquals(byteArrayOf(4, 9, 6), result(fresh.readAt(0uL, 3u)).toByteArray())
        fresh.close(); result(files.close())
    }
    @Test fun invalidAndOverlappingPatchesAreRejectedBeforeWriting() = sandbox { box ->
        val files = result(FileFfmpegWorkspace.open(box.root.toString(), input(), testContext, "output.bin", true))
        val path = files.output.toPath()
        box.fs.openReadWrite(path, mustCreate = true).use { it.write(0, byteArrayOf(4, 5, 6), 0, 3) }
        val invalid = listOf(
            listOf(FfmpegPatch(ByteRange(ULong.MAX_VALUE - 1uL, 1uL), Bytes(byteArrayOf(9)))),
            listOf(FfmpegPatch(ByteRange(0uL, 2uL), Bytes(byteArrayOf(9)))),
            listOf(FfmpegPatch(ByteRange(0uL, 2uL), Bytes(byteArrayOf(9, 9))), FfmpegPatch(ByteRange(1uL, 1uL), Bytes(byteArrayOf(9)))))
        for (patches in invalid) {
            failure(files.patchOutput(patches), "OFFSET_OUT_OF_BOUNDS")
            assertContentEquals(byteArrayOf(4, 5, 6), box.contents(path))
        }
        result(files.close())
    }
    @Test fun oversizeOutputIsNotOpenedOrPatched() = sandbox { box ->
        val files = result(FileFfmpegWorkspace.open(box.root.toString(), input(), Context(Limits(4uL, 100uL)), "output.bin", true))
        box.fs.openReadWrite(files.output.toPath(), mustCreate = true).use { it.write(0, byteArrayOf(4, 5), 0, 2) }
        failure(files.openOutput(), "RESOURCE_LIMIT_EXCEEDED")
        failure(files.patchOutput(emptyList()), "RESOURCE_LIMIT_EXCEEDED")
        result(files.close())
    }
    @Test fun failedCopyReleasesHandleBeforeWindowsCleanupAndPreservesError() = sandbox { box ->
        val original = input()
        val source = object : BinarySource by original {
            override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> =
                CoreResult.Failure(CoreError(IssueCode("CUSTOM_INPUT_FAILURE"), Stage.Read, "Expected test error"))
        }
        failure(FileFfmpegWorkspace.open(box.root.toString(), source, testContext, "output.bin", true), "CUSTOM_INPUT_FAILURE")
        assertTrue(box.fs.list(box.root).isEmpty())
    }
    @Test fun cleanupPreservesUnknownFilesAndCanBeRetried() = sandbox { box ->
        val files = result(FileFfmpegWorkspace.open(box.root.toString(), input(), testContext, "output.bin", true))
        val unknown = files.input.toPath().parent!! / "unknown"
        box.fs.openReadWrite(unknown, mustCreate = true).use { it.write(0, byteArrayOf(8), 0, 1) }
        assertIs<CoreResult.Failure>(files.close())
        assertContentEquals(byteArrayOf(8), box.contents(unknown))
        box.fs.delete(unknown)
        result(files.close())
        assertTrue(box.fs.list(box.root).isEmpty())
    }
}
