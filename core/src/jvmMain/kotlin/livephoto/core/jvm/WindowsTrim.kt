package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Common structural selection followed by actual bounded OS compressed remux and decode.
 * No seek approximation, hidden preroll, or encoder fallback. Core owns final publication. */
internal object WindowsTrim {
    suspend fun run(command: List<String>, job: BackendJob): CoreResult<BackendResult> = attempt {
        if (job.operation != Operation.Trim) fail("INVALID_ARGUMENT", "Backend method and operation differ", Stage.Plan)
        job.validate().orThrow()
        val ref = job.inputs.single()
        if (ref.resourceId != null || ref.snapshot != null) fail("INVALID_ARGUMENT", "Core must resolve the trim resource", Stage.Plan)
        val source = (ref.input as? SourceSet.Single)?.source ?: fail("INVALID_ARGUMENT", "System trim requires an isolated video", Stage.Plan)
        val reader = BinaryReader(source, job.context)
        val size = reader.identity().orThrow().size
        if (size > minOf(8_000_000uL, job.context.limits.maxSpoolBytes)) fail("RESOURCE_LIMIT_EXCEEDED", "System trim input budget is insufficient", Stage.Plan)
        val before = BmffVideoProbe(reader).probe(ByteRange(0uL, size)).orThrow()
        // Intentionally lossless-only even when Exact explicitly allows an encoder.
        val plan = planLosslessTrim(reader, before, job.trim!!)
        val metadata = RemuxVerification.metadata(reader, before, trimDurationsVerifiedSeparately = true)
        val sourceHash = sha256Range(reader, before.range).orThrow()
        var directory: Path? = null; var selected: Path? = null
        try {
            val dir = Files.createTempDirectory("livephoto-system-trim-"); directory = dir
            val file = dir.resolve("selected.mp4"); selected = file
            val privateContext = job.context.copy(limits = job.context.limits.copy(maxOutputBytes = minOf(8_000_000uL, job.context.limits.maxOutputBytes, job.context.limits.maxSpoolBytes)))
            Files.newOutputStream(file, StandardOpenOption.CREATE_NEW).use { stream ->
                val sink = object : BinarySink {
                    override suspend fun write(bytes: Bytes): CoreResult<UInt> { stream.write(bytes.toByteArray()); return CoreResult.Success(bytes.size.toUInt()) }
                    override suspend fun seek(offset: ULong): CoreResult<Unit> = unsupported()
                    override suspend fun truncate(length: ULong): CoreResult<Unit> = unsupported()
                    override suspend fun flush(): CoreResult<Unit> { stream.flush(); return CoreResult.Success(Unit) }
                    override suspend fun close(): CoreResult<Unit> = CoreResult.Success(Unit)
                    private fun unsupported() = CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Trim, "Private trim is sequential"))
                }
                LosslessTrimWriter.write(BinaryReader(source, privateContext), job.trim, sink).orThrow()
            }
            val trimmed = FileBinarySource(file)
            try {
                val length = trimmed.size().orThrow()
                if (length >= job.context.limits.maxSpoolBytes) fail("RESOURCE_LIMIT_EXCEEDED", "System trim private file exhausted spool budget", Stage.Plan)
                val selectedReader = BinaryReader(trimmed, job.context)
                val actual = BmffVideoProbe(selectedReader).probe(ByteRange(0uL, length)).orThrow()
                verifyTrimDurationHeaders(selectedReader, actual, plan)
                RemuxVerification.verify(reader, plan.expected(), selectedReader, actual)
                RemuxVerification.verifyMetadata(metadata, RemuxVerification.metadata(selectedReader, actual, trimDurationsVerifiedSeparately = true))
                val remux = WindowsRemux.run(command, job.copy(operation = Operation.Remux, trim = null, remuxContainer = VideoContainer.Mp4,
                    inputs = listOf(ResourceRef(SourceSet.Single(trimmed))), context = job.context.copy(limits = job.context.limits.copy(maxSpoolBytes = job.context.limits.maxSpoolBytes - length)))).orThrow()
                if (sourceHash != sha256Range(reader, before.range).orThrow()) fail("SOURCE_CHANGED", "System trim original source changed", Stage.Verify)
                reader.validateIdentity().orThrow()
                remux.copy(tracks = listOf(plan.trackTrim), timelineMap = plan.mapping, execution = remux.execution.map {
                    it.copy(stage = Stage.Trim, inputFacts = videoFacts(before),
                        reason = "Common closed-IDR sample selection and exact table/duration relocation; actual OS compressed remux; independently verified selected samples, ordinary metadata and complete OS decode; no encoding or hidden content")
                })
            } finally { trimmed.close() }
        } catch (fault: CoreFault) { throw fault }
        catch (_: Exception) { fail("IO_READ_FAILED", "System trim private media IO failed", Stage.Trim) }
        finally {
            var failed = false
            for (path in listOfNotNull(selected, directory)) try { Files.deleteIfExists(path) } catch (_: Exception) { failed = true }
            if (failed) fail("IO_WRITE_FAILED", "System trim temporary cleanup failed", Stage.Trim)
        }
    }
}
