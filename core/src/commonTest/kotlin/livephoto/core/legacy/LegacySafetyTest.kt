package livephoto.core.legacy

import livephoto.core.*
import livephoto.core.binary.TestSource
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

class LegacySafetyTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 2_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val google = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg"))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun pair(fixture: LegacyFixtures.Pair) = SourceSet.Pair(source(fixture.image, "image"), source(fixture.video, "video"))

    @Test
    fun unknownLegacyTimeConvertsToUnspecifiedTargetFieldInsteadOfInventedZero(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair()
        val output = MemoryOutputTransaction(context, "legacy-unknown-key")
        value(core.convert(ConvertRequest(pair(fixture), google, output = output, context = context)))
        val result = value(core.inspect(ReadRequest(SourceSet.Single(source(output.committedAssets().values.single().toByteArray(), "converted")), context)))
        assertEquals(listOf(Value.Text("-1")), result.keyPhoto.rawFields.map { it.rawValue })
        assertEquals(KeySource.DerivedDefault, result.keyPhoto.source)
        assertEquals(Time(40, 1000u), result.keyPhoto.position)
    }

    @Test
    fun unknownJsonPropertiesStayReadableAndRawButCannotBeDiscardedByClean(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair(imageJson = LegacyFixtures.json("pair-A", ",\"ordinary-rating\":5"))
        val input = pair(fixture)
        val inspection = value(core.inspect(ReadRequest(input, context)))
        assertEquals(Ownership.Unknown, inspection.metadata.single { it.selector == "ordinary-rating" }.owner)
        val raw = MemoryOutputTransaction(context, "legacy-unknown-json-raw")
        value(core.split(SplitRequest(input, SplitMode.Raw, output = raw, context = context)))
        assertEquals(setOf(Bytes(fixture.image), Bytes(fixture.video)), raw.committedAssets().values.toSet())
        for (convert in listOf(false, true)) {
            val output = MemoryOutputTransaction(context, "legacy-unknown-json-$convert")
            val result = if (convert) core.convert(ConvertRequest(input, google, output = output, context = context))
                else core.split(SplitRequest(input, output = output, context = context))
            assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(result).error.code)
            assertTrue(output.committedAssets().isEmpty())
        }
    }

    @Test
    fun convertRechecksOriginalVideoIdentityBeforeSingleFinalCommit(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair()
        val image = TestSource(fixture.image)
        val video = TestSource(fixture.video).also { it.currentIdentity = it.currentIdentity.copy(id = SourceId("original-video")) }
        val transaction = MemoryOutputTransaction(context, "legacy-convert-source-change")
        val changed = object : OutputTransaction by transaction {
            override suspend fun prepare(): CoreResult<Unit> {
                video.currentIdentity = video.currentIdentity.copy(generation = GenerationToken("changed-during-staging"))
                return transaction.prepare()
            }
        }
        val result = core.convert(ConvertRequest(SourceSet.Pair(image, video), google, output = changed, context = context))
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(result).error.code)
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
        assertTrue(transaction.committedAssets().isEmpty())
        assertFalse(image.closed); assertFalse(video.closed)
    }

    @Test
    fun sameTargetCopyDoesNotPublishUnselectedCandidateCarriers(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair()
        val unmatched = LegacyFixtures.pair(id = "unmatched")
        val input = SourceSet.Candidates(listOf(source(fixture.image, "matched-image"), source(fixture.video, "matched-video"), source(unmatched.image, "unmatched-image")))
        val output = MemoryOutputTransaction(context, "legacy-same-target")
        val result = value(core.convert(ConvertRequest(input, ProtocolSelector(ProtocolIds.VivoLegacy, ProfileId("pair")), output = output, context = context)))
        assertEquals(2, result.output.assets.size)
        assertEquals(setOf(Bytes(fixture.image), Bytes(fixture.video)), output.committedAssets().values.toSet())
    }

    @Test
    fun plansRejectSharedAliasesAndRetainOriginalConversionSnapshot(): Unit = runImmediate {
        val fusion = SourceSet.Single(source(LegacyFixtures.fusion(), "fusion"))
        val inspected = value(core.inspect(ReadRequest(fusion, context)))
        val aliases = inspected.layout.resources.filter { it.kind == ResourceKind.Video }.map { it.id }
        val output = MemoryOutputTransaction(context, "legacy-alias-plan")
        assertEquals(IssueCode("INVALID_ARGUMENT"), assertIs<CoreResult.Failure>(core.plan(ExtractRequest(fusion, aliases, output = output, context = context))).error.code)
        val input = pair(LegacyFixtures.pair())
        val original = value(core.inspect(ReadRequest(input, context)))
        val plan = value(core.plan(ConvertRequest(input, google, output = output, context = context)))
        assertEquals(original.snapshot, plan.snapshot)
        assertEquals(TransactionState.Open, value(output.query()).state)
        assertTrue(value(output.query()).assetIds.isEmpty())
    }

    @Test
    fun fusionAuthorityConflictsAndUnknownVersionBlockCleanAndConvert(): Unit = runImmediate {
        val cases = listOf(
            "<v:VMotionPhotoVersion xmlns:v='http://ns.vivo.com/photos/1.0/camera/'>1</v:VMotionPhotoVersion>",
            "<o:VideoLength xmlns:o='http://ns.oplus.com/photos/1.0/camera/'>0</o:VideoLength>",
            "<l:Protocol xmlns:l='https://github.com/LengxiQwQ/live-photo-box'>other</l:Protocol>",
            "<l:Protocol xmlns:l='https://github.com/LengxiQwQ/live-photo-box'>MotionPhotoFusion</l:Protocol>",
            "<g:MicroVideo xmlns:g='http://ns.google.com/photos/1.0/camera/'>1</g:MicroVideo><g:MicroVideoVersion xmlns:g='http://ns.google.com/photos/1.0/camera/'>1</g:MicroVideoVersion><g:MicroVideoOffset xmlns:g='http://ns.google.com/photos/1.0/camera/'>${GoogleFixtures.video().bytes.size}</g:MicroVideoOffset>")
        for ((index, extra) in cases.withIndex()) {
            val input = SourceSet.Single(source(LegacyFixtures.fusion(ordinaryXmp = extra), "fusion-$index"))
            val output = MemoryOutputTransaction(context, "fusion-conflicting-authority-$index")
            assertIs<CoreResult.Failure>(core.convert(ConvertRequest(input, google, output = output, context = context)))
            assertTrue(output.committedAssets().isEmpty())
        }
    }

    @Test
    fun pairCleanStrictDoesNotClaimUnknownPrivateAssociationsPreserved(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "pair-strict-metadata")
        val result = core.split(SplitRequest(pair(LegacyFixtures.pair()), policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = output, context = context))
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(result).error.code)
        assertTrue(output.committedAssets().isEmpty())
        assertEquals(TransactionState.Aborted, value(output.query()).state)
    }

    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
