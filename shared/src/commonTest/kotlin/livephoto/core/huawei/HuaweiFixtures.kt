package livephoto.core.huawei

import livephoto.core.google.GoogleFixtures

/** Literal fixed-width trailer oracle. Neither p:q field has an inferred time unit. */
internal object HuaweiFixtures {
    data class Photo(val bytes: ByteArray, val jpeg: ByteArray, val video: ByteArray, val extra: ByteArray, val tail: ByteArray) {
        val videoStart: Int get() = jpeg.size + extra.size
    }

    fun tail(prefix: String = "v6_f1", history: String = "1:2", live: String): ByteArray {
        require(prefix.length <= 6 && history.length <= 8 && live.length <= 20)
        val result = ByteArray(60) { 0x20 }
        prefix.encodeToByteArray().copyInto(result, 0)
        history.encodeToByteArray().copyInto(result, 20)
        live.encodeToByteArray().copyInto(result, 40)
        return result
    }

    fun photo(video: ByteArray = GoogleFixtures.video().bytes, jpeg: ByteArray = GoogleFixtures.jpeg(), extra: ByteArray = byteArrayOf(), prefix: String = "v6_f1", history: String = "1:2", live: String = "LIVE_${video.size + 20}"): Photo {
        val tail = tail(prefix, history, live)
        return Photo(jpeg + extra + video + tail, jpeg, video, extra, tail)
    }
}
