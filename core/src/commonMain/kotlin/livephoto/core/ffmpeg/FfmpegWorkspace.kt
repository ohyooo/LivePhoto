package livephoto.core.ffmpeg

import livephoto.core.*
import livephoto.core.binary.*

/** Common media orchestration; actual storage and process IO are injected. */
internal class FfmpegWorkspace private constructor(
    private val files: FfmpegFiles, private val runtime: FfmpegRuntime,
    private val reader: BinaryReader, private val stage: Stage, private val label: String,
) {
    val input: String get() = files.input
    val output: String get() = files.output
    val outputLimit: ULong get() = files.outputLimit
    suspend fun run(arguments: List<String>, errorCode: String, processStage: Stage = stage): FfmpegProcessResult {
        reader.validateIdentity().orThrow()
        val process = runtime.execute(arguments, 600_000L, reader.context)
        reader.validateIdentity().orThrow()
        when {
            process.cancelled -> fail("CANCELLED", "$label cancelled", processStage)
            process.timedOut -> fail("BACKEND_TIMEOUT", "$label exceeded its runtime limit", processStage)
            process.outputLimited -> fail("RESOURCE_LIMIT_EXCEEDED", "$label diagnostic budget exceeded", processStage)
            process.ioFailed -> fail("IO_READ_FAILED", "$label process did not exit with drained diagnostics", processStage)
            process.code != 0 -> fail(errorCode, "$label FFmpeg invocation failed without fallback", processStage)
        }
        return process
    }
    suspend fun outputSize(errorCode: String, outputStage: Stage = stage): ULong =
        when (val result = files.outputSize()) {
            is CoreResult.Success -> result.value
            is CoreResult.Failure -> if (result.error.code.value == "FFMPEG_OUTPUT_MISSING")
                fail(errorCode, "$label produced no file", outputStage)
            else throw CoreFault(result.error.copy(stage = outputStage))
        }
    suspend fun openOutput(): BinarySource = files.openOutput().orThrow()
    suspend fun patchOutput(patches: List<FfmpegPatch>) { files.patchOutput(patches).orThrow() }

    companion object {
        suspend fun <T> use(runtime: FfmpegRuntime, source: BinarySource, reader: BinaryReader,
            stage: Stage, label: String, outputName: String = "output.bin", cleanupStage: Stage = stage,
            producesOutput: Boolean = true, ioStage: Stage = stage,
            block: suspend FfmpegWorkspace.() -> T): T {
            require(outputName in setOf("output.bin", "frame.jpg"))
            reader.identity().orThrow()
            checkCancelled(reader.context)
            val files = runtime.openFiles(source, reader.context, outputName, producesOutput).orThrow()
            try {
                reader.validateIdentity().orThrow()
                return FfmpegWorkspace(files, runtime, reader, stage, label).block()
            } catch (fault: CoreFault) { throw fault }
            catch (_: Exception) { fail("IO_READ_FAILED", "$label temporary media IO failed", ioStage) }
            finally {
                when (val result = files.close()) {
                    is CoreResult.Success -> Unit
                    is CoreResult.Failure -> throw CoreFault(result.error.copy(stage = cleanupStage))
                }
            }
        }
    }
}
