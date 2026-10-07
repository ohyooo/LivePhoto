package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** One nonreordered video, retaining every source presentation sample and duration (including VFR). */
internal fun transcodeProfile(video: VideoStructure, encoding: VideoEncoding): VideoTrack {
    val track = video.tracks.singleOrNull()
        ?: fail("CAPABILITY_UNSUPPORTED", "This transcode profile refuses audio/extra tracks instead of dropping them", Stage.Plan)
    if (encoding.codec != VideoCodec.Avc || encoding.container != VideoContainer.Mp4 || encoding.bitrate != null ||
        encoding.frameRatePolicy != FrameRatePolicy.Preserve || encoding.dynamicRange != DynamicRangePolicy.Preserve ||
        encoding.width != null && encoding.width != track.width || encoding.height != null && encoding.height != track.height)
        fail("CAPABILITY_UNSUPPORTED", "Only unchanged-dimension/rate/range default-quality AVC MP4 encoding is implemented", Stage.Plan)
    val identityMatrix = Bytes(unsignedBytes(0x10000uL, 4, Endian.Big).toByteArray() + ByteArray(12) +
        unsignedBytes(0x10000uL, 4, Endian.Big).toByteArray() + ByteArray(12) + unsignedBytes(0x40000000uL, 4, Endian.Big).toByteArray())
    if (video.container != VideoContainer.Mp4 || track.handler != "vide" || track.sampleEntry != "avc1" || track.codec != VideoCodec.Avc || track.edit != null ||
        track.samples.size !in 1..64 || track.samples.any { it.presentationTime < 0 || it.presentationTime.toULong() != it.decodeTime } ||
        track.samples.first().decodeTime != 0uL || track.transform != identityMatrix || track.width == null || track.height == null ||
        track.displayWidthFixed.toULong() != checkedMultiply(track.width.toULong(), 65536uL) || track.displayHeightFixed.toULong() != checkedMultiply(track.height.toULong(), 65536uL) ||
        track.timescale > Int.MAX_VALUE.toUInt() || video.movieTimescale > Int.MAX_VALUE.toUInt())
        fail("CAPABILITY_UNSUPPORTED", "Finite transcode requires at most 64 AVC frames, identity transform, no edit/reordering and bounded MP4 timescales", Stage.Plan)
    return track
}

internal suspend fun verifyTranscode(input: BinaryReader, before: VideoStructure, output: BinaryReader, after: VideoStructure, encoding: VideoEncoding) {
    val a = transcodeProfile(before, encoding); val b = transcodeProfile(after, encoding)
    fun same(ok: Boolean, selector: String) { if (!ok) fail("POSTCONDITION_FAILED", "Transcode changed unrequested media semantics", Stage.Verify, Location(selector = selector)) }
    same(after.container == encoding.container && b.codec == encoding.codec, "codec/container")
    same(a.trackId == b.trackId && a.timescale == b.timescale && before.movieTimescale == after.movieTimescale && before.movieDuration == after.movieDuration, "tracks/timescales/movieDuration")
    same(a.duration == b.duration && a.presentationDuration.compareTo(b.presentationDuration) == 0 && a.width == b.width && a.height == b.height, "duration/dimensions")
    same(a.samples.size == b.samples.size, "frameCount")
    for ((left, right) in a.samples.zip(b.samples)) {
        checkCancelled(input.context)
        same(left.presentationTime == right.presentationTime && left.decodeTime == right.decodeTime && left.duration == right.duration, "framePtsDtsDuration")
    }
    RemuxVerification.verifyMetadata(RemuxVerification.metadata(input, before, transcodeAvcConfiguration = true),
        RemuxVerification.metadata(output, after, transcodeAvcConfiguration = true))
    input.validateIdentity().orThrow(); output.validateIdentity().orThrow()
}
