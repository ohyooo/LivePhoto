package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.videoFacts
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Finite same-MP4 system packet streamcopy; source envelope is independently restored. */
internal object WindowsRemux {
    suspend fun run(command: List<String>, job: BackendJob): CoreResult<BackendResult> = attempt {
        if (job.operation != Operation.Remux) fail("INVALID_ARGUMENT", "Backend method and operation differ", Stage.Plan)
        job.validate().orThrow()
        if (job.remuxContainer != VideoContainer.Mp4) fail("CAPABILITY_UNSUPPORTED", "System remux currently targets MP4 only", Stage.Plan)
        val ref = job.inputs.single()
        if (ref.resourceId != null || ref.snapshot != null) fail("INVALID_ARGUMENT", "Core must resolve the remux resource", Stage.Plan)
        val source = (ref.input as? SourceSet.Single)?.source ?: fail("INVALID_ARGUMENT", "System remux requires an isolated video", Stage.Plan)
        val reader = BinaryReader(source, job.context)
        val size = reader.identity().orThrow().size
        if (size > 8_000_000uL || size > job.context.limits.maxOutputBytes || size * 2uL >= job.context.limits.maxSpoolBytes)
            fail("RESOURCE_LIMIT_EXCEEDED", "System remux input/output budget is insufficient", Stage.Plan)
        val before = BmffVideoProbe(reader).probe(ByteRange(0uL, size)).orThrow()
        val track = before.tracks.singleOrNull()
            ?: fail("CAPABILITY_UNSUPPORTED", "System remux cannot drop additional tracks", Stage.Plan)
        if (before.container != VideoContainer.Mp4 || track.handler != "vide" || track.codec != VideoCodec.Avc || track.sampleEntry != "avc1" ||
            track.width == null || track.height == null || track.width !in 48u..1024u || track.height !in 48u..1024u ||
            track.samples.size !in 1..64 || track.samples.any { it.presentationTime < 0 || it.decodeTime != it.presentationTime.toULong() })
            fail("CAPABILITY_UNSUPPORTED", "Input is outside the finite single-track non-reordered MP4 profile", Stage.Plan)
        val config = track.codecConfiguration
        if (config.size < 7 || config[1].toInt() and 255 != 66)
            fail("CAPABILITY_UNSUPPORTED", "System remux currently verifies Baseline AVC only", Stage.Plan)
        var offset = 6
        repeat(config[5].toInt() and 31) {
            val length = ((config[offset].toInt() and 255) shl 8) or (config[offset + 1].toInt() and 255)
            if (length < 4 || config[offset + 3].toInt() and 255 != 66)
                fail("CAPABILITY_UNSUPPORTED", "System remux SPS is outside Baseline", Stage.Plan)
            AvcSdrFrameProfile.verify(config.slice(offset + 2, offset + 2 + length), track.width, track.height).orThrow()
            offset += 2 + length
        }
        val ppsCount = config[offset++].toInt() and 255
        repeat(ppsCount) {
            val length = ((config[offset].toInt() and 255) shl 8) or (config[offset + 1].toInt() and 255)
            AvcSdrFrameProfile.verifyPps(config.slice(offset + 2, offset + 2 + length), 66).orThrow()
            offset += 2 + length
        }
        val metadata = RemuxVerification.metadata(reader, before)
        val sourceHash = sha256Range(reader, before.range).orThrow()
        fun ticks(value: Long): Long {
            if (value < 0 || value > Long.MAX_VALUE / 10_000_000L) fail("CAPABILITY_UNSUPPORTED", "System remux timestamp exceeds its exact domain", Stage.Plan)
            val product = value * 10_000_000L
            if (product % track.timescale.toLong() != 0L) fail("CAPABILITY_UNSUPPORTED", "System remux requires exact 100ns timestamps", Stage.Plan)
            return product / track.timescale.toLong()
        }
        val timeline = track.samples.joinToString(",") { "${ticks(it.presentationTime)}:${ticks(it.duration.toLong())}" }
        val nativeLimit = minOf(16_065_536uL, job.context.limits.maxSpoolBytes - size * 2uL)
        val requestRoot = Path.of(System.getProperty("java.home")).toAbsolutePath()
        if (WindowsNativeRemux.request(arrayOf("--remux-video-private", requestRoot.resolve("input.mp4").toString(), requestRoot.resolve("remux.mp4").toString(),
                track.samples.size.toString(), "8000000", nativeLimit.toString(), timeline)) == null)
            fail("CAPABILITY_UNSUPPORTED", "System remux requires a contiguous exact finite timing grid", Stage.Plan)
        val owned = mutableListOf<Path>()
        var directory: Path? = null
        try {
            val dir = Files.createTempDirectory("livephoto-system-remux-"); directory = dir
            val input = dir.resolve("input.mp4"); val packets = dir.resolve("remux.mp4"); val restored = dir.resolve("restored.mp4")
            owned.addAll(listOf(input, packets, restored))
            Files.newOutputStream(input, StandardOpenOption.CREATE_NEW).use { stream ->
                var position = 0uL
                while (position < size) {
                    val bytes = reader.readBuffer(position, minOf(65_536uL, size - position).toUInt()).orThrow()
                    stream.write(bytes.toByteArray()); position += bytes.size.toULong()
                }
            }
            suspend fun verifyCopy() {
                val copy = FileBinarySource(input)
                try {
                    val r = BinaryReader(copy, job.context)
                    if (copy.size().orThrow() != size || sha256Range(r, before.range).orThrow() != sourceHash)
                        fail("SOURCE_CHANGED", "System remux private input changed", Stage.Verify)
                } finally { copy.close() }
            }
            verifyCopy()
            val process = ExternalProcess.run(command + listOf("--remux-video-private", input.toString(), packets.toString(),
                track.samples.size.toString(), "8000000", nativeLimit.toString(), timeline), 30_000, job.context)
            when {
                process.cancelled -> fail("CANCELLED", "System remux cancelled", Stage.Remux)
                process.timedOut -> fail("BACKEND_TIMEOUT", "System remux exceeded its runtime limit", Stage.Remux)
                process.outputLimited -> fail("RESOURCE_LIMIT_EXCEEDED", "System remux diagnostic budget exceeded", Stage.Remux)
                process.ioFailed -> fail("IO_READ_FAILED", "System remux diagnostics were not completely drained", Stage.Remux)
                process.code != 0 -> fail("REMUX_FAILED", "System packet streamcopy failed", Stage.Remux)
            }
            if (!process.output.trim().startsWith("WINDOWS_MEDIA_API_REMUX=SUCCESS scope=private-compressed-video-not-preservation samples=${track.samples.size} "))
                fail("POSTCONDITION_FAILED", "System remux worker completion is missing", Stage.Verify)
            verifyCopy()
            val packetSource = FileBinarySource(packets)
            try {
                if (packetSource.size().orThrow() > nativeLimit) fail("RESOURCE_LIMIT_EXCEEDED", "Native remux output exceeded its spool budget", Stage.Verify)
                Files.newOutputStream(restored, StandardOpenOption.CREATE_NEW).use { stream ->
                    val sink = object : BinarySink {
                        override suspend fun write(bytes: Bytes): CoreResult<UInt> { stream.write(bytes.toByteArray()); return CoreResult.Success(bytes.size.toUInt()) }
                        override suspend fun seek(offset: ULong): CoreResult<Unit> = CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.WriteProtocol, "Private restoration is sequential"))
                        override suspend fun truncate(length: ULong): CoreResult<Unit> = CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.WriteProtocol, "Private restoration is sequential"))
                        override suspend fun flush(): CoreResult<Unit> { stream.flush(); return CoreResult.Success(Unit) }
                        override suspend fun close(): CoreResult<Unit> = CoreResult.Success(Unit)
                    }
                    RemuxEnvelopeRestoration.write(reader, BinaryReader(packetSource, job.context), sink).orThrow()
                }
            } finally { packetSource.close() }
            val restoredSource = FileBinarySource(restored)
            try {
                val restoredReader = BinaryReader(restoredSource, job.context)
                val after = BmffVideoProbe(restoredReader).probe(ByteRange(0uL, restoredSource.size().orThrow())).orThrow()
                RemuxVerification.verify(reader, before, restoredReader, after)
                RemuxVerification.verifyMetadata(metadata, RemuxVerification.metadata(restoredReader, after))
                // A complete finite OS decode is an additional gate, not metadata evidence.
                WindowsMediaFoundationBackend(command).probe(ProbeRequest(ResourceRef(SourceSet.Single(restoredSource)), true, job.context)).orThrow()
                verifyCopy()
                if (sourceHash != sha256Range(reader, before.range).orThrow()) fail("SOURCE_CHANGED", "System remux source changed", Stage.Verify)
                val facts = videoFacts(after)
                val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4")).orThrow()
                try { copyRange(restoredReader, handle.sink, ByteRange(0uL, size), job.context).orThrow(); handle.sink.flush().orThrow() }
                finally { handle.sink.close().orThrow() }
                BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, "video/mp4", size)), listOf(facts), listOf(
                    ExecutionRecord(Stage.Remux, "windows-media-foundation", "Finite compressed sample streamcopy; classified source envelope restored; complete sample/configuration/metadata verification and OS decode; same MP4 only", false, true, false, videoFacts(before), facts)))
            } finally { restoredSource.close() }
        } catch (fault: CoreFault) { throw fault }
        catch (_: Exception) { fail("IO_READ_FAILED", "System remux temporary media IO failed", Stage.Remux) }
        finally {
            var failed = false
            for (path in owned.asReversed() + listOfNotNull(directory)) try { Files.deleteIfExists(path) } catch (_: Exception) { failed = true }
            if (failed) fail("IO_WRITE_FAILED", "System remux temporary cleanup failed", Stage.Remux)
        }
    }
}
