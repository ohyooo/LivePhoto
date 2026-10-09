package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.BmffVideoProbe
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/** OS decoder evidence, not a vendor/device fixture or public backend coverage claim. */
class WindowsNativeVideoDecodeTest {
    private fun worker(args: List<String>): ProcessResult {
        val javaPath = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val classes = listOf(WindowsMediaApiWorker::class.java, Unit::class.java).map {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }.distinct()
        return ExternalProcess.run(listOf(javaPath.toString(), "-Xms16m", "-Xmx128m", "--enable-native-access=ALL-UNNAMED", "-cp",
            classes.joinToString(File.pathSeparator), "livephoto.core.jvm.WindowsMediaApiWorker") + args, 30_000)
    }
    private fun usable(): Boolean {
        org.junit.Assume.assumeTrue("Retained desktop system API acceptance is disabled", System.getenv("LIVEPHOTO_REQUIRE_WINDOWS_MEDIA") == "true")
        val result = worker(listOf("--decoder-preflight"))
        assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
        if (System.getenv("LIVEPHOTO_REQUIRE_WINDOWS_MEDIA") == "true") assertEquals(0, result.code, result.output)
        if (result.code == 3) { assertTrue(result.output.contains("UNAVAILABLE")); return false }
        assertEquals(0, result.code, result.output)
        return true
    }
    @Test fun invalidDecodeLimitsAreRejectedBeforeAnyNativeAccess() {
        for (args in listOf(listOf("--decode-video"), listOf("--decode-video", "relative.mp4", "1", "2048", "128000000"),
            listOf("--decode-video", Path.of(System.getProperty("java.home")).toString(), "0", "2048", "128000000"))) {
            val result = worker(args)
            assertEquals(2, result.code, result.output)
            assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
        }
    }
    @Test fun actualDecodedBufferPayloadIsReadWithinBoundsWithoutClaimingFrameExtraction(): Unit = runImmediate {
        if (!usable()) return@runImmediate
        val path = Files.createTempFile("livephoto-os-payload-", ".mp4")
        try {
            for (variant in listOf("b0", "b2")) {
                val bytes = WindowsEncodedFixtures.bytes(variant)
                Files.write(path, bytes)
                val result = worker(listOf("--decode-video-payload", path.toString(), "4", "65536", "128000000"))
                assertEquals(0, result.code, result.output)
                assertFalse(result.ioFailed || result.timedOut || result.outputLimited)
                val trace = Regex("WINDOWS_MEDIA_API_DECODE=SUCCESS scope=selected-avc-video frames=4 width=64 height=64 ptsSha256=[0-9a-f]{64} payloadBytes=([0-9]+) payloadSha256=([0-9a-f]{64})")
                    .matchEntire(result.output.trim()) ?: error("Missing bounded raw-buffer evidence: ${result.output}")
                assertTrue(trace.groupValues[1].toLong() in 24_576L..262_144L)
                assertNotEquals(MessageDigest.getInstance("SHA-256").digest().joinToString("") { "%02x".format(it.toInt() and 255) }, trace.groupValues[2])
                assertContentEquals(bytes, Files.readAllBytes(path))
                val bounded = worker(listOf("--decode-video-payload", path.toString(), "4", "6143", "128000000"))
                assertEquals(4, bounded.code, bounded.output)
                assertFalse(bounded.output.contains("DECODE=SUCCESS"))
                assertContentEquals(bytes, Files.readAllBytes(path))
            }
            // Payload evidence remains separate from the gated public frame operation.
            assertEquals(Implementation.Experimental, WindowsMediaFoundationBackend.available().single().capabilities().operations.single { it.operation == Operation.ExtractFrame }.implementation)
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun actualAvcDecodeReachesEosWithIndependentPresentationTimeline(): Unit = runImmediate {
        if (!usable()) return@runImmediate // Explicit unavailable evidence, not a claimed decoder success.
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath)
        val fixtureEncoder = found.ffmpegPath.takeUnless { System.getenv("LIVEPHOTO_WINDOWS_FIXTURE_ONLY") == "true" }
        val directory = Files.createTempDirectory("livephoto-os-decode-")
        val owned = mutableListOf<Path>()
        try {
            for (bFrames in listOf(0, 2)) {
                val path = directory.resolve("avc B $bFrames.mp4"); owned.add(path)
                if (fixtureEncoder == null) Files.write(path, WindowsEncodedFixtures.bytes("b$bFrames")) else {
                val encoded = ExternalProcess.run(listOf(fixtureEncoder.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error",
                    "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "4", "-c:v", "libx264", "-preset", "medium",
                    "-bf", bFrames.toString(), "-g", "4", "-pix_fmt", "yuv420p", path.toString()), 60_000)
                assertEquals(0, encoded.code, encoded.output)
                assertFalse(encoded.ioFailed || encoded.timedOut || encoded.outputLimited)
                }
                val source = FileBinarySource(path)
                val context = Context(Limits(128_000_000uL, 128_000_000uL))
                val reader = BinaryReader(source, context)
                val range = ByteRange(0uL, source.size().orThrow())
                try {
                    val before = sha256Range(reader, range).orThrow()
                    val facts = BmffVideoProbe(reader).probe(range).orThrow()
                    val track = facts.tracks.single()
                    assertEquals(VideoCodec.Avc, track.codec)
                    val digest = MessageDigest.getInstance("SHA-256")
                    track.samples.sortedBy { it.presentationTime }.forEach {
                        val scaled = Math.multiplyExact(it.presentationTime, 10_000_000L)
                        assertEquals(0L, scaled % track.timescale.toLong())
                        digest.update(ByteBuffer.allocate(8).putLong(scaled / track.timescale.toLong()).array())
                    }
                    val expected = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    val result = worker(listOf("--decode-video", path.toString(), "4", "65536", "128000000"))
                    assertEquals(0, result.code, result.output)
                    assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
                    assertTrue(result.output.contains("DECODE=SUCCESS scope=selected-avc-video frames=4 width=64 height=64 ptsSha256=$expected"), result.output)
                    val bounded = worker(listOf("--decode-video", path.toString(), "1", "65536", "128000000"))
                    assertEquals(4, bounded.code, bounded.output)
                    assertTrue(bounded.output.contains("DECODE=FAILED")); assertFalse(bounded.output.contains("DECODE=SUCCESS"))
                    assertFalse(bounded.timedOut || bounded.ioFailed || bounded.outputLimited)
                    assertEquals(before, sha256Range(reader, range).orThrow())
                    val systems = WindowsMediaFoundationBackend.available()
                    assertEquals(1, systems.size, "Actual registered OS AVC/NV12 decoder is required for this runtime")
                    val discovery = JvmMediaBackends.discover(null, "", true, systems) { error("No FFmpeg or PATH lookup is authorized") }
                    assertNull(discovery.ffmpegPath)
                    assertEquals(listOf("windows-media-foundation"), discovery.backend!!.capabilities().backendIds)
                    assertFalse(discovery.issues.any { it.code.value == "MEDIA_BACKEND_UNAVAILABLE" })
                    val decoded = DefaultLivePhotoCore(discovery.backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(source)), true, context)).orThrow()
                    assertEquals(Coverage.Partial, decoded.coverage)
                    assertTrue(decoded.issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                    assertEquals(before, sha256Range(reader, range).orThrow())
                    if (fixtureEncoder != null) {
                        // Explicit fixture muxing, outside the OS backend. No FFmpeg in the decode route.
                        val movPath = directory.resolve("actual muxed B $bFrames.mov"); owned.add(movPath)
                        val muxed = ExternalProcess.run(listOf(fixtureEncoder.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                            "-i", path.toString(), "-map", "0:v:0", "-c", "copy", "-map_metadata", "-1", "-f", "mov", movPath.toString()), 60_000)
                        assertEquals(0, muxed.code, muxed.output); assertFalse(muxed.timedOut || muxed.ioFailed || muxed.outputLimited)
                        val movSource = FileBinarySource(movPath)
                        try {
                            val movReader = BinaryReader(movSource, context)
                            val movRange = ByteRange(0uL, movSource.size().orThrow())
                            val movBefore = sha256Range(movReader, movRange).orThrow()
                            val movFacts = BmffVideoProbe(movReader).probe(movRange).orThrow()
                            assertEquals(VideoContainer.Mov, movFacts.container)
                            val movTrack = movFacts.tracks.single()
                            assertEquals(track.samples.size, movTrack.samples.size)
                            assertEquals(track.samples.map { sha256Range(reader, it.range).orThrow() },
                                movTrack.samples.map { sha256Range(movReader, it.range).orThrow() })
                            val movDecoded = DefaultLivePhotoCore(discovery.backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(movSource)), true, context)).orThrow()
                            assertEquals(VideoContainer.Mov, movDecoded.videoContainer); assertEquals(Coverage.Partial, movDecoded.coverage)
                            assertTrue(movDecoded.issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                            assertEquals(movBefore, sha256Range(movReader, movRange).orThrow())
                        } finally { movSource.close() }
                    }
                } finally { source.close() }
            }
            if (fixtureEncoder != null) {
            val small = directory.resolve("below OS decoder minimum.mp4"); owned.add(small)
            val encoded = ExternalProcess.run(listOf(fixtureEncoder.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=32x32:rate=25", "-frames:v", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p", small.toString()), 60_000)
            assertEquals(0, encoded.code, encoded.output)
            assertFalse(encoded.ioFailed || encoded.timedOut || encoded.outputLimited)
            val smallBytes = Files.readAllBytes(small)
            val rejected = worker(listOf("--decode-video", small.toString(), "4", "65536", "128000000"))
            assertEquals(4, rejected.code, rejected.output); assertFalse(rejected.output.contains("DECODE=SUCCESS"))
            assertFalse(rejected.ioFailed || rejected.timedOut || rejected.outputLimited)
            assertContentEquals(smallBytes, Files.readAllBytes(small))
            val source = FileBinarySource(small)
            try {
                val rejectedProbe = DefaultLivePhotoCore(WindowsMediaFoundationBackend.available().single()).probe(
                    ProbeRequest(ResourceRef(SourceSet.Single(source)), true, Context(Limits(128_000_000uL, 128_000_000uL))))
                assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(rejectedProbe).error.code.value)
            } finally { source.close() }
            }
        } finally { owned.forEach { Files.deleteIfExists(it) }; Files.deleteIfExists(directory) }
    }
    @Test fun malformedLocalMediaNeverReportsDecodeSuccess() {
        if (!usable()) return
        val path = Files.createTempFile("livephoto-invalid-os-", ".mp4")
        try {
            Files.write(path, byteArrayOf(1, 2, 3, 4))
            val result = worker(listOf("--decode-video", path.toString(), "4", "65536", "128000000"))
            assertEquals(4, result.code, result.output)
            assertTrue(result.output.contains("DECODE=FAILED")); assertFalse(result.output.contains("DECODE=SUCCESS"))
            assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(path))
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun actualSystemMovProbePreservesBFramesVfrAndRejectsAudio(): Unit = runImmediate {
        if (!usable()) return@runImmediate
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        val core = DefaultLivePhotoCore(WindowsMediaFoundationBackend.available().single())
        for (name in listOf("b0", "b2", "vfr", "audio")) {
            val bytes = WindowsEncodedFixtures.movBytes(name)
            val source = livephoto.core.memory.MemoryBinarySource(Bytes(bytes), SourceId("system-mov-$name"))
            val original = livephoto.core.memory.MemoryBinarySource(Bytes(WindowsEncodedFixtures.bytes(name)), SourceId("system-mp4-$name"))
            try {
                val reader = BinaryReader(source, context)
                val range = ByteRange(0uL, bytes.size.toULong())
                val before = sha256Range(reader, range).orThrow()
                val movie = BmffVideoProbe(reader).probe(range).orThrow()
                val mp4 = BmffVideoProbe(BinaryReader(original, context)).probe(ByteRange(0uL, original.size().orThrow())).orThrow()
                assertEquals(VideoContainer.Mov, movie.container)
                assertEquals(mp4.tracks, movie.tracks)
                val result = core.probe(ProbeRequest(ResourceRef(SourceSet.Single(source)), true, context))
                if (name == "audio") assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(result).error.code.value)
                else {
                    val decoded = result.orThrow()
                    assertEquals(VideoContainer.Mov, decoded.videoContainer); assertEquals(Coverage.Partial, decoded.coverage)
                    assertTrue(decoded.issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                }
                assertEquals(before, sha256Range(reader, range).orThrow())
            } finally { source.close(); original.close() }
        }
    }
    @Test fun actualSystemProbeKeepsVfrPresentationTimesAndRejectsAudioRatherThanDroppingIt(): Unit = runImmediate {
        if (!usable()) return@runImmediate
        val ffmpeg = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of)).ffmpegPath
            .takeUnless { System.getenv("LIVEPHOTO_WINDOWS_FIXTURE_ONLY") == "true" }
        val directory = Files.createTempDirectory("livephoto-os-vfr-")
        val video = directory.resolve("explicit VFR.mp4")
        val audioVideo = directory.resolve("explicit VFR plus AAC.mp4")
        val elementary = directory.resolve("explicit canonical AAC.aac")
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        fun encode(args: List<String>) {
            val result = ExternalProcess.run(listOf(ffmpeg.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror") + args, 60_000)
            assertEquals(0, result.code, result.output); assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
        }
        try {
            if (ffmpeg == null) Files.write(video, WindowsEncodedFixtures.bytes("vfr")) else encode(listOf("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "8", "-vf",
                "setpts='if(lt(N,4),N,4+(N-4)*2)/(25*TB)'", "-fps_mode", "passthrough", "-c:v", "libx264", "-preset", "ultrafast",
                "-bf", "0", "-g", "3", "-pix_fmt", "yuv420p", "-use_editlist", "0", video.toString()))
            val backend = WindowsMediaFoundationBackend.available().single()
            val source = FileBinarySource(video)
            try {
                val reader = BinaryReader(source, context)
                val range = ByteRange(0uL, source.size().orThrow())
                val before = sha256Range(reader, range).orThrow()
                val track = BmffVideoProbe(reader).probe(range).orThrow().tracks.single()
                assertEquals(8, track.samples.size)
                assertTrue(track.samples.map { it.duration }.distinct().size > 1)
                val result = DefaultLivePhotoCore(backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(source)), true, context)).orThrow()
                assertEquals(Coverage.Partial, result.coverage)
                assertTrue(result.issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                assertEquals(before, sha256Range(reader, range).orThrow())
            } finally { source.close() }
            if (ffmpeg == null) Files.write(audioVideo, WindowsEncodedFixtures.bytes("audio")) else {
                encode(listOf("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.48", "-c:a", "aac", "-b:a", "96k",
                    "-ac", "2", "-flags:a", "+bitexact", "-f", "adts", elementary.toString()))
                encode(listOf("-i", video.toString(), "-i", elementary.toString(), "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-bsf:a", "aac_adtstoasc", audioVideo.toString()))
            }
            val withAudio = FileBinarySource(audioVideo)
            try {
                val reader = BinaryReader(withAudio, context)
                val range = ByteRange(0uL, withAudio.size().orThrow())
                val before = sha256Range(reader, range).orThrow()
                val tracks = BmffVideoProbe(reader).probe(range).orThrow().tracks
                assertEquals(2, tracks.size); assertTrue(tracks.any { it.handler == "soun" })
                val result = DefaultLivePhotoCore(backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(withAudio)), true, context))
                assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(result).error.code.value)
                assertEquals(before, sha256Range(reader, range).orThrow())
            } finally { withAudio.close() }
        } finally { Files.deleteIfExists(audioVideo); Files.deleteIfExists(elementary); Files.deleteIfExists(video); Files.deleteIfExists(directory) }
    }
}
