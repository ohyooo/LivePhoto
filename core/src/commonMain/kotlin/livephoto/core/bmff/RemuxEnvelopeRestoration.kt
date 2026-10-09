package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/**
 * Finite same-container restoration, not a general muxer or a public backend.
 * Classified source headers retain their original offsets. Only independently verified,
 * byte-identical packets from the media backend are used for the media payload.
 * The caller owns staging, complete readback verification, and atomic publication.
 */
internal object RemuxEnvelopeRestoration {
    suspend fun write(source: BinaryReader, packets: BinaryReader, sink: BinarySink): CoreResult<Unit> = attempt {
        val context = source.context
        val sourceSize = source.identity().orThrow().size
        val packetSize = packets.identity().orThrow().size
        if (sourceSize > 8_000_000uL || packetSize > 16_065_536uL)
            fail("RESOURCE_LIMIT_EXCEEDED", "Packet restoration exceeds its finite input domain", Stage.Plan)
        val before = BmffVideoProbe(source).probe(ByteRange(0uL, sourceSize)).orThrow()
        val emitted = BmffVideoProbe(packets).probe(ByteRange(0uL, packetSize)).orThrow()
        fun track(video: VideoStructure): VideoTrack {
            if (video.container != VideoContainer.Mp4 || video.tracks.size != 1)
                fail("CAPABILITY_UNSUPPORTED", "Restoration requires one AVC track in MP4", Stage.Plan)
            val track = video.tracks.single()
            if (track.handler != "vide" || track.codec != VideoCodec.Avc || track.sampleEntry != "avc1" ||
                track.samples.size !in 1..64 || track.samples.any { it.presentationTime < 0 || it.decodeTime != it.presentationTime.toULong() })
                fail("CAPABILITY_UNSUPPORTED", "Restoration does not support reordered or non-AVC packets", Stage.Plan)
            return track
        }
        val left = track(before); val right = track(emitted)
        // Refuse unknown source metadata rather than assuming that retaining its bytes
        // is sufficient to preserve references. No output is written during preflight.
        RemuxVerification.metadata(source, before)
        fun unchanged(value: Boolean) {
            if (!value) fail("POSTCONDITION_FAILED", "Backend packets do not match the source", Stage.Verify)
        }
        unchanged(left.samples.size == right.samples.size && left.width == right.width && left.height == right.height)
        val sourceDigest = sha256Range(source, before.range).orThrow()
        val packetDigest = sha256Range(packets, emitted.range).orThrow()
        for ((a, b) in left.samples.zip(right.samples)) {
            checkCancelled(context)
            unchanged(a.range.length == b.range.length && sha256Range(source, a.range).orThrow() == sha256Range(packets, b.range).orThrow())
            unchanged(Time(a.decodeTime.toLong(), left.timescale).compareTo(Time(b.decodeTime.toLong(), right.timescale)) == 0 &&
                Time(a.presentationTime, left.timescale).compareTo(Time(b.presentationTime, right.timescale)) == 0 &&
                Time(a.duration.toLong(), left.timescale).compareTo(Time(b.duration.toLong(), right.timescale)) == 0)
            unchanged(a.isSync == b.isSync && a.dependencyByte == b.dependencyByte)
        }
        val ordered = left.samples.zip(right.samples).sortedBy { it.first.range.offset }
        var previousEnd = 0uL
        for ((a, _) in ordered) {
            if (a.range.offset < previousEnd) fail("CORRUPTED_CONTAINER", "Overlapping source packet ranges", Stage.Plan)
            previousEnd = a.range.endExclusive
        }
        val writer = BinaryWriter(sink, context)
        writer.budget.checkCapacity(sourceSize)
        var offset = 0uL
        for ((a, b) in ordered) {
            copyRange(source, writer, ByteRange(offset, a.range.offset - offset), context).orThrow()
            copyRange(packets, writer, b.range, context).orThrow()
            offset = a.range.endExclusive
        }
        copyRange(source, writer, ByteRange(offset, sourceSize - offset), context).orThrow()
        if (sourceDigest != sha256Range(source, before.range).orThrow() || packetDigest != sha256Range(packets, emitted.range).orThrow())
            fail("SOURCE_CHANGED", "Source or backend packets changed during restoration", Stage.Verify)
        source.validateIdentity().orThrow(); packets.validateIdentity().orThrow()
    }
}
