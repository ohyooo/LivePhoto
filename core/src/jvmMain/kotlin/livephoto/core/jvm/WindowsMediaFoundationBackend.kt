package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.BmffVideoProbe
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Finite system media operations. All native access remains in a bounded child process. */
internal class WindowsMediaFoundationBackend(private val command: List<String>) : MediaBackend {
    override fun capabilities() = MediaCapabilities(listOf("windows-media-foundation"), listOf(
        CapabilityEntry(Operation.Probe, Implementation.Experimental, conditions = listOf(
            Condition(ConditionOperator.Equals, "decodeScope", Value.Text("one-mp4-or-mov-avc-video-no-audio-48x48-to-4096x2304-at-most-64-visible-unique-exact-100ns-frames")),
            Condition(ConditionOperator.Equals, "runtime", Value.Text("registered-software-avc-nv12-decoder-512MiB-isolated-process-per-input-format-verification-not-HDR-or-metadata-conformance"))))) +
        listOf(CapabilityEntry(Operation.ExtractFrame, Implementation.Experimental, conditions = listOf(
            Condition(ConditionOperator.Equals, "frameProfile", Value.Text("single-avc-baseline-main-high-proven-8bit-420-no-custom-scaling-no-audio-even-48-to-1024-at-most-64-exact-100ns-frames-bt709-limited-progressive-square-left-default-jpeg"))))) +
        listOf(CapabilityEntry(Operation.Remux, Implementation.Experimental, conditions = listOf(
            Condition(ConditionOperator.Equals, "remuxProfile", Value.Text("same-mp4-single-baseline-avc-no-audio-no-reorder-48-to-1024-at-most-64-exact-contiguous-100ns-frames-8MB-classified-source-envelope")),
            Condition(ConditionOperator.Equals, "verification", Value.Text("actual-os-compressed-packets-source-envelope-restored-all-samples-config-metadata-and-full-finite-os-decode-no-transcode"))))) +
        listOf(Operation.Trim, Operation.Transcode).map {
            CapabilityEntry(it, Implementation.Unsupported, reasons = listOf(IssueCode("CAPABILITY_UNSUPPORTED")))
        })

    override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = attempt {
        if (request.media.resourceId != null || request.media.snapshot != null)
            fail("INVALID_ARGUMENT", "Core must resolve system decoder resources", Stage.Validate)
        val source = (request.media.input as? SourceSet.Single)?.source
            ?: fail("INVALID_ARGUMENT", "System decoder requires one isolated media source", Stage.Validate)
        val reader = BinaryReader(source, request.context)
        val identity = reader.identity().orThrow()
        val facts = DefaultLivePhotoCore().probe(request.copy(decodeCheck = false)).orThrow()
        if (!request.decodeCheck) return@attempt facts
        if (facts.videoContainer !in setOf(VideoContainer.Mp4, VideoContainer.Mov) || facts.imageFormat != null)
            fail("CAPABILITY_UNSUPPORTED", "System decoder currently implements isolated bounded MP4/MOV video only", Stage.Validate)
        if (identity.size > minOf(128_000_000uL, request.context.limits.maxSpoolBytes))
            fail("RESOURCE_LIMIT_EXCEEDED", "System decoder input exceeds its temporary storage budget", Stage.Read)
        val range = ByteRange(0uL, identity.size)
        val structure = BmffVideoProbe(reader).probe(range).orThrow()
        val track = structure.tracks.singleOrNull()
            ?: fail("CAPABILITY_UNSUPPORTED", "System decoder cannot silently omit audio or other tracks", Stage.Validate)
        if (track.handler != "vide" || track.codec != VideoCodec.Avc || track.sampleEntry != "avc1" ||
            track.width == null || track.height == null || track.width !in 48u..4096u || track.height !in 48u..2304u ||
            track.samples.size !in 1..64 || track.samples.any { it.presentationTime < 0 } ||
            track.samples.map { it.presentationTime }.distinct().size != track.samples.size)
            fail("CAPABILITY_UNSUPPORTED", "Input is outside the finite system AVC decoder profile", Stage.Validate)
        val expected = MessageDigest.getInstance("SHA-256")
        track.samples.sortedBy { it.presentationTime }.forEach {
            if (it.presentationTime > Long.MAX_VALUE / 10_000_000L)
                fail("CAPABILITY_UNSUPPORTED", "System decoder PTS exceeds its exact signed time domain", Stage.Validate)
            val product = it.presentationTime * 10_000_000L
            if (product % track.timescale.toLong() != 0L)
                fail("CAPABILITY_UNSUPPORTED", "System decoder requires exact 100ns presentation times", Stage.Validate)
            expected.update(ByteBuffer.allocate(8).putLong(product / track.timescale.toLong()).array())
        }
        val digest = expected.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val sourceHash = sha256Range(reader, range).orThrow()
        var directory: Path? = null; var file: Path? = null
        try {
            directory = Files.createTempDirectory("livephoto-windows-probe-")
            val inputFile = directory.resolve("private media.mp4")
            file = inputFile
            Files.newOutputStream(inputFile).use { stream ->
                var offset = 0uL
                while (offset < identity.size) {
                    val bytes = reader.readBuffer(offset, minOf(65_536uL, identity.size - offset).toUInt()).orThrow()
                    stream.write(bytes.toByteArray()); offset += bytes.size.toULong()
                }
            }
            reader.validateIdentity().orThrow()
            suspend fun verifyPrivateCopy() {
                val copied = FileBinarySource(inputFile)
                try {
                    val privateReader = BinaryReader(copied, request.context)
                    if (copied.size().orThrow() != identity.size || sha256Range(privateReader, range).orThrow() != sourceHash)
                        fail("SOURCE_CHANGED", "System decoder private copy differs from the immutable source", Stage.Validate)
                } finally { copied.close() }
            }
            verifyPrivateCopy()
            val process = ExternalProcess.run(command + listOf("--decode-video", inputFile.toString(), track.samples.size.toString(),
                "32000000", "128000000"), 30_000, request.context)
            reader.validateIdentity().orThrow()
            when {
                process.cancelled -> fail("CANCELLED", "System decoder cancelled", Stage.Validate)
                process.timedOut -> fail("BACKEND_TIMEOUT", "System decoder exceeded its runtime limit", Stage.Validate)
                process.outputLimited -> fail("RESOURCE_LIMIT_EXCEEDED", "System decoder diagnostics exceeded their budget", Stage.Validate)
                process.ioFailed -> fail("IO_READ_FAILED", "System decoder diagnostics could not be completely drained", Stage.Validate)
                process.code != 0 -> fail("DECODE_FAILED", "System decoder did not complete the selected video", Stage.Validate)
            }
            val trace = "WINDOWS_MEDIA_API_DECODE=SUCCESS scope=selected-avc-video frames=${track.samples.size} width=${track.width} height=${track.height} ptsSha256=$digest"
            if (process.output.trim() != trace)
                fail("POSTCONDITION_FAILED", "System decoder frame count, dimensions or full timeline differ from independent structure", Stage.Validate)
            verifyPrivateCopy()
            if (sha256Range(reader, range).orThrow() != sourceHash)
                fail("SOURCE_CHANGED", "System decoder input changed during validation", Stage.Validate)
            facts.copy(coverage = Coverage.Partial, issues = facts.issues +
                Issue(IssueCode("MEDIA_DECODE_COMPLETED"), Severity.Info, Layer.Media))
        } catch (fault: CoreFault) { throw fault }
        catch (_: Exception) { fail("IO_READ_FAILED", "System decoder private media IO failed", Stage.Read) }
        finally {
            var failed = false
            for (owned in listOfNotNull(file, directory)) try { Files.deleteIfExists(owned) } catch (_: Exception) { failed = true }
            if (failed) fail("IO_WRITE_FAILED", "System decoder temporary cleanup failed", Stage.Read)
        }
    }
    private fun unsupported(job: BackendJob, expected: Operation): CoreResult<BackendResult> {
        if (job.operation != expected) return CoreResult.Failure(CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Plan, "Backend method and operation differ"))
        return when (val result = job.validate()) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "This system media operation is not implemented"))
        }
    }
    override suspend fun trim(job: BackendJob) = unsupported(job, Operation.Trim)
    override suspend fun remux(job: BackendJob) = WindowsRemux.run(command, job)
    override suspend fun transcode(job: BackendJob) = unsupported(job, Operation.Transcode)
    override suspend fun extractFrame(job: BackendJob) = WindowsFrame.run(command, job)

    companion object {
        private val installed: List<MediaBackend> by lazy {
            if (!System.getProperty("os.name").startsWith("Windows") || System.getProperty("os.arch") !in setOf("amd64", "x86_64")) emptyList()
            else try {
                val core = Path.of(WindowsMediaApiWorker::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
                // jpackage app-image's fixed sibling layout, not a PATH/CWD executable search.
                val helper = core.takeIf { it.fileName.toString() == "core-jvm.jar" && it.parent.fileName.toString() == "app" }
                    ?.parent?.parent?.resolve("WindowsMediaHelper.exe")
                val command = if (helper != null && Files.isRegularFile(helper) && Files.isExecutable(helper)) listOf(helper.toRealPath().toString())
                else {
                    val javaPath = Path.of(System.getProperty("java.home"), "bin", "java.exe").toRealPath()
                    if (!Files.isRegularFile(javaPath) || !Files.isExecutable(javaPath)) return@lazy emptyList()
                    val classes = listOf(core.toString(), Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toRealPath().toString()).distinct()
                    listOf(javaPath.toString(), "-Xms16m", "-Xmx128m", "--enable-native-access=ALL-UNNAMED", "-cp",
                        classes.joinToString(File.pathSeparator), "livephoto.core.jvm.WindowsMediaApiWorker")
                }
                val result = ExternalProcess.run(command + "--decoder-preflight", 15_000)
                if (result.code == 0 && !result.timedOut && !result.ioFailed && !result.outputLimited && !result.cancelled &&
                    result.output.trim().matches(Regex("WINDOWS_MEDIA_API_DECODER=SUCCESS scope=registered-software-avc-to-nv12 candidates=([1-9]|[1-9][0-9]|1[01][0-9]|12[0-8])")))
                    listOf(WindowsMediaFoundationBackend(command)) else emptyList()
            } catch (_: Exception) { emptyList() }
        }
        fun available(): List<MediaBackend> = installed
    }
}
