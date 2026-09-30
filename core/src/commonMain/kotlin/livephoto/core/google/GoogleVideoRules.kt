package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** Protocol constraints are checked independently from generic BMFF structural validity. */
internal fun googleVideoIssues(video: VideoStructure, target: ProtocolSelector, writer: Boolean): List<Issue> {
    val videos = video.tracks.count { it.handler == "vide" }
    val audio = video.tracks.filter { it.handler == "soun" }
    if (writer && videos != 1 || !writer && videos !in 1..2 || audio.size > 1) return listOf(Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Protocol))
    if (target.protocol in setOf(ProtocolIds.Oplus, ProtocolIds.Samsung) && video.container != VideoContainer.Mp4) return listOf(Issue(IssueCode("UNSUPPORTED_CONTAINER"), Severity.Warning, Layer.Protocol))
    if (target.protocol !in setOf(ProtocolIds.GoogleV2, ProtocolIds.Oplus)) return emptyList()
    return audio.mapNotNull { track ->
        if (track.audioCodec != AudioCodec.Aac || track.audioSampleSize != 16u || track.audioActualChannelCount !in setOf(1u, 2u) ||
            track.audioActualSampleRate !in setOf(44_100u, 48_000u, 96_000u)) Issue(IssueCode("AUDIO_CODEC_NOT_SUPPORTED"), Severity.Error, Layer.Protocol, Location(track = TrackId(track.trackId.toString())))
        else if (writer && track.audioActualSampleRate == 96_000u) Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Protocol, Location(track = TrackId(track.trackId.toString())))
        else null
    }
}

internal fun requireGoogleWriteVideo(video: VideoStructure, target: ProtocolSelector): Unit {
    val issues = googleVideoIssues(video, target, true)
    if (issues.isNotEmpty()) fail(issues.first().code.value, "Video does not satisfy the implemented Google target track/audio constraints", Stage.Plan, issues.first().location)
}
