package livephoto.core.vivo

import livephoto.core.google.GoogleFixtures

/** Synthetic encoded JPEG auxiliary data and literal Native-profile directory facts; no HDR/decode/device certification. */
internal object VivoFixtures {
    const val uri = "http://ns.vivo.com/photos/1.0/camera/"
    const val hdrUri = "http://ns.adobe.com/hdr-gain-map/1.0/"
    data class Photo(val bytes: ByteArray, val jpegEnd: Int, val gainMap: ByteArray?, val video: ByteArray)

    fun gainMap(): ByteArray = GoogleFixtures.jpeg(GoogleFixtures.xmpSegment("<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:h='$hdrUri' h:Version='1.0'/></rdf:RDF>"))

    fun photo(
        video: ByteArray = GoogleFixtures.video().bytes,
        gainMap: ByteArray? = null,
        version: String = "1",
        source: String = "1",
        kit: String = "1.0.0.9",
        primaryAttrs: String = "",
        motionAttrs: String = "item:Padding='0'",
        gainAttrs: String = "",
        gainLength: String? = gainMap?.size?.toString(),
        videoLength: String = video.size.toString(),
        timestamp: String? = "0",
        extra: String = "",
        extraSegments: ByteArray = byteArrayOf(),
    ): Photo {
        val packet = xml(videoLength, gainLength, version, source, kit, primaryAttrs, motionAttrs, gainAttrs, timestamp, extra)
        val jpeg = GoogleFixtures.jpeg(extraSegments + GoogleFixtures.xmpSegment(packet))
        return Photo(jpeg + (gainMap ?: byteArrayOf()) + video, jpeg.size, gainMap, video)
    }

    fun xml(videoLength: String?, gainLength: String? = null, version: String = "1", source: String = "1", kit: String = "1.0.0.9", primaryAttrs: String = "", motionAttrs: String = "item:Padding='0'", gainAttrs: String = "", timestamp: String? = "0", extra: String = "", vendor: Boolean = true): String {
        val fields = if (vendor) "v:VMotionPhotoVersion='$version' v:VMotionPhotoSource='$source' v:VMediaKitVersion='$kit'" else ""
        val google = if (videoLength == null) "" else "camera:MotionPhoto='1' camera:MotionPhotoVersion='1' " + (timestamp?.let { "camera:MotionPhotoPresentationTimestampUs='$it'" } ?: "")
        val hdr = if (gainLength != null) "h:Version='1.0'" else ""
        val primary = "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='image/jpeg' item:Semantic='Primary' $primaryAttrs/></rdf:li>"
        val gain = gainLength?.let { "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='image/jpeg' item:Semantic='GainMap' item:Length='$it' $gainAttrs/></rdf:li>" } ?: ""
        val motion = videoLength?.let { "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='video/mp4' item:Semantic='MotionPhoto' item:Length='$it' $motionAttrs/></rdf:li>" } ?: ""
        return "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:v='$uri' xmlns:h='$hdrUri' xmlns:camera='http://ns.google.com/photos/1.0/camera/' xmlns:container='http://ns.google.com/photos/1.0/container/' xmlns:item='http://ns.google.com/photos/1.0/container/item/' $fields $google $hdr><container:Directory><rdf:Seq>$primary$gain$motion</rdf:Seq></container:Directory>$extra</rdf:Description></rdf:RDF></x:xmpmeta>"
    }

    fun ordinaryGainMapImage(gain: ByteArray = gainMap()): ByteArray = GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(xml(null, gain.size.toString(), vendor = false))) + gain
}
