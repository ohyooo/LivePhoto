package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.apple.AppleHeifFixtures
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.RangeSource
import livephoto.core.memory.MemoryOutputTransaction
import livephoto.core.memory.MemoryBinarySource
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
                val movPath = directory.resolve("explicit streamcopy fixture.mov"); owned.add(movPath)
                command(listOf("-i", video.toString(), "-map", "0:v:0", "-c", "copy", "-map_metadata", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-write_btrt", "0", movPath.toString()))
                val movInput = FileBinarySource(movPath)
                try {
                    val original = BinaryReader(movInput, context)
                    val facts = BmffVideoProbe(original).probe(ByteRange(0uL, movInput.size().orThrow())).orThrow()
                    assertEquals(VideoContainer.Mov, facts.container)
                    val before = sha256Range(original, facts.range).orThrow()
                    val liveMov = core.create(CreateRequest(frame.operation.output.assets.single().readableSource!!, movInput,
                        ProtocolSelector(ProtocolIds.GoogleV2), edits = EditSpec(keyPosition = CoverPosition.FrameIndex(1uL)),
                        policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = MemoryOutputTransaction(context, "apple-real-mov-live-source"), context = context)).orThrow()
                    try {
                        val converted = core.convert(ConvertRequest(SourceSet.Single(liveMov.output.assets.single().readableSource!!),
                            ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mov")), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
                            output = MemoryOutputTransaction(context, "apple-real-mov-convert"), context = context)).orThrow()
                        try {
                            assertEquals(VideoContainer.Mov, converted.output.assets[1].videoContainer)
                            assertTrue(converted.execution.none { it.transcoded || it.remuxed })
                            val pair = SourceSet.Pair(converted.output.assets[0].readableSource!!, converted.output.assets[1].readableSource!!)
                            assertEquals(0, core.inspect(ReadRequest(pair, context)).orThrow().keyPhoto.position!!.compareTo(Time(40, 1000u)))
                            val reader = BinaryReader(pair.video, context)
                            val convertedFacts = BmffVideoProbe(reader, allowTimedMetadata = true).probe(ByteRange(0uL, pair.video.size().orThrow())).orThrow()
                            RemuxVerification.verify(original, facts, reader, convertedFacts.copy(tracks = convertedFacts.tracks.filter { it.handler != "meta" }))
                            val path = directory.resolve("converted Apple MOV.mov"); owned.add(path)
                            save(pair.video, path); decode(path)
                            // Real encoded movie; the HEIC primary is explicitly synthetic framing, not a decoded camera photo.
                            val cid = core.inspect(ReadRequest(pair, context)).orThrow().pairing!!.imageIdentifier!!
                            val heic = MemoryBinarySource(Bytes(AppleHeifFixtures.image(cid, idat = true)), SourceId("real-movie-synthetic-heic"))
                            val heicBefore = sha256Range(BinaryReader(heic, context), ByteRange(0uL, heic.size().orThrow())).orThrow()
                            val keyed = core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Pair(heic, pair.video), CoverPosition.FrameIndex(0uL),
                                MutationPolicy(preservation = PreservationPolicy.Strict), MemoryOutputTransaction(context, "real-heic-mov-key"), context)).orThrow()
                            try {
                                assertEquals("image/heic", keyed.output.assets[0].mime)
                                assertEquals(heicBefore, sha256Range(BinaryReader(keyed.output.assets[0].readableSource!!, context), ByteRange(0uL, heic.size().orThrow())).orThrow())
                                assertEquals(Coverage.Partial, keyed.validation.coverage)
                                val keyPath = directory.resolve("keyed HEIC pair real movie.mov"); owned.add(keyPath)
                                save(keyed.output.assets[1].readableSource!!, keyPath); decode(keyPath)
                            } finally { keyed.output.assets.forEach { it.readableSource?.close() } }
                            val heicClean = core.split(SplitRequest(SourceSet.Pair(heic, pair.video), output = MemoryOutputTransaction(context, "real-heic-mov-clean"), context = context)).orThrow()
                            try {
                                assertEquals("image/heic", heicClean.output.assets[0].mime)
                                val detected = core.detect(ReadRequest(SourceSet.Single(heicClean.output.assets[0].readableSource!!), context)).orThrow()
                                assertEquals(Disposition.Unknown, detected.disposition); assertTrue(detected.matches.isEmpty())
                                val cleanPath = directory.resolve("clean HEIC pair real movie.mov"); owned.add(cleanPath)
                                save(heicClean.output.assets[1].readableSource!!, cleanPath); decode(cleanPath)
                                assertEquals(heicBefore, sha256Range(BinaryReader(heic, context), ByteRange(0uL, heic.size().orThrow())).orThrow())
                            } finally { heicClean.output.assets.forEach { it.readableSource?.close() }; heic.close() }
                            assertEquals(before, sha256Range(original, facts.range).orThrow())
                        } finally { converted.output.assets.forEach { it.readableSource?.close() } }
                    } finally { liveMov.output.assets.forEach { it.readableSource?.close() } }
                    val apple = core.create(CreateRequest(frame.operation.output.assets.single().readableSource!!, movInput,
                        ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mov")), edits = EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)),
                        policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = MemoryOutputTransaction(context, "apple-real-mov-create"), context = context)).orThrow()
                    try {
                        var pair = SourceSet.Pair(apple.output.assets[0].readableSource!!, apple.output.assets[1].readableSource!!)
                        val firstMovie = BinaryReader(pair.video, context)
                        val firstHash = sha256Range(firstMovie, ByteRange(0uL, pair.video.size().orThrow())).orThrow()
                        val derived = mutableListOf<OperationResult>()
                        try {
                            for (index in listOf(1uL, 0uL)) {
                                val changed = core.setKeyPhotoPosition(SetKeyRequest(pair, CoverPosition.FrameIndex(index), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
                                    output = MemoryOutputTransaction(context, "apple-real-mov-key-$index"), context = context)).orThrow()
                                derived += changed
                                assertEquals(VideoContainer.Mov, changed.output.assets[1].videoContainer)
                                pair = SourceSet.Pair(changed.output.assets[0].readableSource!!, changed.output.assets[1].readableSource!!)
                                val reader = BinaryReader(pair.video, context)
                                val changedFacts = BmffVideoProbe(reader, allowTimedMetadata = true).probe(ByteRange(0uL, pair.video.size().orThrow())).orThrow()
                                RemuxVerification.verify(original, facts, reader, changedFacts.copy(tracks = changedFacts.tracks.filter { it.handler != "meta" }))
                                val outputPath = directory.resolve("apple mov key $index.mov"); owned.add(outputPath)
                                save(pair.video, outputPath); decode(outputPath)
                            }
                            assertEquals(firstHash, sha256Range(BinaryReader(pair.video, context), ByteRange(0uL, pair.video.size().orThrow())).orThrow())
                            assertEquals(before, sha256Range(original, facts.range).orThrow())
                        } finally { derived.forEach { result -> result.output.assets.forEach { it.readableSource?.close() } } }
                    } finally { apple.output.assets.forEach { it.readableSource?.close() } }
                } finally { movInput.close() }
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
                            val changedKey = core.setKeyPhotoPosition(SetKeyRequest(createdPair, CoverPosition.FrameIndex(0uL),
                                policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = MemoryOutputTransaction(context, "apple-real-key-$index"), context = context)).orThrow()
                            try {
                                val keyMovie = BinaryReader(changedKey.output.assets[1].readableSource!!, context)
                                val keyFacts = BmffVideoProbe(keyMovie, allowTimedMetadata = true).probe(ByteRange(0uL, keyMovie.identity().orThrow().size)).orThrow()
                                RemuxVerification.verify(original, originalFacts, keyMovie, keyFacts.copy(tracks = keyFacts.tracks.filter { it.handler != "meta" }))
                                val keyPath = directory.resolve("apple changed key $index.mp4"); owned.add(keyPath)
                                save(keyMovie.source, keyPath); decode(keyPath)
                                val keyPair = SourceSet.Pair(changedKey.output.assets[0].readableSource!!, keyMovie.source)
                                assertEquals(0, core.inspect(ReadRequest(keyPair, context)).orThrow().keyPhoto.position!!.compareTo(Time.Zero))
                                val imageReader = BinaryReader(createdPair.image, context)
                                val outputImage = BinaryReader(keyPair.image, context)
                                assertEquals(sha256Range(imageReader, ByteRange(0uL, imageReader.identity().orThrow().size)).orThrow(),
                                    sha256Range(outputImage, ByteRange(0uL, outputImage.identity().orThrow().size)).orThrow())
                            } finally { changedKey.output.assets.forEach { it.readableSource?.close() } }
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
