package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** FFmpeg emits this recovery map. Reject other valid maps before process/temp/staging IO. */
internal object FfmpegAacRemuxProfile {
    fun validate(video: VideoStructure, metadata: RemuxVerification.Metadata) {
        for ((index, track) in video.tracks.withIndex()) {
            if (track.handler != "soun") continue
            val prefix = "/moov[0]/trak[$index]/mdia[0]/minf[0]/stbl[0]"
            val mapping = Bytes(ByteArray(4) + "roll".encodeToByteArray() +
                unsignedBytes(1uL, 4, Endian.Big).toByteArray() +
                unsignedBytes(track.samples.size.toULong(), 4, Endian.Big).toByteArray() +
                unsignedBytes(1uL, 4, Endian.Big).toByteArray())
            val expected = Sha256().also { it.update(mapping) }.finish()
            // metadata() already validates the exact paired sgpd description and entire graph.
            // No global restriction: a different backend can preserve a valid groupless/two-run input.
            if (metadata.fields["$prefix/sgpd[0]"] == null || metadata.fields["$prefix/sbgp[0]"] != expected)
                fail("CAPABILITY_UNSUPPORTED", "FFmpeg AAC remux requires the existing canonical all-sample roll recovery map; adding/changing groups is not authorized", Stage.Plan)
        }
    }
}
