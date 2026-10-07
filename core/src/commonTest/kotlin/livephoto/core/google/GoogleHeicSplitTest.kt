package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.heif.*
import livephoto.core.memory.*
import kotlin.test.*

class GoogleHeicSplitTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic"))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private suspend fun created(image: ByteArray, video: ByteArray = GoogleFixtures.video().bytes, token: String): Bytes {
        val tx = MemoryOutputTransaction(context, token)
        core.create(CreateRequest(source(image, "cover"), source(video, "video"), target, output = tx, context = context)).orThrow()
        return tx.committedAssets().values.single()
    }
    @Test fun cleanSplitDeletesOwnedMotionItemsAndPreservesOrdinaryImageCodingAndExactMovie(): Unit = runImmediate {
        var count = 0
        for (layout in listOf(Triple(false, false, false), Triple(true, false, false), Triple(false, true, false), Triple(false, false, true)))
            for (primary in listOf(1u, 70_000u)) for (reference in listOf(null, 1)) {
                val original = HeifFixtures.plain(metaLast = layout.first, extended = layout.second, idat = layout.third, multiple = true,
                    primaryId = primary, ilocVersion = 2, iinfVersion = 1, emptyReferenceVersion = reference, extendedTables = true, lastTable = "iloc")
                val movie = GoogleFixtures.video(aac = true).bytes
                val motion = created(original, movie, "heic-to-split-${count++}")
                val input = SourceSet.Single(source(motion.toByteArray(), "heic-motion-source"))
                val tx = MemoryOutputTransaction(context, "heic-clean-split-$count")
                val req = SplitRequest(input, policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context)
                val plan = core.plan(req).orThrow()
                assertEquals(Availability.Conditional, plan.capabilities.availability)
                assertTrue(tx.query().orThrow().assetIds.isEmpty())
                val result = core.split(req).orThrow()
                assertEquals(TransactionState.Committed, result.output.receipt.state)
                assertEquals(2, result.output.assets.size)
                val image = tx.committedAssets().getValue(result.output.assets.single { it.role == AssetRole.PrimaryImage }.id)
                val video = tx.committedAssets().getValue(result.output.assets.single { it.role == AssetRole.MotionVideo }.id)
                assertEquals(Bytes(movie), video)
                val inspected = core.inspect(ReadRequest(SourceSet.Single(source(image.toByteArray(), "clean-heic")), context)).orThrow()
                assertTrue(inspected.detection.matches.isEmpty())
                assertEquals(Disposition.NonLive, inspected.detection.disposition)
                assertEquals(ImageFormat.Heic, inspected.media.first().imageFormat)
                assertEquals(1, inspected.layout.resources.size)
                assertTrue(inspected.metadata.none { it.selector.endsWith(":metadata-format") })
                val reader = BinaryReader(source(image.toByteArray(), "clean-heic-graph"), context)
                val roots = BmffReader(reader).readBoxes(ByteRange(0uL, image.size.toULong())).orThrow()
                val graph = HeifItemGraphReader.read(reader, roots).orThrow()
                assertEquals(primary, graph.primary)
                assertEquals(1, graph.infos.size)
                assertTrue(graph.references.isEmpty())
                assertTrue(roots.none { it.type == "mpvd" })
                assertEquals(if (layout.third) 0 else 1, roots.count { it.type == "mdat" })
                val recreating = MemoryOutputTransaction(context, "heic-clean-recreate-$count")
                core.create(CreateRequest(source(image.toByteArray(), "clean-cover"), source(video.toByteArray(), "clean-video"), target, output = recreating, context = context)).orThrow()
                assertTrue(result.preservation.records.none { it.outcome == GuaranteeOutcome.Unknown || it.outcome == GuaranteeOutcome.Changed })
                assertEquals(motion, BinaryReader(input.source, context).readExactly(0uL, motion.size.toUInt()).orThrow())
                val again = MemoryOutputTransaction(context, "heic-clean-idempotent-$count")
                val ordinary = SourceSet.Single(source(image.toByteArray(), "already-clean-heic"))
                val repeated = core.split(SplitRequest(ordinary, policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = again, context = context)).orThrow()
                assertEquals(1, repeated.output.assets.size)
                assertEquals(image, again.committedAssets().values.single())
                assertTrue(repeated.preservation.changes.isEmpty())
            }
        assertEquals(16, count)
    }
    @Test fun unknownRootPrivateCodingAndMixedXmpNeverBecomeCleanupAuthority(): Unit = runImmediate {
        val motion = created(HeifFixtures.plain(), token = "heic-split-refuse-source").toByteArray()
        val reader = BinaryReader(source(motion, "heic-refuse-inspect"), context)
        val roots = BmffReader(reader).readBoxes(ByteRange(0uL, motion.size.toULong())).orThrow()
        val graph = HeifItemGraphReader.read(reader, roots).orThrow()
        val xmp = graph.locations.items.single { it.id != graph.primary }.extents.single().data
        val changedPacket = motion.copyOf().also { bytes ->
            // Equal-length namespace replacement remains valid XML but is no longer fully owned canonical Google XMP.
            val packet = bytes.copyOfRange(xmp.offset.toInt(), xmp.endExclusive.toInt()).decodeToString()
            val changed = packet.replace("MotionPhotoVersion", "MotionPhotoPrivate").encodeToByteArray()
            assertEquals(packet.encodeToByteArray().size, changed.size)
            changed.copyInto(bytes, xmp.offset.toInt())
        }
        val primary = graph.locations.items.single { it.id == graph.primary }.extents.single().data
        val sei = motion.copyOf().also { it[primary.offset.toInt() + 4] = 0x4e }
        val privateMovie = created(HeifFixtures.plain(), GoogleFixtures.video().bytes + GoogleFixtures.box("priv", byteArrayOf(1, 2, 3)), "heic-private-movie-source").toByteArray()
        for ((index, bytes) in listOf(changedPacket, sei, privateMovie).withIndex()) {
            val tx = MemoryOutputTransaction(context, "heic-split-unowned-$index")
            val req = SplitRequest(SourceSet.Single(source(bytes, "heic-private-source-$index")), output = tx, context = context)
            assertIs<CoreResult.Failure>(core.plan(req)); assertIs<CoreResult.Failure>(core.split(req))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }
    @Test fun validProtocolWithOrdinaryXmpCommentIsStillNotWholeItemDeletionAuthority(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val xml = GoogleDirectoryWriter.heic(video.size.toULong(), 0L, context).toByteArray() + "<!--ordinary private comment-->".encodeToByteArray()
        val original = BinaryReader(source(HeifFixtures.plain(), "heic-mixed-xmp-original"), context)
        val plan = HeifXmpAppender.prepare(original, Bytes(xml)).orThrow()
        val privateOutput = MemoryOutputTransaction(context, "heic-mixed-xmp-proof")
        val handle = privateOutput.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/heic")).orThrow()
        plan.write(original, BinaryWriter(handle.sink, context)).orThrow(); handle.sink.close().orThrow(); privateOutput.prepare().orThrow()
        val staged = privateOutput.openStaged(handle.id).orThrow()
        val reader = BinaryReader(staged, context)
        plan.verify(original, reader).orThrow()
        val image = reader.readExactly(0uL, staged.size().orThrow().toUInt()).orThrow().toByteArray()
        staged.close(); privateOutput.abort().orThrow()
        val bytes = image + GoogleFixtures.box("mpvd", video)
        val input = SourceSet.Single(source(bytes, "heic-valid-mixed-xmp"))
        assertEquals(Verdict.Valid, core.validateProtocol(ValidationRequest(input, target = target, context = context)).orThrow().verdict)
        val output = MemoryOutputTransaction(context, "heic-do-not-delete-ordinary-xmp")
        assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(core.split(SplitRequest(input, output = output, context = context))).error.code)
        assertTrue(output.query().orThrow().assetIds.isEmpty())
        assertEquals(Bytes(bytes), BinaryReader(input.source, context).readExactly(0uL, bytes.size.toUInt()).orThrow())
    }
    @Test fun combinedOutputBudgetAndAtomicityAreCheckedBeforeEitherAssetIsStaged(): Unit = runImmediate {
        val motion = created(HeifFixtures.plain(), token = "heic-split-limits-source")
        val limited = context.copy(limits = context.limits.copy(maxOutputBytes = GoogleFixtures.video().bytes.size.toULong()))
        val tx = MemoryOutputTransaction(limited, "heic-split-limit")
        val req = SplitRequest(SourceSet.Single(source(motion.toByteArray(), "heic-motion-limited")), output = tx, context = limited)
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(core.split(req)).error.code)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
        val base = MemoryOutputTransaction(context, "heic-split-no-asset-set-atomic")
        val output = object : OutputTransaction by base { override fun capabilities() = OutputCapabilities(true, false, true, false, true) }
        assertEquals(IssueCode("ATOMIC_PUBLICATION_UNAVAILABLE"), assertIs<CoreResult.Failure>(core.split(SplitRequest(SourceSet.Single(source(motion.toByteArray(), "heic-no-atomic")), output = output, context = context))).error.code)
        assertTrue(base.query().orThrow().assetIds.isEmpty())
    }
    @Test fun failureCreatingSecondAssetAbortsTheFirstRatherThanPublishingHalfASplit(): Unit = runImmediate {
        val motion = created(HeifFixtures.plain(), token = "heic-split-atomic-source")
        val base = MemoryOutputTransaction(context, "heic-split-second-failure")
        var created = 0; var committed = 0
        val output = object : OutputTransaction by base {
            override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                created++
                if (created == 2) return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Injected second-asset failure"))
                return base.create(spec)
            }
            override suspend fun commit(): CoreResult<Receipt> { committed++; return base.commit() }
        }
        assertEquals(IssueCode("IO_WRITE_FAILED"), assertIs<CoreResult.Failure>(core.split(SplitRequest(SourceSet.Single(source(motion.toByteArray(), "heic-atomic-source")), output = output, context = context))).error.code)
        assertEquals(2, created); assertEquals(0, committed)
        assertEquals(TransactionState.Aborted, base.query().orThrow().state)
        assertTrue(base.committedAssets().isEmpty())
    }
}
