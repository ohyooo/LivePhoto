package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.ffmpeg.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Transitional JVM adapter; all media commands and verification live in common code. */
internal object JvmFfmpegRuntime : FfmpegRuntime {
    override suspend fun execute(arguments: List<String>, timeoutMillis: Long, context: Context): FfmpegProcessResult {
        val result = ExternalProcess.run(arguments, timeoutMillis, context)
        return FfmpegProcessResult(result.code, result.output, result.outputLimited, result.cancelled, result.timedOut, result.ioFailed)
    }
    override suspend fun openFiles(source: BinarySource, context: Context, outputName: String,
        producesOutput: Boolean): CoreResult<FfmpegFiles> = attempt {
        require(outputName in setOf("output.bin", "frame.jpg"))
        val reader = BinaryReader(source, context)
        val size = reader.identity().orThrow().size
        if (size > context.limits.maxSpoolBytes || producesOutput && size == context.limits.maxSpoolBytes)
            fail("RESOURCE_LIMIT_EXCEEDED", "FFmpeg input exceeds available temporary storage", Stage.Plan)
        val limit = if (producesOutput) minOf(context.limits.maxOutputBytes, context.limits.maxSpoolBytes - size, Long.MAX_VALUE.toULong()) else 0uL
        if (producesOutput && limit == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "FFmpeg has no output storage budget", Stage.Plan)
        checkCancelled(context)
        val directory = try { Files.createTempDirectory("livephoto-ffmpeg-") }
        catch (_: Exception) { fail("IO_WRITE_FAILED", "FFmpeg workspace creation failed", Stage.Read) }
        val files = JvmFfmpegFiles(directory, limit, producesOutput, context)
        try {
            Files.newOutputStream(directory.resolve("input.bin")).use { stream ->
                var offset = 0uL
                while (offset < size) {
                    val bytes = reader.readBuffer(offset, minOf(65536uL, size - offset).toUInt()).orThrow()
                    stream.write(bytes.toByteArray()); offset += bytes.size.toULong()
                }
            }
            reader.validateIdentity().orThrow()
            files.setOutputName(outputName)
            files
        } catch (failure: Exception) {
            files.close().orThrow()
            if (failure is CoreFault) throw failure
            fail("IO_READ_FAILED", "FFmpeg temporary input acquisition failed", Stage.Read)
        }
    }
}

private class JvmFfmpegFiles(private val directory: Path, override val outputLimit: ULong,
    private val producesOutput: Boolean, private val context: Context) : FfmpegFiles {
    private var outputPath = directory.resolve("output.bin")
    fun setOutputName(name: String) { outputPath = directory.resolve(name) }
    override val input: String get() = directory.resolve("input.bin").toString()
    override val output: String get() = outputPath.toString()
    private var closed = false
    override suspend fun outputSize(): CoreResult<ULong> = attempt {
        if (closed || !producesOutput || !Files.isRegularFile(outputPath))
            fail("FFMPEG_OUTPUT_MISSING", "FFmpeg produced no regular output", Stage.Verify)
        val size = Files.size(outputPath).toULong()
        if (size > outputLimit) fail("RESOURCE_LIMIT_EXCEEDED", "FFmpeg output exceeds its storage budget", Stage.Verify)
        size
    }
    override suspend fun openOutput(): CoreResult<BinarySource> = attempt {
        outputSize().orThrow()
        FileBinarySource(outputPath)
    }
    override suspend fun patchOutput(patches: List<FfmpegPatch>): CoreResult<Unit> = attempt {
        val size = outputSize().orThrow()
        for (patch in patches) {
            if (patch.range.offset > size || patch.range.length > size - patch.range.offset || patch.range.length != patch.bytes.size.toULong())
                fail("OFFSET_OUT_OF_BOUNDS", "AAC hint patch exceeds owned output", Stage.Remux)
        }
        try {
            FileChannel.open(outputPath, StandardOpenOption.WRITE).use { channel ->
                for (patch in patches) {
                    val buffer = ByteBuffer.wrap(patch.bytes.toByteArray()); var offset = patch.range.offset.toLong()
                    while (buffer.hasRemaining()) {
                        checkCancelled(context)
                        val written = channel.write(buffer, offset)
                        if (written <= 0) fail("IO_WRITE_FAILED", "AAC hint patch made no progress", Stage.Remux)
                        offset += written
                    }
                }
                channel.force(true)
            }
        } catch (failure: CoreFault) { throw failure }
        catch (_: Exception) { fail("IO_WRITE_FAILED", "AAC hint patch failed", Stage.Remux) }
    }
    override suspend fun close(): CoreResult<Unit> = attempt {
        closed = true
        var failed = false
        for (path in listOf(outputPath, directory.resolve("input.bin"), directory)) {
            try { Files.deleteIfExists(path) } catch (_: Exception) { failed = true }
        }
        if (failed) fail("IO_WRITE_FAILED", "FFmpeg temporary cleanup failed", Stage.Remux)
    }
}
