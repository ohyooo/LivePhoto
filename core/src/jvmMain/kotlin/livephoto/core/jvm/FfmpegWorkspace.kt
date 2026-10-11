package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import java.nio.file.Files
import java.nio.file.Path

/** IO/process adapter only: FFmpeg owns media processing; Core owns protocol and result verification. */
internal class FfmpegWorkspace private constructor(
    val input: Path,
    val output: Path,
    val outputLimit: ULong,
    private val reader: BinaryReader,
    private val stage: Stage,
    private val label: String,
    private val producesOutput: Boolean,
) {
    /** Validate the original source on both sides of every external invocation, including failures. */
    suspend fun run(arguments: List<String>, errorCode: String, processStage: Stage = stage): ProcessResult {
        reader.validateIdentity().orThrow()
        val process = ExternalProcess.run(arguments, 600_000L, reader.context)
        checkProcess(process, errorCode, processStage)
        return process
    }

    internal suspend fun checkProcess(process: ProcessResult, errorCode: String, processStage: Stage = stage) {
        reader.validateIdentity().orThrow()
        when {
            process.cancelled -> fail("CANCELLED", "$label cancelled", processStage)
            process.timedOut -> fail("BACKEND_TIMEOUT", "$label exceeded its runtime limit", processStage)
            process.outputLimited -> fail("RESOURCE_LIMIT_EXCEEDED", "$label diagnostic budget exceeded", processStage)
            process.ioFailed -> fail("IO_READ_FAILED", "$label process did not exit with drained diagnostics", processStage)
            process.code != 0 -> fail(errorCode, "$label FFmpeg invocation failed without fallback", processStage)
        }
    }

    fun outputSize(errorCode: String, outputStage: Stage = stage): ULong {
        check(producesOutput) { "A decode-only workspace has no media output" }
        if (!Files.isRegularFile(output)) fail(errorCode, "$label produced no file", outputStage)
        val size = Files.size(output).toULong()
        if (size > outputLimit) fail("RESOURCE_LIMIT_EXCEEDED", "$label output exceeded its storage budget", outputStage)
        return size
    }

    companion object {
        /** Own exactly input/output/directory. Never delete a source or a caller's staging asset. */
        suspend fun <T> use(
            reader: BinaryReader,
            stage: Stage,
            label: String,
            outputName: String = "output.bin",
            cleanupStage: Stage = stage,
            producesOutput: Boolean = true,
            ioStage: Stage = stage,
            block: suspend FfmpegWorkspace.() -> T,
        ): T {
            require(outputName in setOf("output.bin", "frame.jpg"))
            val identity = reader.identity().orThrow()
            val limits = reader.context.limits
            if (identity.size > limits.maxSpoolBytes || producesOutput && identity.size == limits.maxSpoolBytes)
                fail("RESOURCE_LIMIT_EXCEEDED", "$label input exceeds available temporary storage", if (producesOutput) Stage.Plan else Stage.Read)
            val outputLimit = if (producesOutput) minOf(limits.maxOutputBytes, limits.maxSpoolBytes - identity.size, Long.MAX_VALUE.toULong()) else 0uL
            if (producesOutput && outputLimit == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "$label has no output storage budget", Stage.Plan)
            checkCancelled(reader.context)
            var directory: Path? = null
            var input: Path? = null
            var output: Path? = null
            try {
                directory = Files.createTempDirectory("livephoto-ffmpeg-")
                input = directory.resolve("input.bin")
                output = directory.resolve(outputName)
                Files.newOutputStream(input).use { stream ->
                    var offset = 0uL
                    while (offset < identity.size) {
                        val bytes = reader.readBuffer(offset, minOf(65_536uL, identity.size - offset).toUInt()).orThrow()
                        stream.write(bytes.toByteArray())
                        offset += bytes.size.toULong()
                    }
                }
                reader.validateIdentity().orThrow()
                return FfmpegWorkspace(input, output, outputLimit, reader, stage, label, producesOutput).block()
            } catch (fault: CoreFault) { throw fault }
            catch (_: Exception) { fail("IO_READ_FAILED", "$label temporary media IO failed", ioStage) }
            finally {
                var failed = false
                for (owned in listOfNotNull(output, input, directory)) try { Files.deleteIfExists(owned) }
                catch (_: Exception) { failed = true }
                if (failed) fail("IO_WRITE_FAILED", "$label temporary cleanup failed", cleanupStage)
            }
        }
    }
}
