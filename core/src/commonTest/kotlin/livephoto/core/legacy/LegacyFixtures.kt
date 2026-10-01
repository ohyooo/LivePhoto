package livephoto.core.legacy

import livephoto.core.google.GoogleFixtures
import livephoto.core.oplus.OplusFixtures
import livephoto.core.samsung.SamsungFixtures

/** Independent UTF-8 length fields, signature, UUID and SEF fixtures. No product writers. */
internal object LegacyFixtures {
    const val vivoIdKey = "com.android.camera.livephoto"
    const val vivoType = "vivoMediaExtInfo"
    val signature = GoogleFixtures.bytes(0x1b, 0x2a, 0x39, 0x48, 0x57, 0x66, 0x75, 0x84, 0x93, 0xa2, 0xb3)
    data class Pair(val image: ByteArray, val video: ByteArray, val cleanImage: ByteArray, val cleanVideo: ByteArray, val tail: ByteArray, val uuid: ByteArray)

    fun json(id: String, extra: String = ""): String = "{\"$vivoIdKey\":\"${escape(id)}\",\"com.android.camera.imageTime\":40$extra}"
    fun tail(id: String, json: String = json(id)): ByteArray {
        val j = ("vivo" + json).encodeToByteArray()
        val idBytes = id.encodeToByteArray()
        return j + GoogleFixtures.u32((j.size - 4).toUInt()) + "cameralbum!".encodeToByteArray() + GoogleFixtures.u32((19 + idBytes.size).toUInt()) + idBytes + ByteArray(4) { 0xff.toByte() } + signature
    }
    fun pair(id: String = "pair-A", videoId: String = id, extraImage: ByteArray = byteArrayOf(), imageJson: String = json(id), videoJson: String = json(videoId)): Pair {
        val image = GoogleFixtures.jpeg(extraImage)
        val baseVideo = GoogleFixtures.video().bytes
        val imageTail = tail(id, imageJson)
        val uuid = GoogleFixtures.box("uuid", vivoType.encodeToByteArray() + tail(videoId, videoJson))
        return Pair(image + imageTail, baseVideo + uuid, image, baseVideo, imageTail, uuid)
    }

    fun fusion(video: ByteArray = GoogleFixtures.video().bytes, vendorLength: Int = video.size, marker: Boolean = true, ordinarySef: Boolean = false, ordinaryXmp: String = ""): ByteArray {
        val records = listOf(SamsungFixtures.Record(0x0a30, "MotionPhoto_Data", video), SamsungFixtures.Record(0x0a31, "MotionPhoto_Version", "mpv3".encodeToByteArray())) + if (ordinarySef) listOf(SamsungFixtures.ordinary) else emptyList()
        val suffix = SamsungFixtures.trailer(records)
        val directory = "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='image/jpeg' item:Semantic='Primary' item:Length='0' item:Padding='24'/></rdf:li>" +
            "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='video/mp4' item:Semantic='MotionPhoto' item:Length='${suffix.size - 24}' item:Padding='0'/></rdf:li>"
        val author = if (marker) "<l:Protocol xmlns:l='https://github.com/LengxiQwQ/live-photo-box'>MotionPhotoFusion</l:Protocol>" else ""
        val extras = author +
            "<o:MotionPhotoOwner xmlns:o='http://ns.oplus.com/photos/1.0/camera/'>oplus</o:MotionPhotoOwner>" +
            "<o:OLivePhotoVersion xmlns:o='http://ns.oplus.com/photos/1.0/camera/'>2</o:OLivePhotoVersion>" +
            "<o:VideoLength xmlns:o='http://ns.oplus.com/photos/1.0/camera/'>$vendorLength</o:VideoLength>" +
            "<o:MotionPhotoPrimaryPresentationTimestampUs xmlns:o='http://ns.oplus.com/photos/1.0/camera/'>0</o:MotionPhotoPrimaryPresentationTimestampUs>" +
            "<v:VMotionPhotoVersion xmlns:v='http://ns.vivo.com/photos/1.0/camera/'>1</v:VMotionPhotoVersion>" +
            "<v:VMotionPhotoSource xmlns:v='http://ns.vivo.com/photos/1.0/camera/'>1</v:VMotionPhotoSource>" +
            "<v:VMediaKitVersion xmlns:v='http://ns.vivo.com/photos/1.0/camera/'>1.0.0.9</v:VMediaKitVersion>" + ordinaryXmp
        return GoogleFixtures.jpeg(OplusFixtures.exifSegment("oplus_10485792") + GoogleFixtures.xmpSegment(GoogleFixtures.v2Xml(video.size, directory = directory, extra = extras))) + suffix
    }
    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
