package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.selectFrame
import livephoto.core.implementation.videoFacts
import livephoto.core.implementation.jpegFacts
import livephoto.core.jpeg.JpegParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import livephoto.core.memory.MemoryBinarySource

/** Finite software-AVC system frame extraction, with independent Core timeline/JPEG verification. */
@OptIn(ExperimentalUnsignedTypes::class)
internal object WindowsFrame {
    suspend fun run(command: List<String>, job: BackendJob): CoreResult<BackendResult> = attempt {
        if (job.operation != Operation.ExtractFrame) fail("INVALID_ARGUMENT", "Backend method and operation differ", Stage.Plan)
        job.validate().orThrow()
        val encoding = job.imageEncoding ?: fail("INVALID_ARGUMENT", "Missing image encoding", Stage.Plan)
        if (encoding.format != ImageFormat.Jpeg || encoding.quality != null || encoding.dynamicRange != DynamicRangePolicy.Preserve)
            fail("CAPABILITY_UNSUPPORTED", "System frame profile supports default-quality SDR JPEG only", Stage.Plan)
        val ref = job.inputs.single()
        if (ref.resourceId != null || ref.snapshot != null) fail("INVALID_ARGUMENT", "Core must resolve the frame resource")
        val source = (ref.input as? SourceSet.Single)?.source ?: fail("INVALID_ARGUMENT", "Frame requires isolated video")
        val reader = BinaryReader(source, job.context)
        val identity = reader.identity().orThrow(); val range = ByteRange(0uL, identity.size)
        val video = BmffVideoProbe(reader).probe(range).orThrow()
        val selected = selectFrame(video, job.position ?: fail("INVALID_ARGUMENT", "Missing frame position"))
        val track = selected.track
        if (video.tracks.size != 1 || track.codec != VideoCodec.Avc || track.sampleEntry != "avc1" ||
            track.width == null || track.height == null || track.width !in 48u..1024u || track.height !in 48u..1024u ||
            track.width % 2u != 0u || track.height % 2u != 0u || track.samples.size !in 1..64 ||
            track.samples.any { it.presentationTime < 0 } || track.samples.map { it.presentationTime }.distinct().size != track.samples.size)
            fail("CAPABILITY_UNSUPPORTED", "Input is outside the finite system frame profile", Stage.Plan)
        // High is admitted only with explicit eight-bit 4:2:0 SPS proof. Never
        // downgrade High10/422/444, unknown bit depth or custom scaling syntax.
        val config = track.codecConfiguration
        val profile = config[1].toInt() and 255
        if (profile !in setOf(66, 77, 100)) fail("HDR_PRESERVATION_UNAVAILABLE", "System frame profile requires proven eight-bit Baseline/Main/High AVC", Stage.Plan)
        var offset = 6
        repeat(config[5].toInt() and 31) {
            val count = ((config[offset].toInt() and 255) shl 8) or (config[offset + 1].toInt() and 255)
            if (count < 4 || config[offset + 3].toInt() and 255 != profile)
                fail("HDR_PRESERVATION_UNAVAILABLE", "SPS profile differs from the finite eight-bit profile", Stage.Plan)
            AvcSdrFrameProfile.verify(config.slice(offset + 2, offset + count + 2), track.width, track.height).orThrow()
            offset += count + 2
        }
        run {
            val count = config[offset++].toInt() and 255
            repeat(count) {
                val length = ((config[offset].toInt() and 255) shl 8) or (config[offset + 1].toInt() and 255)
                AvcSdrFrameProfile.verifyPps(config.slice(offset + 2, offset + length + 2), profile).orThrow()
                offset += length + 2
            }
        }
        val nalWidth = (config[4].toInt() and 3) + 1
        val nalBudget = ParseBudget(job.context)
        for (sample in track.samples) {
            val framing = validateNalFraming(reader, sample.range, nalWidth, VideoCodec.Avc, nalBudget, 7u).orThrow()
            if (framing.types.any { it !in setOf(1, 5, 9) })
                fail("HDR_PRESERVATION_UNAVAILABLE", "System frame cannot interpret auxiliary SEI or changing parameter sets", Stage.Plan)
        }
        RemuxVerification.metadata(reader, video)
        verifyVisualMetadata(reader, range, video.container)
        val matrix = Bytes(unsignedBytes(0x10000uL, 4, Endian.Big).toByteArray() + ByteArray(12) +
            unsignedBytes(0x10000uL, 4, Endian.Big).toByteArray() + ByteArray(12) + unsignedBytes(0x40000000uL, 4, Endian.Big).toByteArray())
        if (track.transform != matrix || track.displayWidthFixed.toULong() != track.width.toULong() * 65536uL ||
            track.displayHeightFixed.toULong() != track.height.toULong() * 65536uL)
            fail("CAPABILITY_UNSUPPORTED", "System frame cannot discard display transforms", Stage.Plan)
        val pixels = track.width.toULong() * track.height.toULong()
        val rawSize = pixels * 3uL / 2uL
        // Reserve simultaneous RGB, NV12 and compressed JPEG buffers in the image budget.
        if (pixels * 8uL > job.context.limits.maxMetadataBytes || identity.size + rawSize >= job.context.limits.maxSpoolBytes || identity.size > 128_000_000uL)
            fail("RESOURCE_LIMIT_EXCEEDED", "System frame exceeds input/pixel storage limits", Stage.Plan)
        val outputLimit = minOf(job.context.limits.maxOutputBytes, job.context.limits.maxSpoolBytes - identity.size - rawSize,
            job.context.limits.maxMetadataBytes - pixels * 8uL, 8_000_000uL).toInt()
        if (outputLimit <= 0) fail("RESOURCE_LIMIT_EXCEEDED", "No JPEG output budget", Stage.Plan)
        val expected = MessageDigest.getInstance("SHA-256")
        fun hundredNs(time: Time): Long {
            if (time.value > Long.MAX_VALUE / 10_000_000L) fail("VALUE_NOT_REPRESENTABLE", "System frame PTS exceeds its signed domain", Stage.Plan)
            val product = time.value * 10_000_000L
            if (product % time.timescale.toLong() != 0L) fail("VALUE_NOT_REPRESENTABLE", "System frame requires exact 100ns PTS", Stage.Plan)
            return product / time.timescale.toLong()
        }
        track.samples.sortedBy { it.presentationTime }.forEach { expected.update(ByteBuffer.allocate(8).putLong(hundredNs(Time(it.presentationTime, track.timescale))).array()) }
        val timeline = hex(expected.digest()); val actualTime = hundredNs(selected.time)
        val sourceHash = sha256Range(reader, range).orThrow()
        var directory: Path? = null; var input: Path? = null; var raw: Path? = null
        try {
            val dir = Files.createTempDirectory("livephoto-system-frame-"); directory = dir
            val inputPath = dir.resolve("private.mp4"); input = inputPath
            val rawPath = dir.resolve("selected.nv12"); raw = rawPath
            Files.newOutputStream(inputPath).use { stream ->
                var at = 0uL
                while (at < identity.size) {
                    val bytes = reader.readBuffer(at, minOf(65_536uL, identity.size - at).toUInt()).orThrow()
                    stream.write(bytes.toByteArray()); at += bytes.size.toULong()
                }
            }
            suspend fun verifySources() {
                reader.validateIdentity().orThrow()
                if (sha256Range(reader, range).orThrow() != sourceHash) fail("SOURCE_CHANGED", "System frame input changed")
                val copied = FileBinarySource(inputPath)
                try {
                    if (copied.size().orThrow() != identity.size || sha256Range(BinaryReader(copied, job.context), range).orThrow() != sourceHash)
                        fail("SOURCE_CHANGED", "System frame private input changed")
                } finally { copied.close() }
            }
            verifySources()
            val process = ExternalProcess.run(command + listOf("--select-video-frame", inputPath.toString(), track.samples.size.toString(),
                rawSize.toString(), identity.size.toString(), selected.index.toString(), rawPath.toString()), 30_000, job.context)
            verifySources()
            when {
                process.cancelled -> fail("CANCELLED", "System frame cancelled", Stage.DecodeFrame)
                process.timedOut -> fail("BACKEND_TIMEOUT", "System frame timed out", Stage.DecodeFrame)
                process.outputLimited -> fail("RESOURCE_LIMIT_EXCEEDED", "System frame diagnostic budget exceeded", Stage.DecodeFrame)
                process.ioFailed -> fail("IO_READ_FAILED", "System frame diagnostics incomplete", Stage.DecodeFrame)
                process.code != 0 -> fail("DECODE_FAILED", "System frame could not prove the authorized pixel profile", Stage.DecodeFrame)
            }
            if (!Files.isRegularFile(rawPath) || Files.size(rawPath).toULong() != rawSize) fail("POSTCONDITION_FAILED", "System frame packed size differs", Stage.Verify)
            val bytes = Files.readAllBytes(rawPath)
            val trace = "WINDOWS_MEDIA_API_FRAME=SUCCESS frames=${track.samples.size} width=${track.width} height=${track.height} ptsSha256=$timeline index=${selected.index} time100ns=$actualTime bytes=$rawSize sha256=${hex(MessageDigest.getInstance("SHA-256").digest(bytes))} layout=packed-nv12"
            if (process.output.trim() != trace) fail("POSTCONDITION_FAILED", "System frame selection or full timeline differs", Stage.Verify)
            val image = WindowsFramePixels.image(bytes, track.width.toInt(), track.height.toInt()) { checkCancelled(job.context) }
            val encoded = object : ByteArrayOutputStream() {
                override fun write(b: Int) { if (count >= outputLimit) fail("RESOURCE_LIMIT_EXCEEDED", "JPEG exceeds output budget", Stage.EncodeImage); super.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { if (len > outputLimit - count) fail("RESOURCE_LIMIT_EXCEEDED", "JPEG exceeds output budget", Stage.EncodeImage); super.write(b, off, len) }
            }
            MemoryCacheImageOutputStream(encoded).use { stream ->
                if (!ImageIO.write(image, "jpeg", stream)) fail("ENCODE_FAILED", "Existing JDK JPEG encoder unavailable", Stage.EncodeImage)
                stream.flush()
            }
            val jpegBytes = encoded.toByteArray()
            // ImageIO.read(ImageInputStream) itself closes on success. Make the memory
            // stream's close idempotent so use also handles unsupported/exceptional reads.
            val imageInput = object : MemoryCacheImageInputStream(jpegBytes.inputStream()) {
                private var closed = false
                override fun close() { if (!closed) { super.close(); closed = true } }
            }
            val decoded = imageInput.use { ImageIO.read(it) }
                ?: fail("DECODE_FAILED", "Derived JPEG cannot be decoded", Stage.Verify)
            if (decoded.width != image.width || decoded.height != image.height) fail("POSTCONDITION_FAILED", "Derived JPEG dimensions differ", Stage.Verify)
            val staged = MemoryBinarySource(Bytes(jpegBytes), SourceId("system-derived-jpeg"))
            try {
                val jpegReader = BinaryReader(staged, job.context)
                val jpeg = JpegParser.parse(jpegReader).orThrow(); val facts = jpegFacts(jpegReader, jpeg)
                if (jpeg.trailing.length != 0uL || facts.width != track.width || facts.height != track.height || facts.issues.any { it.severity == Severity.Error })
                    fail("POSTCONDITION_FAILED", "System frame JPEG invalid", Stage.Verify)
                verifySources(); checkCancelled(job.context)
                val handle = job.destination.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg")).orThrow()
                try { copyRange(jpegReader, handle.sink, ByteRange(0uL, jpegBytes.size.toULong()), job.context).orThrow(); handle.sink.flush().orThrow() }
                finally { handle.sink.close().orThrow() }
                verifySources()
                BackendResult(listOf(StagedAsset(handle.id, AssetRole.PrimaryImage, "image/jpeg", jpegBytes.size.toULong())), listOf(facts),
                    listOf(Stage.DecodeFrame, Stage.EncodeImage).map { stage -> ExecutionRecord(stage, "windows-media-foundation",
                        if (stage == Stage.DecodeFrame) "Full presentation timeline and selected packed NV12 frame verified" else "Explicit BT.709 SDR to standard sRGB derived JPEG; source video unchanged",
                        false, false, false, videoFacts(video), facts) }, actualFrameTime = selected.time, actualFrameIndex = selected.index, actualFrameTrack = TrackId(track.trackId.toString()))
            } finally { staged.close() }
        } catch (fault: CoreFault) { throw fault }
        catch (failure: Exception) {
            val line = failure.stackTrace.firstOrNull { it.className.startsWith("livephoto.core.jvm.WindowsFrame") }?.lineNumber
            fail("IO_READ_FAILED", "System frame temporary media or image processing failed (${failure.javaClass.simpleName}, line=$line)", Stage.DecodeFrame)
        }
        finally {
            var failed = false
            for (owned in listOfNotNull(raw, input, directory)) try { Files.deleteIfExists(owned) } catch (_: Exception) { failed = true }
            if (failed) fail("IO_WRITE_FAILED", "System frame temporary cleanup failed", Stage.EncodeImage)
        }
    }
    private suspend fun verifyVisualMetadata(reader: BinaryReader, range: ByteRange, container: VideoContainer) {
        val boxes = BmffReader(reader)
        var children = boxes.readBoxes(range).orThrow()
        var depth = 0u
        for (name in listOf("moov", "trak", "mdia", "minf", "stbl")) {
            val box = children.single { it.type == name }
            children = boxes.readBoxes(box.payload, ++depth).orThrow()
        }
        val stsd = children.single { it.type == "stsd" }
        val entry = boxes.readBoxes(ByteRange(stsd.payload.offset + 8uL, stsd.payload.length - 8uL), ++depth).orThrow().single()
        val extras = boxes.readBoxes(ByteRange(entry.payload.offset + 78uL, entry.payload.length - 78uL), ++depth).orThrow()
        for (box in extras) {
            when (box.type) {
                "avcC", "btrt" -> Unit // already classified; bitrate hints cannot change pixel interpretation.
                "pasp" -> {
                    val bytes = reader.readExactly(box.payload.offset, checkedInt(box.payload.length).toUInt()).orThrow()
                    if (bytes.size != 8 || readUnsigned(bytes.slice(0, 4), Endian.Big) != 1uL || readUnsigned(bytes.slice(4), Endian.Big) != 1uL)
                        fail("CAPABILITY_UNSUPPORTED", "Container pixel aspect differs from the square-pixel SPS", Stage.Plan)
                }
                "colr" -> {
                    val bytes = reader.readExactly(box.payload.offset, checkedInt(box.payload.length).toUInt()).orThrow()
                    AvcSdrFrameProfile.verifyContainerColour(bytes, container).orThrow()
                }
                else -> fail("HDR_PRESERVATION_UNAVAILABLE", "System frame cannot discard aperture, field, HDR or auxiliary interpretation", Stage.Plan)
            }
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
