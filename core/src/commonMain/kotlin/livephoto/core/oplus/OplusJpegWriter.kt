package livephoto.core.oplus

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.exif.*
import livephoto.core.google.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

/** JPEG + byte-preserved MP4, canonical Oplus V2 without a vendor trailer. Staging only. */
internal object OplusJpegWriter {
    suspend fun createPlan(
        session: SourceSession, videoLength: ULong, timestamp: Long, strip: Boolean,
        context: Context, budget: ParseBudget,
    ): CoreResult<JpegRewritePlan> = attempt {
        checkCancelled(context)
        val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "Oplus creation requires JPEG image content")
        if (videoLength == 0uL) fail("INVALID_ARGUMENT", "Oplus motion video must be nonempty")
        if (timestamp < -1L) fail("INVALID_PRESENTATION_TIMESTAMP", "Oplus presentation timestamp is outside its protocol domain")
        if (session.bindings.isNotEmpty()) {
            if (strip) fail("CAPABILITY_UNSUPPORTED", "Replacing existing live bindings with Oplus requires a separate verified conversion plan")
            fail("SOURCE_ALREADY_LIVE", "Image contains source protocol bindings")
        }
        reserveXmp(jpeg, budget, mergingVendor = true)
        val additional = markerPatch(session, ExifMarkerAction.AddCanonical, budget)
        val base = GoogleJpegWriter.createPlan(session, ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg")),
            videoLength, "video/mp4", timestamp, false, context, additional).orThrow()
        val xmpPatch = base.patches.singleOrNull { it.exifProof == null }
            ?: fail("POSTCONDITION_FAILED", "Canonical Google base must produce exactly one XMP patch")
        val xmlStart = 4 + XMP_HEADER.length
        if (xmpPatch.replacement.size < xmlStart) fail("POSTCONDITION_FAILED", "Canonical Google base has no complete XMP payload")
        val packet = XmpReader.parse(xmpPatch.replacement.slice(xmlStart), context).orThrow()
        val updates = mapOf(
            ExpandedName(OPLUS_URI, "MotionPhotoPrimaryPresentationTimestampUs") to timestamp.toString(),
            ExpandedName(OPLUS_URI, "MotionPhotoOwner") to "oplus",
            ExpandedName(OPLUS_URI, "OLivePhotoVersion") to "2",
            ExpandedName(OPLUS_URI, "VideoLength") to videoLength.toString(),
        )
        val xml = XmpWriter.merge(packet, updates, context).orThrow()
        val readback = XmpReader.parse(xml, context).orThrow()
        for ((name, expected) in updates) if (readback.scalar(name.uri, name.local).orThrow() != expected) fail("POSTCONDITION_FAILED", "Oplus XMP scalar failed final readback")
        if (readback.scalar(CAMERA_URI, "MotionPhotoPresentationTimestampUs").orThrow() != timestamp.toString()) fail("POSTCONDITION_FAILED", "Oplus and Google timestamps disagree after writing")
        session.recheck()
        GoogleJpegWriter.patch(jpeg, xml, additional)
    }

    suspend fun cleanPlan(session: SourceSession, context: Context, budget: ParseBudget): CoreResult<JpegRewritePlan> = attempt {
        checkCancelled(context)
        val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "Oplus clean requires JPEG content")
        if (session.bindings.none { it.protocol == ProtocolIds.Oplus }) fail("UNSUPPORTED_PROTOCOL", "Oplus cleanup requires an actual Oplus binding")
        reserveXmp(jpeg, budget, mergingVendor = false)
        val additional = markerPatch(session, ExifMarkerAction.RemoveOwned, budget)
        val result = GoogleJpegWriter.cleanPlan(session, context, additional).orThrow()
        session.recheck()
        result
    }

    private suspend fun markerPatch(session: SourceSession, action: ExifMarkerAction, budget: ParseBudget): List<JpegPatch> {
        val jpeg = session.jpeg!!
        val segments = jpeg.segments.filter { it.payloadKind == AppPayloadKind.Exif }
        if (segments.size > 1) fail("CONFLICTING_METADATA", "Multiple EXIF APP1 blocks cannot authorize one Oplus marker")
        val existing = segments.singleOrNull()
        if (existing == null && action == ExifMarkerAction.RemoveOwned) return emptyList()
        val writer = ExifMarkerWriter(session.reader, budget)
        val proof = if (existing == null) writer.createMarkerAppPayload().orThrow() else {
            val payload = existing.payload ?: fail("CORRUPTED_CONTAINER", "EXIF APP1 payload is missing")
            if (payload.length < 6uL) fail("CORRUPTED_CONTAINER", "EXIF APP1 identifier is truncated")
            writer.rewriteMarker(ByteRange(checkedAdd(payload.offset, 6uL), payload.length - 6uL), action).orThrow()
        }
        if (proof.isNoOp) return emptyList() // No change never grants authority to relocate opaque EXIF.
        budget.retain(checkedMultiply(checkedAdd(proof.replacementPayload.size.toULong(), 4uL), 2uL))
        val app = JpegRewrite.appSegment(0xe1, proof.replacementPayload).orThrow()
        val range = existing?.range ?: ByteRange(2uL, 0uL)
        return listOf(JpegPatch(range, app, proof))
    }

    /** Reserve a conservative serializer/parse envelope before helpers allocate their bounded APP data. */
    private fun reserveXmp(jpeg: JpegStructure, budget: ParseBudget, mergingVendor: Boolean) {
        var raw = 0uL
        for (segment in jpeg.segments) if (segment.payloadKind == AppPayloadKind.Xmp) raw = checkedAdd(raw, segment.payload?.length ?: 0uL)
        val upper = minOf(65533uL, checkedAdd(checkedMultiply(raw, 6uL), if (mergingVendor) 4096uL else 1024uL))
        budget.retain(checkedMultiply(upper, if (mergingVendor) 16uL else 8uL))
    }
}
