package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.memory.*
import kotlin.test.*

class AppleCidEvidenceTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))

    @Test fun formallyParsedCidEvidenceIsStableRoleBoundAndIndependentOfCandidateOrder(): Unit = runImmediate {
        for (heic in listOf(false, true)) {
            val image = source(if (heic) AppleHeifFixtures.image(idat = true) else AppleFixtures.image(), "image")
            val video = source(AppleFixtures.movie(), "movie")
            val singleImage = core.inspect(ReadRequest(SourceSet.Single(image), context)).orThrow()
            val singleMovie = core.inspect(ReadRequest(SourceSet.Single(video), context)).orThrow()
            assertEquals(Disposition.Candidate, singleImage.detection.disposition)
            val expected = listOf(singleImage.pairing!!.evidence.single(), singleMovie.pairing!!.evidence.single())
            assertNotEquals(expected[0].id, expected[1].id)
            for (input in listOf(SourceSet.Pair(image, video), SourceSet.Candidates(listOf(video, image)))) {
                val inspection = core.inspect(ReadRequest(input, context)).orThrow()
                assertEquals(expected, inspection.pairing!!.evidence); assertEquals(expected, inspection.detection.matches.single().evidence)
                for (evidence in expected) {
                    assertEquals(EvidenceKind.Inspection, evidence.kind); assertNull(evidence.reference)
                    assertTrue(evidence.description.contains("does not prove a shared capture"))
                    val location = evidence.location!!
                    val field = inspection.metadata.single { it.location.range == location.range && it.location.source == location.source && it.owner == Ownership.SourceProtocol }
                    assertEquals(Value.Text(AppleFixtures.ID), field.value)
                }
            }
        }
    }

    @Test fun providerGenerationIdentityRoleAndPhysicalFieldEachBindEvidenceWithoutLeakingTokens(): Unit = runImmediate {
        val original = TestSource(AppleHeifFixtures.image())
        val first = core.inspect(ReadRequest(SourceSet.Single(original), context)).orThrow().pairing!!.evidence.single()
        original.currentIdentity = original.currentIdentity.copy(generation = GenerationToken("private-generation-token"))
        val next = core.inspect(ReadRequest(SourceSet.Single(original), context)).orThrow().pairing!!.evidence.single()
        assertNotEquals(first.id, next.id); assertFalse(next.id.value.contains("private-generation-token")); assertFalse(next.description.contains("private-generation-token"))
        assertEquals(next, core.inspect(ReadRequest(SourceSet.Single(original), context)).orThrow().pairing!!.evidence.single())
        val other = source(AppleHeifFixtures.image(), "different-source")
        assertNotEquals(next.id, core.inspect(ReadRequest(SourceSet.Single(other), context)).orThrow().pairing!!.evidence.single().id)
        val reader = BinaryReader(other, context)
        val range = ByteRange(0uL, 1uL)
        val a = AppleCidEvidence.parsed(reader, "image", AppleFixtures.ID, range, ParseBudget(context))
        assertNotEquals(a.id, AppleCidEvidence.parsed(reader, "video", AppleFixtures.ID, range, ParseBudget(context)).id)
        assertNotEquals(a.id, AppleCidEvidence.parsed(reader, "image", AppleFixtures.ID, ByteRange(1uL, 1uL), ParseBudget(context)).id)
        assertFalse(original.closed)
    }

    @Test fun publishedSetKeyPairKeepsCidSemanticsButIssuesFreshInspectionEvidence(): Unit = runImmediate {
        val input = AppleHeifKeyFixtures.pair(context, mov = true)
        val before = core.inspect(ReadRequest(input, context)).orThrow()
        val changed = core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(1uL), output = MemoryOutputTransaction(context, "cid-evidence-publication"), context = context)).orThrow()
        try {
            val after = core.inspect(ReadRequest(SourceSet.Pair(changed.output.assets[0].readableSource!!, changed.output.assets[1].readableSource!!), context)).orThrow()
            val oldPair = before.pairing!!; val newPair = after.pairing!!
            assertEquals(oldPair.copy(evidence = emptyList()), newPair.copy(evidence = emptyList()))
            for ((left, right) in oldPair.evidence.zip(newPair.evidence)) {
                assertNotEquals(left.id, right.id); assertNotEquals(left.location!!.source, right.location!!.source)
            }
        } finally { changed.output.assets.forEach { it.readableSource?.close() } }
    }
}
