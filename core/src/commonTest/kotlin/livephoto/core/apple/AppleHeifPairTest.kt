package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

class AppleHeifPairTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("heic-mov"))
    private val strict = MutationPolicy(preservation = PreservationPolicy.Strict,
        requiredGuarantees = listOf(Guarantee.ExactExtraction, Guarantee.MetadataPreserving))
    private fun source(bytes: ByteArray, name: String) = MemoryBinarySource(Bytes(bytes), SourceId(name))
    private fun pair(image: ByteArray = AppleHeifFixtures.image(), movie: ByteArray = AppleFixtures.movie()) =
        SourceSet.Pair(source(image, "heic-image"), source(movie, "apple-movie"))
    private suspend fun bytes(input: BinarySource) = BinaryReader(input, context).readExactly(0uL, input.size().orThrow().toUInt()).orThrow()

    @Test fun formalHeifExifAssociationFindsCidAtPhysicalRangeAndKeyUsesMoviePts(): Unit = runImmediate {
        for (idat in listOf(false, true)) for (bias in listOf(0, 6)) {
            val input = pair(AppleHeifFixtures.image(idat = idat, bias = bias), AppleFixtures.movie(timescale = 25u, delay = 30u, payload = 0))
            val inspection = core.inspect(ReadRequest(input, context)).orThrow()
            assertEquals(target, inspection.detection.primaryProtocol); assertEquals(Disposition.Live, inspection.detection.disposition)
            assertEquals(true, inspection.pairing?.matches)
            assertEquals(ImageFormat.Heic, inspection.media.first().imageFormat); assertEquals("image/heic", inspection.media.first().mime)
            assertEquals(Time(30, 25u), inspection.keyPhoto.position); assertEquals(Value.Number("0"), inspection.keyPhoto.rawFields.single().rawValue)
            val field = inspection.metadata.single { it.selector == "apple:image:content-identifier" }
            assertEquals(SourceId("heic-image"), field.location.source)
            assertEquals(Bytes((AppleFixtures.ID + "\u0000").encodeToByteArray()), BinaryReader(input.image, context)
                .readExactly(field.location.range!!.offset, field.location.range.length.toUInt()).orThrow())
            assertEquals(Verdict.Valid, core.validateProtocol(ValidationRequest(input, context = context)).orThrow().verdict)
            assertEquals(Coverage.Partial, core.validate(ValidationRequest(input, context = context)).orThrow().coverage)
            val single = core.inspect(ReadRequest(SourceSet.Single(input.image), context)).orThrow()
            assertEquals(Disposition.Candidate, single.detection.disposition); assertEquals(false, single.pairing?.matches)
        }
    }

    @Test fun rawExtractRawSplitAndSameTargetKeepCompleteHeicAndMovieByteExact(): Unit = runImmediate {
        val input = pair(); val imageBefore = bytes(input.image); val movieBefore = bytes(input.video)
        for (mode in 0..2) {
            val output = MemoryOutputTransaction(context, "heic-raw-$mode")
            val result = when (mode) {
                0 -> core.extract(ExtractRequest(input, emptyList(), output = output, context = context))
                1 -> core.split(SplitRequest(input, SplitMode.Raw, strict, output, context))
                else -> core.convert(ConvertRequest(input, target, policy = strict, output = output, context = context))
            }.orThrow()
            try {
                assertEquals(listOf(AssetRole.PrimaryImage, AssetRole.MotionVideo), result.output.assets.map { it.role })
                assertEquals("image/heic", result.output.assets.first().mime); assertEquals(ImageFormat.Heic, result.output.assets.first().imageFormat)
                assertEquals(imageBefore, bytes(result.output.assets[0].readableSource!!)); assertEquals(movieBefore, bytes(result.output.assets[1].readableSource!!))
                assertTrue(result.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
                assertTrue(result.preservation.changes.isEmpty()); assertTrue(result.execution.none { it.transcoded || it.remuxed })
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
        }
        assertEquals(imageBefore, bytes(input.image)); assertEquals(movieBefore, bytes(input.video))
    }

    @Test fun unknownGraphsWrongTagAndIdConflictNeverInventOrRepairAPair(): Unit = runImmediate {
        val other = "11112233-4455-6677-8899-aabbccddeeff"
        for ((input, code) in listOf(pair(movie = AppleFixtures.movie(other)) to "INVALID_PAIR_IDENTIFIER",
            pair(AppleHeifFixtures.image(tag = 0x17)) to "PAIR_ASSET_MISSING",
            pair(AppleHeifFixtures.image(linked = false)) to "CAPABILITY_UNSUPPORTED",
            pair(AppleHeifFixtures.image(multipleExif = true)) to "CAPABILITY_UNSUPPORTED",
            pair(AppleHeifFixtures.image(unknownProperty = true)) to "CAPABILITY_UNSUPPORTED")) {
            val output = MemoryOutputTransaction(context, "heic-reject-$code")
            assertEquals(code, assertIs<CoreResult.Failure>(core.inspect(ReadRequest(input, context))).error.code.value)
            assertIs<CoreResult.Failure>(core.convert(ConvertRequest(input, target, output = output, context = context)))
            assertTrue(output.query().orThrow().assetIds.isEmpty())
        }
        val input = pair()
        val capabilities = core.getProtocolCapabilities(target).operations
        assertEquals(Implementation.Experimental, capabilities.single { it.operation == Operation.Inspect }.implementation)
        assertEquals(Implementation.Planned, capabilities.single { it.operation == Operation.Create }.implementation)
        assertEquals(Implementation.Unsupported, capabilities.single { it.operation == Operation.SetKey }.implementation)
        val output = MemoryOutputTransaction(context, "heic-writer-gates")
        assertIs<CoreResult.Failure>(core.split(SplitRequest(input, output = output, context = context)))
        assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(0uL), output = output, context = context)))
        assertIs<CoreResult.Failure>(core.convert(ConvertRequest(input, target, sameTarget = SameTargetPolicy.Normalize, output = output, context = context)))
        assertTrue(output.query().orThrow().assetIds.isEmpty())
    }

    @Test fun compatibleMp4IsASeparateReadProfileWithNoImplicitContainerConversion(): Unit = runImmediate {
        val created = core.create(CreateRequest(source(GoogleFixtures.jpeg(), "jpeg"), source(GoogleFixtures.video().bytes, "mp4"),
            ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4")), output = MemoryOutputTransaction(context, "heic-compatible-movie"), context = context)).orThrow()
        try {
            val movie = created.output.assets[1].readableSource!!
            val jpegPair = SourceSet.Pair(created.output.assets[0].readableSource!!, movie)
            val identifier = core.inspect(ReadRequest(jpegPair, context)).orThrow().pairing!!.imageIdentifier!!
            val input = SourceSet.Pair(source(AppleHeifFixtures.image(identifier), "heic-for-mp4"), movie)
            val profile = ProtocolSelector(ProtocolIds.Apple, ProfileId("heic-mp4"))
            assertEquals(profile, core.inspect(ReadRequest(input, context)).orThrow().detection.primaryProtocol)
            assertEquals(Implementation.Experimental, core.getProtocolCapabilities(profile).operations.single { it.operation == Operation.ExtractRaw }.implementation)
            val result = core.convert(ConvertRequest(input, profile, policy = strict, output = MemoryOutputTransaction(context, "heic-mp4-preserve"), context = context)).orThrow()
            try { assertEquals(VideoContainer.Mp4, result.output.assets[1].videoContainer); assertEquals(bytes(movie), bytes(result.output.assets[1].readableSource!!)) }
            finally { result.output.assets.forEach { it.readableSource?.close() } }
        } finally { created.output.assets.forEach { it.readableSource?.close() } }
    }

    @Test fun itemInventoryAndRawExifStayDistinctFromWholeImageCarrier(): Unit = runImmediate {
        for (idat in listOf(false, true)) {
            val input = pair(AppleHeifFixtures.image(idat = idat))
            val inspection = core.inspect(ReadRequest(input, context)).orThrow()
            val items = inspection.layout.resources.filter { it.id.value.startsWith("heif:item:") }
            assertEquals(2, items.size); assertTrue(items.all { !it.standalone && it.kind == ResourceKind.Unknown })
            val association = inspection.layout.relationships.single { it.kind == RelationshipKind.Describes }
            assertEquals(ResourceId("heif:item:2"), association.from); assertEquals(ResourceId("heif:item:1"), association.to)
            assertTrue(inspection.metadata.any { it.selector == "heif:item:2:metadata-format" && it.value == Value.Text("Exif/TIFF") })
            val exif = items.single { it.id == association.from }.extents.single()
            assertEquals(SourceId("heic-image"), exif.source)
            val result = core.extract(ExtractRequest(input, listOf(association.from), output = MemoryOutputTransaction(context, "heic-item-$idat"), context = context)).orThrow()
            try {
                val asset = result.output.assets.single()
                assertEquals(AssetRole.SidecarMetadata, asset.role); assertEquals("application/octet-stream", asset.mime)
                assertEquals(BinaryReader(input.image, context).readExactly(exif.range.offset, exif.range.length.toUInt()).orThrow(), bytes(asset.readableSource!!))
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
        }
    }

    @Test fun candidateOrderNeverResolvesDuplicateIdsAndGuardsUnselectedHeif(): Unit = runImmediate {
        val unrelated = TestSource(AppleHeifFixtures.image("11112233-4455-6677-8899-aabbccddeeff"))
        val inputs = listOf(source(AppleHeifFixtures.image(), "selected-image"), source(AppleFixtures.movie(), "selected-video"), unrelated)
        for (ordered in listOf(inputs, inputs.reversed())) {
            val inspection = core.inspect(ReadRequest(SourceSet.Candidates(ordered), context)).orThrow()
            assertEquals(true, inspection.pairing?.matches); assertEquals(3, inspection.snapshot.identities.size)
        }
        for (ordered in listOf(inputs + source(AppleHeifFixtures.image(), "duplicate"), (inputs + source(AppleFixtures.movie(), "duplicate-video")).reversed())) {
            assertEquals("AMBIGUOUS_PAIR", assertIs<CoreResult.Failure>(core.inspect(ReadRequest(SourceSet.Candidates(ordered), context))).error.code.value)
        }
        val transaction = MemoryOutputTransaction(context, "heif-candidate-changed")
        val output = object : OutputTransaction by transaction {
            override suspend fun prepare(): CoreResult<Unit> {
                unrelated.currentIdentity = unrelated.currentIdentity.copy(generation = GenerationToken("changed"))
                return transaction.prepare()
            }
        }
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(core.extract(ExtractRequest(SourceSet.Candidates(inputs), emptyList(), output = output, context = context))).error.code.value)
        assertEquals(TransactionState.Aborted, transaction.query().orThrow().state)
        assertTrue(transaction.committedAssets().isEmpty()); assertFalse(unrelated.closed)
    }

    @Test fun secondAssetFailureAbortsHeicPairForExtractSplitAndSameTarget(): Unit = runImmediate {
        for (mode in 0..2) {
            val input = pair(); val imageBefore = bytes(input.image); val movieBefore = bytes(input.video)
            val transaction = MemoryOutputTransaction(context, "heif-second-failure-$mode")
            var created = 0
            val output = object : OutputTransaction by transaction {
                override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                    if (++created == 2) return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Synthetic second asset failure"))
                    return transaction.create(spec)
                }
            }
            val result = when (mode) {
                0 -> core.extract(ExtractRequest(input, emptyList(), output = output, context = context))
                1 -> core.split(SplitRequest(input, SplitMode.Raw, strict, output, context))
                else -> core.convert(ConvertRequest(input, target, policy = strict, output = output, context = context))
            }
            assertEquals("IO_WRITE_FAILED", assertIs<CoreResult.Failure>(result).error.code.value)
            assertEquals(2, created); assertEquals(TransactionState.Aborted, transaction.query().orThrow().state)
            assertTrue(transaction.committedAssets().isEmpty())
            assertEquals(imageBefore, bytes(input.image)); assertEquals(movieBefore, bytes(input.video))
        }
    }
}
