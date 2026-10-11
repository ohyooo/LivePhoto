package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic policy and hostile-backend evidence, separate from real FFmpeg media tests. */
class AudioTrimOperationsTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun groups(count: UInt = 3u, firstUnassigned: Boolean = false): ByteArray = GoogleFixtures.box("sgpd",
        byteArrayOf(1, 0, 0, 0) + "roll".encodeToByteArray() + GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u) + byteArrayOf(-1, -1)) +
        GoogleFixtures.fullBox("sbgp", "roll".encodeToByteArray() + GoogleFixtures.u32(if (firstUnassigned) 2u else 1u) +
            (if (firstUnassigned) GoogleFixtures.u32(1u) + GoogleFixtures.u32(0u) else byteArrayOf()) +
            GoogleFixtures.u32(if (firstUnassigned) count - 1u else count) + GoogleFixtures.u32(1u))
    private fun bytes(groups: ByteArray = groups()) = GoogleFixtures.video(aac = true, audioSampleCount = 3u, audioGroups = groups).bytes
    private fun source(bytes: ByteArray = bytes()) = MemoryBinarySource(Bytes(bytes), SourceId("audio-trim-synthetic"))
    private fun request(output: OutputTransaction, source: BinarySource = source(), spec: TrimSpec = TrimSpec(TimeRange(Time.Zero, Time(80, 1000u)))) =
        TrimRequest(ResourceRef(SourceSet.Single(source)), spec, output = output, context = context)

    @Test fun unchangedAllTrackCopyIsIndependentlyVerifiedAndAudioRangeIsDisclosed(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "audio-whole")
        val result = DefaultLivePhotoCore(Backend(bytes())).trim(request(output).copy(policy = MutationPolicy(preservation = PreservationPolicy.Strict))).orThrow()
        try {
            assertEquals(listOf("1", "2"), result.tracks.map { it.trackId.value })
            assertEquals(0, result.tracks[1].encoded.end.compareTo(Time(3072, 48000u)))
            assertEquals(result.tracks[1].encoded, result.tracks[1].presented)
            assertTrue(result.wasBitstreamPreserved); assertFalse(result.wasTranscoded); assertFalse(result.retainedHiddenContent)
            assertEquals(GuaranteeOutcome.Verified, result.operation.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
        } finally { result.operation.output.assets.forEach { it.readableSource?.close() } }
    }

    @Test fun planningReturnsWholePacketsAndExactModesCannotReachBackend(): Unit = runImmediate {
        val source = source(); val reader = BinaryReader(source, context)
        val video = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
        val spec = TrimSpec(TimeRange(Time.Zero, Time(40, 1000u)))
        val plan = planLosslessTrim(reader, video, spec)
        assertEquals(1, plan.audio.single().samples.size)
        assertEquals(48000u, plan.movieTimescale)
        assertEquals(0, plan.trackTrims[1].encoded.end.compareTo(Time(1024, 48000u)))
        assertTrue(plan.issues.any { it.code.value == "AUDIO_PACKET_BOUNDARY_GAP" })
        for (mode in listOf(TrimMode.Exact, TrimMode.LosslessOnly)) {
            val backend = Backend(bytes()); val output = MemoryOutputTransaction(context, "audio-exact-$mode")
            val result = DefaultLivePhotoCore(backend).trim(request(output, source, spec.copy(mode = mode)).copy(policy = MutationPolicy(transcode = TranscodePolicy.Explicit)))
            assertEquals(if (mode == TrimMode.Exact) "EXACT_TRIM_UNAVAILABLE" else "LOSSLESS_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(result).error.code.value)
            assertEquals(0, backend.calls); assertTrue(output.committedAssets().isEmpty())
        }
    }

    @Test fun missingAndNoncanonicalRecoveryMapsAreNotSilentlyAddedOrChanged(): Unit = runImmediate {
        for (group in listOf(byteArrayOf(), groups(firstUnassigned = true))) {
            val backend = Backend(bytes(group)); val output = MemoryOutputTransaction(context, "audio-groups-${group.size}")
            val result = DefaultLivePhotoCore(backend).trim(request(output, source(bytes(group))))
            assertEquals("LOSSLESS_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(result).error.code.value)
            assertEquals(0, backend.calls); assertTrue(output.committedAssets().isEmpty())
        }
    }

    @Test fun droppedAudioFalseRangeAndChangedAudioPayloadAbortStaging(): Unit = runImmediate {
        for (wrong in listOf("drop", "range", "bytes")) {
            val output = MemoryOutputTransaction(context, "audio-hostile-$wrong")
            val result = DefaultLivePhotoCore(Backend(bytes(), wrong)).trim(request(output))
            assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(result).error.code.value)
            assertTrue(output.committedAssets().isEmpty()); assertEquals(TransactionState.Aborted, output.query().orThrow().state)
        }
    }

    @Test fun primingEditsAndUnrepresentableTicksAreRejectedBeforeProcessing(): Unit = runImmediate {
        val reader = BinaryReader(source(), context)
        val video = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
        val audio = video.tracks[1]
        val edited = video.copy(tracks = listOf(video.tracks[0], audio.copy(edit = VideoEdit(1000u, 0uL, 64uL, 1024))))
        val result = attempt { planLosslessTrim(reader, edited, TrimSpec(TimeRange(Time.Zero, Time(80, 1000u)))) }
        assertEquals("LOSSLESS_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(result).error.code.value)
        assertEquals("VALUE_NOT_REPRESENTABLE", assertIs<CoreResult.Failure>(attempt { trimTicks(Time(1, 30000u), 44100u) }).error.code.value)
    }

    private inner class Backend(private val bytes: ByteArray, private val wrong: String = "") : MediaBackend {
        var calls = 0
        override fun capabilities() = MediaCapabilities(listOf("synthetic-audio-trim"), listOf(CapabilityEntry(Operation.Trim, Implementation.Experimental)))
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = attempt {
            calls++
            val original = (job.inputs.single().input as SourceSet.Single).source
            val reader = BinaryReader(original, context)
            val video = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            val plan = planLosslessTrim(reader, video, job.trim!!)
            val changed = bytes.copyOf()
            if (wrong == "bytes") changed[video.tracks[1].samples.first().range.offset.toInt()] = 0xff.toByte()
            val facts = videoFacts(video)
            val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = facts.mime!!)).orThrow()
            try { BinaryWriter(handle.sink, context).writeAll(Bytes(changed)).orThrow() }
            finally { handle.sink.close().orThrow() }
            val tracks = when (wrong) {
                "drop" -> plan.trackTrims.take(1)
                "range" -> plan.trackTrims.map { it.copy(encoded = TimeRange(Time.Zero, Time.Zero)) }
                else -> plan.trackTrims
            }
            BackendResult(listOf(livephoto.core.StagedAsset(handle.id, AssetRole.MotionVideo, facts.mime, bytes.size.toULong())), listOf(facts),
                listOf(ExecutionRecord(Stage.Trim, "synthetic-audio-trim", "Synthetic hostile-backend test only", false, true, false, facts, facts)),
                tracks = tracks, timelineMap = plan.mapping)
        }
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Unexpected probe")
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = error("Unexpected remux")
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = error("Unexpected transcode")
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = error("Unexpected frame")
    }
}
