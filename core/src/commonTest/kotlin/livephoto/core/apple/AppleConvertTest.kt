package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.RangeSource
import livephoto.core.memory.*
import kotlin.test.*

class AppleConvertTest {
    private val context = Context(Limits(12_000_000uL, 12_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4"))
    private fun source(bytes: ByteArray, id: String = "live-source") = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun strict() = MutationPolicy(preservation = PreservationPolicy.Strict,
        requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.ImageDataPreserving, Guarantee.MetadataPreserving))

    @Test fun publicConvertPlansReadonlyThenPublishesVerifiedPairWithInheritedOrExplicitKey(): Unit = runImmediate {
        val identifiers = mutableSetOf<String>()
        for (v2 in listOf(false, true)) for (audio in listOf(false, true)) for (explicit in listOf(false, true)) {
            val video = GoogleFixtures.video(aac = audio).bytes
            val bytes = if (v2) GoogleFixtures.v2Photo(video, timestamp = "0") else GoogleFixtures.v1Photo(video, timestamp = "0")
            val input = source(bytes)
            val before = sha256Range(BinaryReader(input, context), ByteRange(0uL, bytes.size.toULong())).orThrow()
            val output = MemoryOutputTransaction(context, "apple-convert-$v2-$audio-$explicit")
            val request = ConvertRequest(SourceSet.Single(input), target,
                edits = if (explicit) EditSpec(keyPosition = CoverPosition.FrameIndex(1uL)) else null,
                policy = strict(), output = output, context = context)
            val plan = core.plan(request).orThrow()
            assertEquals(Operation.ConvertTo, plan.steps.last { Operation.ConvertTo in it.required }.required.single())
            assertTrue(output.query().orThrow().assetIds.isEmpty())
            val result = core.convert(request).orThrow()
            try {
                assertEquals(listOf(AssetRole.PrimaryImage, AssetRole.MotionVideo), result.output.assets.map { it.role })
                assertEquals(TransactionState.Committed, result.output.receipt.state)
                assertEquals(Verdict.Valid, result.validation.verdict); assertEquals(Coverage.Complete, result.validation.coverage)
                assertTrue(result.execution.none { it.transcoded })
                assertTrue(result.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
                val pair = SourceSet.Pair(result.output.assets[0].readableSource!!, result.output.assets[1].readableSource!!)
                val inspected = core.inspect(ReadRequest(pair, context)).orThrow()
                assertEquals(target, inspected.detection.primaryProtocol)
                assertEquals(true, inspected.pairing?.matches)
                val identifier = inspected.pairing!!.imageIdentifier!!
                assertTrue(appleUuid(identifier)); assertTrue(identifiers.add(identifier), "New conversion must allocate a new pair CID")
                assertEquals(0, inspected.keyPhoto.position?.compareTo(if (explicit) Time(40, 1000u) else Time.Zero))
                val movie = BinaryReader(result.output.assets[1].readableSource!!, context)
                val facts = BmffVideoProbe(movie, allowTimedMetadata = true).probe(ByteRange(0uL, movie.identity().orThrow().size)).orThrow()
                assertEquals(if (audio) 3 else 2, facts.tracks.size)
                assertEquals("0", (inspected.keyPhoto.rawFields.single().rawValue as Value.Number).decimal)
                assertEquals(before, sha256Range(BinaryReader(input, context), ByteRange(0uL, bytes.size.toULong())).orThrow())
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
        }
        val caps = core.getProtocolCapabilities(target).operations
        assertEquals(Implementation.Experimental, caps.single { it.operation == Operation.ConvertTo }.implementation)
        assertEquals(Implementation.Planned, caps.single { it.operation == Operation.Create }.implementation)
    }

    @Test fun secondAssetFailureAndStagedMetadataCorruptionNeverPublishHalfPair(): Unit = runImmediate {
        for (corrupt in listOf(false, true)) {
            val output = MemoryOutputTransaction(context, "apple-fault-$corrupt")
            var creates = 0
            val faults = object : OutputTransaction by output {
                override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                    creates++
                    if (!corrupt && creates == 2) return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Injected second asset failure"))
                    return output.create(spec)
                }
                override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                    val source = output.openStaged(id).orThrow()
                    if (!corrupt || id.value != "asset-1") return CoreResult.Success(source)
                    try {
                        val identity = source.identity().orThrow()
                        val bytes = source.readAt(0uL, identity.size.toUInt()).orThrow().toByteArray()
                        val reader = BinaryReader(source, context)
                        val facts = BmffVideoProbe(reader, allowTimedMetadata = true).probe(ByteRange(0uL, identity.size)).orThrow()
                        val sample = facts.tracks.single { it.handler == "meta" }.samples.single()
                        bytes[(sample.range.endExclusive - 1uL).toInt()] = 1 // Valid marker byte, but not the planned owned timed sample.
                        return CoreResult.Success(MemoryBinarySource(Bytes(bytes), identity.id, identity.generation))
                    } finally { source.close() }
                }
            }
            val result = core.convert(ConvertRequest(SourceSet.Single(source(GoogleFixtures.v2Photo())), target, policy = strict(), output = faults, context = context))
            assertIs<CoreResult.Failure>(result)
            assertEquals(if (corrupt) "POSTCONDITION_FAILED" else "IO_WRITE_FAILED", result.error.code.value)
            assertEquals(TransactionState.Aborted, output.query().orThrow().state)
            assertTrue(output.committedAssets().isEmpty())
        }
    }

    @Test fun unknownSourceExifAndNonatomicOrUnimplementedTargetsAreRefusedBeforeStaging(): Unit = runImmediate {
        val ordinaryExif = GoogleFixtures.segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() +
            byteArrayOf(77, 77, 0, 42) + GoogleFixtures.u32(8u) + byteArrayOf(0, 0) + GoogleFixtures.u32(0u))
        val bytes = GoogleFixtures.v2Photo().let { it.copyOfRange(0, 2) + ordinaryExif + it.copyOfRange(2, it.size) }
        val output = MemoryOutputTransaction(context, "apple-unknown-exif")
        assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source(bytes)), target, output = output, context = context))).error.code.value)
        assertTrue(output.query().orThrow().assetIds.isEmpty())
        val weaker = object : OutputTransaction by output { override fun capabilities() = output.capabilities().copy(assetSetAtomic = false) }
        assertEquals("ATOMIC_PUBLICATION_UNAVAILABLE", assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source(GoogleFixtures.v2Photo())), target, output = weaker, context = context))).error.code.value)
        assertTrue(output.query().orThrow().assetIds.isEmpty())
        for (profile in listOf(null, ProfileId("jpeg-mov"), ProfileId("heic-mov"))) {
            val planned = core.convert(ConvertRequest(SourceSet.Single(source(GoogleFixtures.v2Photo())), ProtocolSelector(ProtocolIds.Apple, profile), output = output, context = context))
            assertEquals("CAPABILITY_PLANNED", assertIs<CoreResult.Failure>(planned).error.code.value)
        }
    }

    @Test fun canonicalJfifAndSingleMdatAllowAppleToGoogleRoundtripWithoutChangingCodedSamples(): Unit = runImmediate {
        val jfif = GoogleFixtures.segment(0xe0, byteArrayOf(0x4a, 0x46, 0x49, 0x46, 0, 1, 2, 0, 0, 1, 0, 1, 0, 0))
        val plain = GoogleFixtures.v2Photo(timestamp = "40000")
        val input = source(plain.copyOfRange(0, 2) + jfif + plain.copyOfRange(2, plain.size))
        val apple = core.convert(ConvertRequest(SourceSet.Single(input), target, policy = strict(), output = MemoryOutputTransaction(context, "apple-jfif"), context = context)).orThrow()
        try {
            val reader = BinaryReader(apple.output.assets[1].readableSource!!, context)
            val roots = BmffReader(reader).readBoxes(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            assertEquals(1, roots.count { it.type == "mdat" })
            val pair = SourceSet.Pair(apple.output.assets[0].readableSource!!, reader.source)
            val converted = core.convert(ConvertRequest(pair, ProtocolSelector(ProtocolIds.GoogleV2), output = MemoryOutputTransaction(context, "apple-google-roundtrip"), context = context)).orThrow()
            try {
                assertTrue(converted.execution.none { it.transcoded })
                val inspected = core.inspect(ReadRequest(SourceSet.Single(converted.output.assets.single().readableSource!!), context)).orThrow()
                assertEquals(ProtocolIds.GoogleV2, inspected.detection.primaryProtocol?.protocol)
                assertEquals(0, inspected.keyPhoto.position?.compareTo(Time(40, 1000u)))
                val extent = inspected.layout.resources.single { it.kind == ResourceKind.Video }.extents.single().range
                val outputReader = BinaryReader(converted.output.assets.single().readableSource!!, context)
                val videoReader = BinaryReader(RangeSource(outputReader, extent), context)
                val video = BmffVideoProbe(videoReader).probe(ByteRange(0uL, extent.length)).orThrow()
                val original = BinaryReader(source(GoogleFixtures.video().bytes, "original-video"), context)
                val facts = BmffVideoProbe(original).probe(ByteRange(0uL, original.identity().orThrow().size)).orThrow()
                RemuxVerification.verify(original, facts, videoReader, video)
            } finally { converted.output.assets.forEach { it.readableSource?.close() } }
        } finally { apple.output.assets.forEach { it.readableSource?.close() } }
    }
}
