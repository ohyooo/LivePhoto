package livephoto.core.media

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class FfmpegTranscodeIntegrationTest {
    @Test fun explicitVfrVideoEncodingCopiesDistinctAacTracksWithSparseIdsAndAllOrders(): Unit = runMediaTest { host ->
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        fun path(name: String) = host.path(name)
        suspend fun prepare(arguments: List<String>) {
            val result = host.execute(listOf(host.executable, "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror") + arguments, 60_000L)
            assertEquals(0, result.code, result.output); assertFalse(result.ioFailed)
        }
        val video = path("VFR video.mp4")
        prepare(listOf("-f", "lavfi", "-i", "testsrc2=size=32x32:rate=25", "-frames:v", "8", "-vf",
            "setpts='if(lt(N,4),N,4+(N-4)*2)/(25*TB)',setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709",
            "-fps_mode", "passthrough", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30", "-bf", "0", "-g", "3", "-pix_fmt", "yuv420p",
            "-use_editlist", "0", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", video.toString()))
        val audio = listOf(path("mono44100.aac"), path("stereo48000.aac"))
        for ((index, file) in audio.withIndex()) prepare(listOf("-f", "lavfi", "-i", "sine=frequency=${440 + index * 440}:sample_rate=${if (index == 0) 44100 else 48000}:duration=0.52",
            "-c:a", "aac", "-b:a", if (index == 0) "64k" else "128k", "-ac", (index + 1).toString(), "-flags:a", "+bitexact", "-f", "adts", file.toString()))
        val tracks = listOf("0:v:0" to 17u, "1:a:0" to 4u, "2:a:0" to 29u)
        val inputs = listOf("-i", video.toString(), "-i", audio[0].toString(), "-i", audio[1].toString())
        val mux = listOf("-c", "copy", "-bsf:a", "aac_adtstoasc", "-use_editlist", "0", "-map_metadata", "-1", "-metadata:s:v", "encoder=",
            "-fflags", "+bitexact", "-write_btrt", "0", "-use_stream_ids_as_track_ids", "1")
        val orders = listOf(listOf(1, 0), listOf(0, 1, 2), listOf(1, 0, 2), listOf(2, 1, 0))
        val encoding = VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4)
        val core = DefaultLivePhotoCore(host.backend)
        for ((case, order) in orders.withIndex()) {
            val fixture = path("order-$case.mp4")
            prepare(inputs + order.flatMapIndexed { index, track -> listOf("-map", tracks[track].first, "-streamid", "$index:${tracks[track].second}") } + mux + fixture.toString())
            val source = host.source(fixture)
            try {
                val reader = BinaryReader(source, context)
                val before = BmffVideoProbe(reader).probe(ByteRange(0uL, source.size().orThrow())).orThrow()
                assertEquals(order.map { tracks[it].second }, before.tracks.map { it.trackId })
                assertTrue(before.tracks.single { it.handler == "vide" }.samples.map { it.duration }.distinct().size > 1)
                val hash = sha256Range(reader, before.range).orThrow()
                val result = core.transcode(TranscodeRequest(ResourceRef(SourceSet.Single(source)), encoding, MutationPolicy(transcode = TranscodePolicy.Explicit),
                    MemoryOutputTransaction(context, "real-aac-transcode-$case"), context)).orThrow()
                try {
                    val output = BinaryReader(result.output.assets.single().readableSource!!, context)
                    val after = BmffVideoProbe(output).probe(ByteRange(0uL, output.identity().orThrow().size)).orThrow()
                    verifyTranscode(reader, before, output, after, encoding)
                    assertEquals(order.map { tracks[it].second }, after.tracks.map { it.trackId })
                    assertNotEquals(before.tracks.single { it.handler == "vide" }.codecConfiguration, after.tracks.single { it.handler == "vide" }.codecConfiguration)
                    for (track in before.tracks.filter { it.handler == "soun" }) {
                        val actual = after.tracks.single { it.trackId == track.trackId }
                        assertEquals(track.codecConfiguration, actual.codecConfiguration)
                        assertEquals(track.audioActualSampleRate, actual.audioActualSampleRate)
                        assertEquals(track.audioActualChannelCount, actual.audioActualChannelCount)
                        assertEquals(track.samples.map { sha256Range(reader, it.range).orThrow() }, actual.samples.map { sha256Range(output, it.range).orThrow() })
                    }
                    assertEquals(GuaranteeOutcome.Changed, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
                    assertEquals(GuaranteeOutcome.Unknown, result.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
                    assertTrue(result.execution.any { it.stage == Stage.Transcode && it.transcoded && !it.remuxed && it.hardwareUsed == false })
                    assertTrue(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(output.source)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                } finally { result.output.assets.forEach { it.readableSource?.close() } }
                for ((policy, code) in listOf(MutationPolicy() to "TRANSCODE_NOT_AUTHORIZED", MutationPolicy(transcode = TranscodePolicy.Explicit, preservation = PreservationPolicy.Strict) to "PRESERVATION_REQUIREMENT_FAILED")) {
                    val tx = MemoryOutputTransaction(context, "aac-unauthorized-$case-$code")
                    assertEquals(code, assertIs<CoreResult.Failure>(core.transcode(TranscodeRequest(ResourceRef(SourceSet.Single(source)), encoding, policy, tx, context))).error.code.value)
                    assertTrue(tx.committedAssets().isEmpty())
                }
                val trimTx = MemoryOutputTransaction(context, "aac-trim-refused-$case")
                assertIs<CoreResult.Failure>(core.trim(TrimRequest(ResourceRef(SourceSet.Single(source)), TrimSpec(TimeRange(Time(40000, 1000000u), Time(400000, 1000000u)), TrimMode.Exact),
                    MutationPolicy(transcode = TranscodePolicy.Explicit), trimTx, context)))
                assertTrue(trimTx.committedAssets().isEmpty())
                assertEquals(hash, sha256Range(reader, before.range).orThrow())
            } finally { source.close() }
        }
        val excessive = path("three-audio.mp4")
        prepare(inputs + listOf(0, 1, 2, 1).flatMapIndexed { index, track -> listOf("-map", tracks[track].first, "-streamid", "$index:${index + 1}") } + mux + excessive.toString())
        val source = host.source(excessive)
        try {
            val tx = MemoryOutputTransaction(context, "three-audio-transcode-refused")
            assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(core.transcode(TranscodeRequest(ResourceRef(SourceSet.Single(source)), encoding,
                MutationPolicy(transcode = TranscodePolicy.Explicit), tx, context))).error.code.value)
            assertTrue(tx.committedAssets().isEmpty())
        } finally { source.close() }
    }
    @Test fun realExplicitAvcTranscodePreservesEveryVfrBoundaryAndRefusesUnknownColor(): Unit = runMediaTest { host ->
        val file = host.path("VFR source.mp4"); val unknown = host.path("unknown color.mp4")
        val privateSei = host.path("private SEI.mp4")
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        suspend fun generate(path: String, colors: Boolean) {
            val run = host.execute(listOf(host.executable, "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "8",
                "-vf", "setpts='if(lt(N,4),N,4+(N-4)*2)/(25*TB)'" + (if (colors) ",setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709" else ""),
                "-fps_mode", "passthrough", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30", "-bf", "0", "-g", "3", "-pix_fmt", "yuv420p", "-use_editlist", "0",
                "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", path.toString()), 60_000L)
            assertEquals(0, run.code, run.output); assertFalse(run.ioFailed)
        }
        generate(file, true); generate(unknown, false)
        val inserted = host.execute(listOf(host.executable, "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror", "-i", file.toString(), "-map", "0", "-c", "copy",
            "-bsf:v", "h264_metadata=sei_user_data=01234567-89ab-cdef-0123-456789abcdef+private-note", "-use_editlist", "0", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-write_btrt", "0", privateSei.toString()), 60_000L)
        assertEquals(0, inserted.code, inserted.output)
        val source = host.source(file); val reader = BinaryReader(source, context); val size = reader.identity().orThrow().size
        val original = BmffVideoProbe(reader).probe(ByteRange(0uL, size)).orThrow(); val hash = sha256Range(reader, ByteRange(0uL, size)).orThrow()
        assertTrue(original.tracks.single().samples.map { it.duration }.distinct().size > 1)
        val core = DefaultLivePhotoCore(host.backend); val tx = MemoryOutputTransaction(context, "real-transcode")
        val run = core.transcode(TranscodeRequest(ResourceRef(SourceSet.Single(source)), VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4), MutationPolicy(transcode = TranscodePolicy.Explicit), tx, context))
        val result = assertIs<CoreResult.Success<OperationResult>>(run, run.toString()).value
        try {
            val outputReader = BinaryReader(result.output.assets.single().readableSource!!, context)
            val actual = BmffVideoProbe(outputReader).probe(ByteRange(0uL, outputReader.identity().orThrow().size)).orThrow()
            verifyTranscode(reader, original, outputReader, actual, VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4))
            assertNotEquals(original.tracks.single().codecConfiguration, actual.tracks.single().codecConfiguration)
            assertEquals(GuaranteeOutcome.Changed, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
            assertTrue(result.execution.any { it.stage == Stage.Transcode && it.transcoded && it.hardwareUsed == false })
            assertTrue(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(result.output.assets.single().readableSource!!)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
        } finally { result.output.assets.forEach { it.readableSource?.close() } }
        assertEquals(hash, sha256Range(reader, ByteRange(0uL, size)).orThrow()); source.close()
        val badSource = host.source(unknown); val badTx = MemoryOutputTransaction(context, "unknown-color-transcode")
        try {
            assertEquals("HDR_PRESERVATION_UNAVAILABLE", assertIs<CoreResult.Failure>(core.transcode(TranscodeRequest(ResourceRef(SourceSet.Single(badSource)), VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4), MutationPolicy(transcode = TranscodePolicy.Explicit), badTx, context))).error.code.value)
            assertTrue(badTx.committedAssets().isEmpty())
        } finally { badSource.close() }
        val privateSource = host.source(privateSei); val privateTx = MemoryOutputTransaction(context, "private-sei-transcode")
        try {
            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(core.transcode(TranscodeRequest(ResourceRef(SourceSet.Single(privateSource)), VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4), MutationPolicy(transcode = TranscodePolicy.Explicit), privateTx, context))).error.code.value)
            assertTrue(privateTx.committedAssets().isEmpty())
        } finally { privateSource.close() }
    }
}
