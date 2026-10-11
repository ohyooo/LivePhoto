package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** One nonreordered video, retaining every source presentation sample and duration (including VFR). */
internal fun transcodeProfile(video: VideoStructure, encoding: VideoEncoding, allowCopiedAudio: Boolean = false): VideoTrack {
    val track = video.tracks.singleOrNull { it.handler == "vide" }
        ?: fail("CAPABILITY_UNSUPPORTED", "Transcode requires exactly one video track", Stage.Plan)
    val audio = video.tracks.filter { it.handler == "soun" }
    if ((!allowCopiedAudio && audio.isNotEmpty()) || audio.size > 2 ||
        video.tracks.any { it.handler !in setOf("vide", "soun") || it.edit != null || it.trackId > Int.MAX_VALUE.toUInt() || it.timescale > Int.MAX_VALUE.toUInt() } ||
        audio.any { it.audioCodec != AudioCodec.Aac || it.sampleEntry != "mp4a" })
        fail("CAPABILITY_UNSUPPORTED", "This profile refuses extra tracks/edits; only explicit transcode can preserve at most two unchanged AAC-LC tracks", Stage.Plan)
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
    val a = transcodeProfile(before, encoding, allowCopiedAudio = true); val b = transcodeProfile(after, encoding, allowCopiedAudio = true)
    fun same(ok: Boolean, selector: String) { if (!ok) fail("POSTCONDITION_FAILED", "Transcode changed unrequested media semantics", Stage.Verify, Location(selector = selector)) }
    same(after.container == encoding.container && b.codec == encoding.codec, "codec/container")
    same(before.tracks.map { it.trackId } == after.tracks.map { it.trackId }, "trackSet/order")
    same(a.trackId == b.trackId && a.timescale == b.timescale && before.movieTimescale == after.movieTimescale && before.movieDuration == after.movieDuration, "tracks/timescales/movieDuration")
    same(a.duration == b.duration && a.presentationDuration.compareTo(b.presentationDuration) == 0 && a.width == b.width && a.height == b.height, "duration/dimensions")
    same(a.samples.size == b.samples.size, "frameCount")
    for ((left, right) in a.samples.zip(b.samples)) {
        checkCancelled(input.context)
        same(left.presentationTime == right.presentationTime && left.decodeTime == right.decodeTime && left.duration == right.duration, "framePtsDtsDuration")
    }
    // Explicit video encoding does not authorize audio encoding, dropping, retiming or configuration changes.
    RemuxVerification.verifyTracks(input, before.tracks.filter { it.handler == "soun" }, output, after.tracks.filter { it.handler == "soun" })
    RemuxVerification.verifyMetadata(RemuxVerification.metadata(input, before, transcodeAvcConfiguration = true),
        RemuxVerification.metadata(output, after, transcodeAvcConfiguration = true))
    input.validateIdentity().orThrow(); output.validateIdentity().orThrow()
}
