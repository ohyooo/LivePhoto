package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.apple.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.jpeg.*

internal object ApplePairOperations {
    suspend fun split(request: SplitRequest, session: SourceSession): CoreResult<OperationResult> = attempt {
        val plan = AppleClean.prepare(session, ParseBudget(request.context)).orThrow()
        val imageReader = BinaryReader(plan.image, request.context)
        val videoReader = BinaryReader(plan.video, request.context)
        suspend fun asset(reader: BinaryReader, role: AssetRole, mime: String): StagedAsset {
            val range = ByteRange(0uL, reader.identity().orThrow().size)
            val raw = GoogleOperations.rawAsset(session, range, role, mime, request.context,
                if (role == AssetRole.MotionVideo) plan.media.container else null, reader)
            return raw.copy(verify = { id, staged ->
                val verified = raw.verify(id, staged)
                val budget = ParseBudget(request.context)
                if (role == AssetRole.PrimaryImage) {
                    val jpeg = JpegParser.parse(staged, budget).orThrow()
                    if (AppleImageReader.read(staged, jpeg, budget).orThrow() != null) fail("POSTCONDITION_FAILED", "Staged Apple image retains CID", Stage.Verify)
                } else {
                    if (AppleVideoReader.read(staged, budget).orThrow() != null) fail("POSTCONDITION_FAILED", "Staged movie retains CID", Stage.Verify)
                    val media = BmffVideoProbe(staged, budget, allowTimedMetadata = true).probe(ByteRange(0uL, staged.identity().orThrow().size)).orThrow()
                    if (media.tracks != plan.media.tracks) fail("POSTCONDITION_FAILED", "Staged movie changed retained sample structure", Stage.Verify)
                }
                verified.copy(guarantees = listOf(
                    GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.ImageDataPreserving, if (role == AssetRole.PrimaryImage) GuaranteeOutcome.Verified else GuaranteeOutcome.NotApplicable,
                        proof = "Fixed-width cleanup changes only parsed protocol-owned metadata"),
                    GuaranteeRecord(id, Guarantee.BitstreamPreserving, if (role == AssetRole.MotionVideo) GuaranteeOutcome.Verified else GuaranteeOutcome.NotApplicable,
                        proof = "Retained media sample/configuration/timeline bytes and offsets are unchanged"),
                    GuaranteeRecord(id, Guarantee.MetadataPreserving, GuaranteeOutcome.Unknown,
                        proof = "Ordinary bytes remain at the same offsets; opaque associations to removed binding are unproven")))
            })
        }
        val assets = listOf(asset(imageReader, AssetRole.PrimaryImage, "image/jpeg"), asset(videoReader, AssetRole.MotionVideo, videoFacts(plan.media).mime!!))
        publish(request.output, request.policy, request.context, session.readers + listOf(imageReader, videoReader), assets, changes(session)).orThrow()
    }

    fun changes(session: SourceSession): List<Change> = session.inspection.metadata.filter { it.owner == Ownership.SourceProtocol }.map {
        Change(it.selector, it.value, null, "Remove verified Apple pair identifier without relocating ordinary bytes", true)
    } + Change(APPLE_STILL_TIME, reason = "Retire verified dedicated timed metadata track and its owned samples", requested = true)
}
