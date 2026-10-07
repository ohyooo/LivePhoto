package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.heif.*
import livephoto.core.memory.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Real decoder evidence for a controlled encoded fixture, not device compatibility or a public HEIC writer. */
class HeifDecodePreservationIntegrationTest {
    private val context = Context(Limits(16_000_000uL, 16_000_000uL))
    @Test fun realHevcPrimaryStillDecodesIdenticallyAfterBoundedMetaExpansion(): Unit = runImmediate {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath, "Required FFmpeg is absent")
        assumeTrue("No existing FFmpeg; HEIF decoder evidence was not run", found.ffmpegPath != null)
        val directory = Files.createTempDirectory("livephoto-heif-decode-")
        val movie = directory.resolve("controlled synthetic primary.mp4")
        val owned = mutableListOf(movie)
        suspend fun decode(path: Path): String {
            val result = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror",
                "-i", path.toString(), "-map", "0:v:0", "-frames:v", "1", "-f", "framemd5", "-"), 60_000L)
            assertEquals(0, result.code, result.output); assertFalse(result.ioFailed)
            assertTrue(result.output.contains("#dimensions 0: 32x32"), result.output)
            val frames = result.output.lineSequence().map(String::trim).filter { it.matches(Regex("[0-9]+,.*")) }.toList()
            assertEquals(1, frames.size, result.output)
            return frames.single().substringAfterLast(',').trim().also { assertEquals(32, it.length) }
        }
        try {
            // Explicit synthetic fixture encoding only. Expansion itself never invokes an encoder or alters NAL/config bytes.
            // Disable encoder informational SEI: private/SEI data intentionally remains outside relocation authority.
            val prepared = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                "-f", "lavfi", "-i", "color=c=black:s=32x32:r=25", "-frames:v", "1", "-c:v", "libx265", "-preset", "ultrafast",
                "-x265-params", "log-level=error:info=0:repeat-headers=0:pools=none:frame-threads=1:wpp=0:bframes=0:keyint=1",
                "-pix_fmt", "yuv420p", "-tag:v", "hvc1", "-map_metadata", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-write_btrt", "0", movie.toString()), 60_000L)
            assertEquals(0, prepared.code, prepared.output); assertFalse(prepared.ioFailed)
            val originalDecode = decode(movie)
            val source = FileBinarySource(movie)
            val sample: Bytes
            val configuration: Bytes
            try {
                val reader = BinaryReader(source, context)
                val video = BmffVideoProbe(reader).probe(ByteRange(0uL, source.size().orThrow())).orThrow()
                val track = video.tracks.single { it.handler == "vide" }
                assertEquals(VideoCodec.Hevc, track.codec)
                assertEquals(1, track.samples.size)
                val range = track.samples.single().range
                sample = reader.readExactly(range.offset, range.length.toUInt()).orThrow()
                configuration = track.codecConfiguration
            } finally { source.close() }
            for ((index, layout) in listOf(Triple(false, false, false), Triple(true, false, false), Triple(false, true, false), Triple(false, false, true)).withIndex()) {
                val bytes = HeifFixtures.plain(metaLast = layout.first, extended = layout.second, idat = layout.third,
                    codedSample = sample.toByteArray(), codecConfiguration = configuration.toByteArray(), width = 32u, height = 32u)
                val inputPath = directory.resolve("primary $index.heic"); owned.add(inputPath); Files.write(inputPath, bytes)
                assertEquals(originalDecode, decode(inputPath))
                val input = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("real-heif-$index")), context)
                val decoded = DefaultLivePhotoCore(found.backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(input.source)), true, context)).orThrow()
                assertEquals(ImageFormat.Heic, decoded.imageFormat)
                assertEquals(Coverage.Partial, decoded.coverage)
                assertTrue(decoded.issues.any { it.code == IssueCode("MEDIA_DECODE_COMPLETED") })
                val before = sha256Range(input, ByteRange(0uL, bytes.size.toULong())).orThrow()
                val plan = HeifMetaExpansion.prepare(input, 32u).orThrow()
                val tx = MemoryOutputTransaction(context, "real-heif-expansion-$index")
                val handle = tx.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/heic")).orThrow()
                plan.write(input, BinaryWriter(handle.sink, context)).orThrow(); handle.sink.close().orThrow(); tx.prepare().orThrow()
                val staged = tx.openStaged(handle.id).orThrow()
                val after = BinaryReader(staged, context)
                plan.verify(input, after).orThrow()
                val result = after.readExactly(0uL, staged.size().orThrow().toUInt()).orThrow()
                staged.close(); tx.abort().orThrow() // Do not pretend this internal proof is a public publication.
                val outputPath = directory.resolve("expanded $index.heic"); owned.add(outputPath); Files.write(outputPath, result.toByteArray())
                assertEquals(originalDecode, decode(outputPath))
                val xmp = Bytes("<r:RDF xmlns:r='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><r:Description/></r:RDF>".encodeToByteArray())
                val metadataPlan = HeifXmpAppender.prepare(input, xmp).orThrow()
                val metadataTx = MemoryOutputTransaction(context, "real-heif-xmp-$index")
                val metadataHandle = metadataTx.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/heic")).orThrow()
                metadataPlan.write(input, BinaryWriter(metadataHandle.sink, context)).orThrow()
                metadataHandle.sink.close().orThrow(); metadataTx.prepare().orThrow()
                val metadataSource = metadataTx.openStaged(metadataHandle.id).orThrow()
                val metadataReader = BinaryReader(metadataSource, context)
                metadataPlan.verify(input, metadataReader).orThrow()
                val metadataBytes = metadataReader.readExactly(0uL, metadataSource.size().orThrow().toUInt()).orThrow()
                metadataSource.close(); metadataTx.abort().orThrow()
                val metadataPath = directory.resolve("with XMP $index.heic"); owned.add(metadataPath); Files.write(metadataPath, metadataBytes.toByteArray())
                assertEquals(originalDecode, decode(metadataPath))
                val createdTx = MemoryOutputTransaction(context, "real-heif-public-create-$index")
                val motionSource = FileBinarySource(movie)
                try {
                    val created = DefaultLivePhotoCore().create(CreateRequest(input.source, motionSource,
                        ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic")), output = createdTx, context = context)).orThrow()
                    assertEquals(Coverage.Partial, created.validation.coverage)
                    val compositeBytes = createdTx.committedAssets().values.single()
                    val compositePath = directory.resolve("public motion $index.heic"); owned.add(compositePath); Files.write(compositePath, compositeBytes.toByteArray())
                    assertEquals(originalDecode, decode(compositePath))
                    val composite = SourceSet.Single(MemoryBinarySource(compositeBytes, SourceId("real-motion-heic-$index")))
                    val extract = MemoryOutputTransaction(context, "real-heif-public-extract-$index")
                    DefaultLivePhotoCore().extract(ExtractRequest(composite, emptyList(), output = extract, context = context)).orThrow()
                    val extractedBytes = extract.committedAssets().values.single()
                    assertContentEquals(Files.readAllBytes(movie), extractedBytes.toByteArray())
                    val extractedPath = directory.resolve("raw motion $index.mp4"); owned.add(extractedPath); Files.write(extractedPath, extractedBytes.toByteArray())
                    assertEquals(originalDecode, decode(extractedPath))
                    val split = MemoryOutputTransaction(context, "real-heif-public-clean-$index")
                    val clean = DefaultLivePhotoCore().split(SplitRequest(composite, output = split, context = context)).orThrow()
                    val cleanImage = split.committedAssets().getValue(clean.output.assets.single { it.role == AssetRole.PrimaryImage }.id)
                    val cleanVideo = split.committedAssets().getValue(clean.output.assets.single { it.role == AssetRole.MotionVideo }.id)
                    assertEquals(extractedBytes, cleanVideo)
                    val cleanImagePath = directory.resolve("clean primary $index.heic"); owned.add(cleanImagePath); Files.write(cleanImagePath, cleanImage.toByteArray())
                    assertEquals(originalDecode, decode(cleanImagePath))
                    val normalizedTx = MemoryOutputTransaction(context, "real-heif-public-normalize-$index")
                    DefaultLivePhotoCore().convert(ConvertRequest(composite,
                        ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic")), sameTarget = SameTargetPolicy.Normalize,
                        policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = normalizedTx, context = context)).orThrow()
                    val normalizedBytes = normalizedTx.committedAssets().values.single()
                    val normalizedPath = directory.resolve("normalized motion $index.heic"); owned.add(normalizedPath); Files.write(normalizedPath, normalizedBytes.toByteArray())
                    assertEquals(originalDecode, decode(normalizedPath))
                    val normalized = SourceSet.Single(MemoryBinarySource(normalizedBytes, SourceId("real-normalized-heic-$index")))
                    assertEquals(DefaultLivePhotoCore().getKeyPhotoPosition(ReadRequest(composite, context)).orThrow().position,
                        DefaultLivePhotoCore().getKeyPhotoPosition(ReadRequest(normalized, context)).orThrow().position)
                    val normalizedRaw = MemoryOutputTransaction(context, "real-heif-normalized-extract-$index")
                    DefaultLivePhotoCore().extract(ExtractRequest(normalized, emptyList(), output = normalizedRaw, context = context)).orThrow()
                    assertEquals(extractedBytes, normalizedRaw.committedAssets().values.single())
                    val keyTx = MemoryOutputTransaction(context, "real-heif-public-set-key-$index")
                    val keyResult = DefaultLivePhotoCore().setKeyPhotoPosition(SetKeyRequest(composite, CoverPosition.FrameIndex(0uL),
                        policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = keyTx, context = context)).orThrow()
                    assertEquals(Time(0, 1_000_000u), keyResult.keyPhoto?.position)
                    val keyBytes = keyTx.committedAssets().values.single()
                    val keyPath = directory.resolve("updated key $index.heic"); owned.add(keyPath); Files.write(keyPath, keyBytes.toByteArray())
                    assertEquals(originalDecode, decode(keyPath))
                    val keyRaw = MemoryOutputTransaction(context, "real-heif-key-extract-$index")
                    DefaultLivePhotoCore().extract(ExtractRequest(SourceSet.Single(MemoryBinarySource(keyBytes, SourceId("real-key-heic-$index"))),
                        emptyList(), output = keyRaw, context = context)).orThrow()
                    assertEquals(extractedBytes, keyRaw.committedAssets().values.single())
                } finally { motionSource.close() }
                val stagedDecode = DefaultLivePhotoCore(found.backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(MemoryBinarySource(result, SourceId("heif-expanded-decode-$index")))), true, context)).orThrow()
                assertTrue(stagedDecode.issues.any { it.code == IssueCode("MEDIA_DECODE_COMPLETED") })
                if (index == 0) {
                    val wrongDimensions = HeifFixtures.plain(codedSample = sample.toByteArray(), codecConfiguration = configuration.toByteArray(), width = 31u, height = 32u)
                    val wrong = DefaultLivePhotoCore(found.backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(MemoryBinarySource(Bytes(wrongDimensions), SourceId("heif-wrong-ispe")))), true, context))
                    assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(wrong).error.code)
                    val oversized = HeifFixtures.plain(codedSample = sample.toByteArray(), codecConfiguration = configuration.toByteArray(), width = 5000u, height = 32u)
                    val refused = DefaultLivePhotoCore(found.backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(MemoryBinarySource(Bytes(oversized), SourceId("heif-oversized-ispe")))), true, context))
                    assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(refused).error.code)
                }
                assertEquals(before, sha256Range(input, ByteRange(0uL, bytes.size.toULong())).orThrow())
                val report = DefaultLivePhotoCore().validateMedia(ValidationRequest(SourceSet.Single(input.source), context = context)).orThrow()
                assertEquals(Coverage.NotRun, report.checks.single { it.id == "media.decode" }.coverage)
            }
        } finally { owned.asReversed().forEach { Files.deleteIfExists(it) }; Files.delete(directory) }
    }
}
