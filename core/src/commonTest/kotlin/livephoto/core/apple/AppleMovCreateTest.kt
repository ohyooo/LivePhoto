package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic classified QuickTime framing; decoder and device acceptance are separate. */
class AppleMovCreateTest {
    private val context = Context(Limits(12_000_000uL, 12_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mov"))
    private val strict = MutationPolicy(preservation = PreservationPolicy.Strict)
    private fun source(bytes: ByteArray, name: String) = MemoryBinarySource(Bytes(bytes), SourceId(name))
    private suspend fun bytes(source: BinarySource) = BinaryReader(source, context).readExactly(0uL, source.size().orThrow().toUInt()).orThrow()
    private suspend fun movie(hevc: Boolean): ByteArray {
        val original = GoogleFixtures.video(hevc = hevc).bytes
        val parser = BmffReader(BinaryReader(source(original, "mov-layout"), context))
        val roots = parser.readBoxes(ByteRange(0uL, original.size.toULong())).orThrow()
        val moov = roots.single { it.type == "moov" }
        val trak = parser.readBoxes(moov.payload).orThrow().single { it.type == "trak" }
        val mdia = parser.readBoxes(trak.payload).orThrow().single { it.type == "mdia" }
        val minf = parser.readBoxes(mdia.payload).orThrow().single { it.type == "minf" }
        val stbl = parser.readBoxes(minf.payload).orThrow().single { it.type == "stbl" }
        val stsd = parser.readBoxes(stbl.payload).orThrow().single { it.type == "stsd" }
        val entry = parser.readBoxes(ByteRange(stsd.payload.offset + 8uL, stsd.payload.length - 8uL)).orThrow().single()
        return original.copyOf().also {
            "qt  ".encodeToByteArray().copyInto(it, roots.single { box -> box.type == "ftyp" }.payload.offset.toInt())
            ("FFMP".encodeToByteArray() + GoogleFixtures.u32(512u) + GoogleFixtures.u32(512u)).copyInto(it, entry.payload.offset.toInt() + 12)
        }
    }
    private suspend fun request(name: String, hevc: Boolean = false) = CreateRequest(source(GoogleFixtures.jpeg(), "$name-image"), source(movie(hevc), "$name-movie"), target,
        edits = EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)), policy = strict, output = MemoryOutputTransaction(context, name), context = context)
    @Test fun explicitMovCreatePreservesContainerSamplesAndPublishesCompletePair(): Unit = runImmediate {
        for (hevc in listOf(false, true)) {
            val req = request("apple-mov-create-$hevc", hevc)
            val before = bytes(req.video)
            val plan = core.plan(req).orThrow()
            assertEquals(target, plan.target)
            assertEquals(Operation.Create, plan.capabilities.operations.single().operation)
            assertTrue(req.output.query().orThrow().assetIds.isEmpty())
            val result = core.create(req).orThrow()
            assertEquals(VideoContainer.Mov, result.output.assets[1].videoContainer)
            assertEquals("video/quicktime", result.output.assets[1].mime)
            assertEquals(Verdict.Valid, result.validation.verdict); assertEquals(Coverage.Complete, result.validation.coverage)
            assertTrue(result.execution.none { it.remuxed || it.transcoded })
            assertTrue(result.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
            val pair = SourceSet.Pair(result.output.assets[0].readableSource!!, result.output.assets[1].readableSource!!)
            assertEquals(target, core.detect(ReadRequest(pair, context)).orThrow().primaryProtocol)
            val original = BinaryReader(req.video, context); val created = BinaryReader(pair.video, context)
            val inputFacts = BmffVideoProbe(original).probe(ByteRange(0uL, before.size.toULong())).orThrow()
            val outputFacts = BmffVideoProbe(created, allowTimedMetadata = true).probe(ByteRange(0uL, pair.video.size().orThrow())).orThrow()
            RemuxVerification.verify(original, inputFacts, created, outputFacts.copy(tracks = outputFacts.tracks.filter { it.handler != "meta" }))
            assertEquals(before, bytes(req.video))
        }
    }
    @Test fun movSetKeyKeepsCompletePrimaryCidAndRevertsEveryMovieByte(): Unit = runImmediate {
        val req = request("apple-mov-key-source")
        val created = core.create(req).orThrow()
        val pair = SourceSet.Pair(created.output.assets[0].readableSource!!, created.output.assets[1].readableSource!!)
        val imageBefore = bytes(pair.image); val movieBefore = bytes(pair.video)
        val pairingBefore = core.inspect(ReadRequest(pair, context)).orThrow().pairing
        var current = pair
        for (index in listOf(1uL, 0uL)) {
            val set = SetKeyRequest(current, CoverPosition.FrameIndex(index), policy = strict, output = MemoryOutputTransaction(context, "apple-mov-key-$index"), context = context)
            assertEquals(target, core.plan(set).orThrow().target)
            val result = core.setKeyPhotoPosition(set).orThrow()
            assertEquals(VideoContainer.Mov, result.output.assets[1].videoContainer)
            current = SourceSet.Pair(result.output.assets[0].readableSource!!, result.output.assets[1].readableSource!!)
            val inspected = core.inspect(ReadRequest(current, context)).orThrow()
            assertEquals(pairingBefore, inspected.pairing)
            assertEquals(0, inspected.keyPhoto.position!!.compareTo(Time(index.toLong() * 40L, 1000u)))
            assertEquals(imageBefore, bytes(current.image))
        }
        assertEquals(movieBefore, bytes(current.video)); assertEquals(movieBefore, bytes(pair.video))
    }
    @Test fun containerMismatchAndGenericOrUnimplementedEntrancesNeverImplicitlyRemux(): Unit = runImmediate {
        val req = request("apple-mov-gates")
        val wrong = req.copy(target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4")))
        assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(core.plan(wrong)).error.code)
        assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(core.create(wrong)).error.code)
        assertTrue(req.output.query().orThrow().assetIds.isEmpty())
        assertEquals(Implementation.Experimental, core.getProtocolCapabilities(target).operations.single { it.operation == Operation.Create }.implementation)
        assertEquals(Implementation.Experimental, core.getProtocolCapabilities(target).operations.single { it.operation == Operation.SetKey }.implementation)
        assertEquals(Implementation.Planned, core.getProtocolCapabilities(target).operations.single { it.operation == Operation.ConvertTo }.implementation)
        assertEquals(Implementation.Planned, core.getProtocolCapabilities(ProtocolSelector(ProtocolIds.Apple)).operations.single { it.operation == Operation.Create }.implementation)
    }
}
