package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.memory.*
import kotlin.test.*

class AppleAssemblyTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL, maxMetadataBytes = 8_000_000uL))
    private fun source(bytes: ByteArray, id: String = "apple-assembly-input") = MemoryBinarySource(Bytes(bytes), SourceId(id))

    @Test fun newExifUsesFormalTag17AndCannotReplaceExistingOrUnprovedExif(): Unit = runImmediate {
        val input = source(GoogleFixtures.jpeg())
        val session = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
        val proof = AppleImagePatch.create(session.reader, AppleFixtures.ID, ParseBudget(context)).orThrow()
        val app = JpegRewrite.appSegment(0xe1, proof.payload).orThrow()
        val patch = JpegPatch(ByteRange(2uL, 0uL), app, appleProof = proof)
        val plan = JpegRewrite.plan(session.jpeg!!, listOf(patch)).orThrow()
        val projected = JpegProjectionSource.create(session, plan).orThrow()
        val reader = BinaryReader(projected, context)
        val jpeg = JpegParser.parse(reader, ParseBudget(context)).orThrow()
        assertEquals(AppleFixtures.ID, AppleImageReader.read(reader, jpeg, ParseBudget(context)).orThrow()?.value)
        assertEquals(codingDigest(session), codingDigest(SourceSession.open(SourceSet.Single(projected), context, ParseBudget(context)).orThrow()))
        assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(JpegRewrite.plan(session.jpeg, listOf(patch.copy(appleProof = null)))).error.code.value)
        val existing = BinaryReader(source(AppleFixtures.image()), context)
        assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(AppleImagePatch.create(existing, AppleFixtures.ID, ParseBudget(context))).error.code.value)
        assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(JpegRewrite.plan(jpeg, listOf(patch))).error.code.value)
        assertEquals("INVALID_PAIR_IDENTIFIER", assertIs<CoreResult.Failure>(AppleImagePatch.create(session.reader, "not-a-uuid", ParseBudget(context))).error.code.value)
    }

    @Test fun movieAppendPreservesEveryRetainedTrackAtBothKeyPositionsIncludingAudio(): Unit = runImmediate {
        for (audio in listOf(false, true)) for (time in listOf(Time.Zero, Time(40, 1000u))) {
            val input = source(GoogleFixtures.video(aac = audio).bytes)
            val reader = BinaryReader(input, context)
            val before = sha256Range(reader, ByteRange(0uL, input.size().orThrow())).orThrow()
            val plan = AppleMovieAssembler.prepare(reader, AppleFixtures.ID, time, ParseBudget(context)).orThrow()
            val output = MemoryOutputTransaction(context, "apple-primitives-$audio-${time.value}")
            val handle = output.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4")).orThrow()
            plan.write(BinaryWriter(handle.sink, context)); handle.sink.flush().orThrow(); handle.sink.close().orThrow()
            output.prepare().orThrow()
            val result = output.openStaged(handle.id).orThrow()
            try {
                val staged = BinaryReader(result, context)
                plan.verify(staged)
                val facts = BmffVideoProbe(staged, allowTimedMetadata = true).probe(ByteRange(0uL, staged.identity().orThrow().size)).orThrow()
                val key = AppleVideoReader.key(staged, facts, ParseBudget(context)).orThrow()
                assertEquals(0, key.position?.compareTo(time))
                assertEquals("0", (key.rawFields.single().rawValue as Value.Number).decimal)
                assertEquals(if (audio) 3 else 2, facts.tracks.size)
                assertEquals(plan.media.tracks, facts.tracks.filter { it.handler != "meta" })
                assertEquals(before, sha256Range(reader, plan.media.range).orThrow())
            } finally { result.close(); output.abort().orThrow() }
        }
    }

    @Test fun unknownMetadataAndNonrepresentableOrOutOfRangeKeysAreNotGuessed(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for ((key, code) in listOf(Time(1, 3000u) to "CAPABILITY_UNSUPPORTED", Time(80, 1000u) to "INVALID_PRESENTATION_TIMESTAMP", Time(-1, 1000u) to "INVALID_PRESENTATION_TIMESTAMP")) {
            val result = AppleMovieAssembler.prepare(BinaryReader(source(video), context), AppleFixtures.ID, key, ParseBudget(context))
            assertEquals(code, assertIs<CoreResult.Failure>(result).error.code.value)
        }
        val unknown = video + GoogleFixtures.box("uuid", ByteArray(16))
        assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(AppleMovieAssembler.prepare(BinaryReader(source(unknown), context), AppleFixtures.ID, Time.Zero, ParseBudget(context))).error.code.value)
        val bound = Context(Limits(1024uL, 1024uL, maxMetadataBytes = 1024uL))
        val limited = AppleMovieAssembler.prepare(BinaryReader(source(video), bound), AppleFixtures.ID, Time.Zero, ParseBudget(bound))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", assertIs<CoreResult.Failure>(limited).error.code.value)
    }
}
