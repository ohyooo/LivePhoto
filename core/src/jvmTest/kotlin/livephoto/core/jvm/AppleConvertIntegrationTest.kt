package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.RangeSource
import livephoto.core.memory.MemoryOutputTransaction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Existing optional FFmpeg, synthetic encodes explicitly outside the forbidden-encoding conversion. */
class AppleConvertIntegrationTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    @Test fun realApplePairAndGoogleRoundtripDecodeAllOriginalVideoAndAudio(): Unit = runImmediate {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath, "Required FFmpeg is absent")
        assumeTrue("Existing FFmpeg is absent; actual Apple decode was not run", found.ffmpegPath != null)
        val core = DefaultLivePhotoCore(found.backend)
        val directory = Files.createTempDirectory("livephoto-apple-real-")
        val video = directory.resolve("explicit encoded video.mp4")
        val audio = directory.resolve("explicit encoded audio.aac")
        val muxed = directory.resolve("audio first mp4.mp4")
        val owned = mutableListOf(video, audio, muxed)
        suspend fun command(args: List<String>) {
            val result = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror") + args, 60_000L)
            assertEquals(0, result.code, result.output); assertFalse(result.ioFailed || result.timedOut || result.outputLimited)
        }
        suspend fun decode(path: Path) = command(listOf("-err_detect", "explode", "-f", "mov", "-i", path.toString(), "-map", "0:v", "-map", "0:a?", "-sn", "-dn", "-f", "null", "-"))
        suspend fun save(source: BinarySource, path: Path) {
            val reader = BinaryReader(source, context)
            val size = reader.identity().orThrow().size
            Files.newOutputStream(path).use { stream ->
                var offset = 0uL
                while (offset < size) {
                    val bytes = reader.readBuffer(offset, minOf(65_536uL, size - offset).toUInt()).orThrow()
                    stream.write(bytes.toByteArray()); offset += bytes.size.toULong()
                }
            }
            reader.validateIdentity().orThrow()
        }
        try {
            command(listOf("-f", "lavfi", "-i", "testsrc2=size=32x32:rate=25", "-frames:v", "4", "-vf", "setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709", "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "2", "-pix_fmt", "yuv420p",
                "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", video.toString()))
            command(listOf("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.16", "-c:a", "aac", "-b:a", "96k", "-ac", "2", "-flags:a", "+bitexact", "-f", "adts", audio.toString()))
            command(listOf("-i", video.toString(), "-i", audio.toString(), "-map", "1:a:0", "-map", "0:v:0", "-c", "copy", "-bsf:a", "aac_adtstoasc",
                "-streamid", "0:4", "-streamid", "1:17", "-use_stream_ids_as_track_ids", "1", "-map_metadata", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-write_btrt", "0", muxed.toString()))
            val plainVideo = FileBinarySource(video)
            val frame = try { core.extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(plainVideo)), CoverPosition.FrameIndex(1uL), ImageEncoding(ImageFormat.Jpeg), MemoryOutputTransaction(context, "apple-real-frame"), context)).orThrow() }
                finally { plainVideo.close() }
            try {
                for ((index, inputPath) in listOf(video, muxed).withIndex()) {
                    val input = FileBinarySource(inputPath)
                    try {
                        val original = BinaryReader(input, context)
                        val originalFacts = BmffVideoProbe(original).probe(ByteRange(0uL, original.identity().orThrow().size)).orThrow()
                        val before = sha256Range(original, originalFacts.range).orThrow()
                        val createdApple = core.create(CreateRequest(frame.operation.output.assets.single().readableSource!!, input,
                            ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4")), edits = EditSpec(keyPosition = CoverPosition.FrameIndex(1uL)),
                            policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = MemoryOutputTransaction(context, "apple-real-create-$index"), context = context)).orThrow()
                        try {
                            val createdMovie = BinaryReader(createdApple.output.assets.single { it.role == AssetRole.MotionVideo }.readableSource!!, context)
                            val createdFacts = BmffVideoProbe(createdMovie, allowTimedMetadata = true).probe(ByteRange(0uL, createdMovie.identity().orThrow().size)).orThrow()
                            RemuxVerification.verify(original, originalFacts, createdMovie, createdFacts.copy(tracks = createdFacts.tracks.filter { it.handler != "meta" }))
                            val createdPath = directory.resolve("created apple movie $index.mp4"); owned.add(createdPath)
                            save(createdMovie.source, createdPath); decode(createdPath)
                            val createdPair = SourceSet.Pair(createdApple.output.assets[0].readableSource!!, createdMovie.source)
                            assertEquals(0, core.inspect(ReadRequest(createdPair, context)).orThrow().keyPhoto.position!!.compareTo(Time(40, 1000u)))
                            assertTrue(createdApple.execution.none { it.transcoded || it.remuxed })
                        } finally { createdApple.output.assets.forEach { it.readableSource?.close() } }
                        val carrier = core.create(CreateRequest(frame.operation.output.assets.single().readableSource!!, input, ProtocolSelector(ProtocolIds.GoogleV2),
                            edits = EditSpec(keyPosition = CoverPosition.FrameIndex(1uL)), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
                            output = MemoryOutputTransaction(context, "apple-real-carrier-$index"), context = context)).orThrow()
                        try {
                            val apple = core.convert(ConvertRequest(SourceSet.Single(carrier.output.assets.single().readableSource!!), ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4")),
                                policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = MemoryOutputTransaction(context, "apple-real-pair-$index"), context = context)).orThrow()
                            try {
                                assertTrue(apple.execution.none { it.transcoded })
                                val movie = BinaryReader(apple.output.assets.single { it.role == AssetRole.MotionVideo }.readableSource!!, context)
                                val facts = BmffVideoProbe(movie, allowTimedMetadata = true).probe(ByteRange(0uL, movie.identity().orThrow().size)).orThrow()
                                RemuxVerification.verify(original, originalFacts, movie, facts.copy(tracks = facts.tracks.filter { it.handler != "meta" }))
                                assertEquals(originalFacts.tracks.map { it.trackId }, facts.tracks.filter { it.handler != "meta" }.map { it.trackId })
                                assertEquals(1, BmffReader(movie).readBoxes(facts.range).orThrow().count { it.type == "mdat" })
                                val outputPath = directory.resolve("apple movie $index.mp4"); owned.add(outputPath)
                                save(movie.source, outputPath); decode(outputPath)
                                val pair = SourceSet.Pair(apple.output.assets[0].readableSource!!, movie.source)
                                val roundtrip = core.convert(ConvertRequest(pair, ProtocolSelector(ProtocolIds.GoogleV2), output = MemoryOutputTransaction(context, "apple-real-roundtrip-$index"), context = context)).orThrow()
                                try {
                                    val live = roundtrip.output.assets.single().readableSource!!
                                    val inspected = core.inspect(ReadRequest(SourceSet.Single(live), context)).orThrow()
                                    assertEquals(0, inspected.keyPhoto.position?.compareTo(Time(40, 1000u)))
                                    val extent = inspected.layout.resources.single { it.kind == ResourceKind.Video }.extents.single().range
                                    val videoSource = RangeSource(BinaryReader(live, context), extent)
                                    val roundtripReader = BinaryReader(videoSource, context)
                                    val roundtripFacts = BmffVideoProbe(roundtripReader).probe(ByteRange(0uL, extent.length)).orThrow()
                                    RemuxVerification.verify(original, originalFacts, roundtripReader, roundtripFacts)
                                    val roundtripPath = directory.resolve("roundtrip movie $index.mp4"); owned.add(roundtripPath)
                                    save(videoSource, roundtripPath); decode(roundtripPath)
                                } finally { roundtrip.output.assets.forEach { it.readableSource?.close() } }
                                assertEquals(before, sha256Range(original, originalFacts.range).orThrow())
                            } finally { apple.output.assets.forEach { it.readableSource?.close() } }
                        } finally { carrier.output.assets.forEach { it.readableSource?.close() } }
                    } finally { input.close() }
                }
            } finally { frame.operation.output.assets.forEach { it.readableSource?.close() } }
        } finally { owned.asReversed().forEach { Files.deleteIfExists(it) }; Files.delete(directory) }
    }
}
