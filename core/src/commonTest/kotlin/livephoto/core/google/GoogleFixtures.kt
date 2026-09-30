package livephoto.core.google

/** Tiny independent ISO-BMFF/JPEG fixtures. These make no device or decoder claims. */
internal object GoogleFixtures {
    data class Video(val bytes: ByteArray, val sampleOffset: ULong, val samples: List<ByteArray>, val configuration: ByteArray)

    fun jpeg(extraSegments: ByteArray = byteArrayOf()): ByteArray {
        val quantization = byteArrayOf(0) + ByteArray(64) { 1 }
        val frame = bytes(8, 0, 1, 0, 1, 1, 1, 0x11, 0)
        val dcTable = bytes(0, 1) + ByteArray(15) + bytes(0)
        val acTable = bytes(0x10, 1) + ByteArray(15) + bytes(0)
        val scanHeader = bytes(1, 1, 0, 0, 63, 0)
        return bytes(0xff, 0xd8) + extraSegments + segment(0xdb, quantization) + segment(0xc0, frame) +
            segment(0xc4, dcTable + acTable) + segment(0xda, scanHeader) + bytes(0x3f, 0xff, 0xd9)
    }

    fun video(editList: ByteArray? = null, composition: ByteArray? = null, trackDuration: UInt = 80u, hevc: Boolean = false, co64: Boolean = false, aac: Boolean = false, audioConfig: ByteArray = bytes(0x11, 0x90)): Video {
        val samples = if (hevc) listOf(bytes(0, 0, 0, 2, 0x26, 1), bytes(0, 0, 0, 3, 2, 1, 0x22))
            else listOf(bytes(0, 0, 0, 2, 0x65, 0x88), bytes(0, 0, 0, 3, 0x41, 0x9a, 0x22))
        val configuration = if (hevc) {
            bytes(1) + ByteArray(12) + bytes(0xf0, 0, 0xfc, 0xfc, 0xf8, 0xf8, 0, 0, 3, 3) +
                listOf(32, 33, 34).fold(byteArrayOf()) { result, type -> result + bytes(0x80 or type, 0, 1, 0, 2, type shl 1, 1) }
        } else bytes(1, 66, 0, 30, 0xff, 0xe1, 0, 4, 0x67, 66, 0, 30, 1, 0, 2, 0x68, 0xce)
        val visualHeader = ByteArray(78)
        put16(visualHeader, 6, 1)
        put16(visualHeader, 24, 1)
        put16(visualHeader, 26, 1)
        put32(visualHeader, 28, 0x00480000u)
        put32(visualHeader, 32, 0x00480000u)
        put16(visualHeader, 40, 1)
        put16(visualHeader, 74, 24)
        put16(visualHeader, 76, 0xffff)
        val sampleDescription = box(if (hevc) "hvc1" else "avc1", visualHeader + box(if (hevc) "hvcC" else "avcC", configuration))
        val stsd = fullBox("stsd", u32(1u) + sampleDescription)
        val stts = fullBox("stts", u32(1u) + u32(2u) + u32(40u))
        val stsc = fullBox("stsc", u32(1u) + u32(1u) + u32(2u) + u32(1u))
        val stsz = fullBox("stsz", u32(0u) + u32(2u) + u32(6u) + u32(7u))
        val stss = fullBox("stss", u32(1u) + u32(1u))
        val tkhd = ByteArray(84)
        tkhd[3] = 1
        put32(tkhd, 12, 1u)
        put32(tkhd, 20, trackDuration)
        matrix(tkhd, 40)
        put32(tkhd, 76, 0x10000u)
        put32(tkhd, 80, 0x10000u)
        val mdhd = ByteArray(24)
        put32(mdhd, 12, 1000u)
        put32(mdhd, 16, 80u)
        put16(mdhd, 20, 0x55c4)
        val hdlr = fullBox("hdlr", u32(0u) + "vide".encodeToByteArray() + ByteArray(12) + "Video\u0000".encodeToByteArray())
        val mvhd = ByteArray(100)
        put32(mvhd, 12, 1000u)
        put32(mvhd, 16, 80u)
        put32(mvhd, 20, 0x10000u)
        put16(mvhd, 24, 0x100)
        matrix(mvhd, 36)
        put32(mvhd, 96, if (aac) 3u else 2u)
        fun moov(chunkOffset: UInt): ByteArray {
            val stco = fullBox(if (co64) "co64" else "stco", u32(1u) + (if (co64) u32(0u) else byteArrayOf()) + u32(chunkOffset))
            val stbl = box("stbl", stsd + stts + stsc + stsz + stco + stss + (composition ?: byteArrayOf()))
            val vmhd = box("vmhd", bytes(0, 0, 0, 1) + ByteArray(8))
            val dref = fullBox("dref", u32(1u) + box("url ", bytes(0, 0, 0, 1)))
            val minf = box("minf", vmhd + box("dinf", dref) + stbl)
            val mdia = box("mdia", box("mdhd", mdhd) + hdlr + minf)
            val videoTrack = box("trak", box("tkhd", tkhd) + (editList?.let { box("edts", it) } ?: byteArrayOf()) + mdia)
            val audioTrack = if (!aac) byteArrayOf() else {
                val header = ByteArray(28)
                put16(header, 6, 1); put16(header, 16, 2); put16(header, 18, 16)
                put32(header, 24, 48000u shl 16)
                // Independent ES_Descriptor -> DecoderConfig -> AAC-LC ASC, then SLConfig.
                val es = bytes(3, 25, 0, 2, 0, 4, 17, 0x40, 0x15) + ByteArray(11) + bytes(5, 2) + audioConfig + bytes(6, 1, 2)
                val audioStsd = fullBox("stsd", u32(1u) + box("mp4a", header + fullBox("esds", es)))
                val audioStts = fullBox("stts", u32(1u) + u32(1u) + u32(1024u))
                val audioStsc = fullBox("stsc", u32(1u) + u32(1u) + u32(1u) + u32(1u))
                val audioStsz = fullBox("stsz", u32(4u) + u32(1u))
                val audioStco = fullBox("stco", u32(1u) + u32(chunkOffset + 13u))
                val audioStbl = box("stbl", audioStsd + audioStts + audioStsc + audioStsz + audioStco)
                val audioMinf = box("minf", fullBox("smhd", ByteArray(4)) + box("dinf", dref) + audioStbl)
                val audioMdhd = mdhd.copyOf()
                put32(audioMdhd, 12, 48000u); put32(audioMdhd, 16, 1024u)
                val audioHdlr = fullBox("hdlr", u32(0u) + "soun".encodeToByteArray() + ByteArray(12) + "Audio\u0000".encodeToByteArray())
                val audioTkhd = tkhd.copyOf()
                put32(audioTkhd, 12, 2u); put32(audioTkhd, 20, 21u)
                put16(audioTkhd, 36, 0x100); put32(audioTkhd, 76, 0u); put32(audioTkhd, 80, 0u)
                box("trak", box("tkhd", audioTkhd) + box("mdia", box("mdhd", audioMdhd) + audioHdlr + audioMinf))
            }
            return box("moov", box("mvhd", mvhd) + videoTrack + audioTrack)
        }
        val ftyp = box("ftyp", "isom".encodeToByteArray() + u32(0u) + "isommp42avc1".encodeToByteArray())
        val offset = ftyp.size + moov(0u).size + 8
        val payload = samples.fold(byteArrayOf()) { result, sample -> result + sample } + if (aac) bytes(0x21, 0x10, 4, 0x60) else byteArrayOf()
        return Video(ftyp + moov(offset.toUInt()) + box("mdat", payload), offset.toULong(), samples, configuration)
    }

    fun xmpSegment(xml: String): ByteArray = segment(0xe1, "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray() + xml.encodeToByteArray())
    fun v1Photo(video: ByteArray = GoogleFixtures.video().bytes, timestamp: String? = "0", length: String = video.size.toString(), version: String = "1", extra: String = ""): ByteArray {
        val key = timestamp?.let { "g:MicroVideoPresentationTimestampUs='$it'" } ?: ""
        val xml = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:g='http://ns.google.com/photos/1.0/camera/' g:MicroVideo='1' g:MicroVideoVersion='$version' g:MicroVideoOffset='$length' $key>$extra</rdf:Description></rdf:RDF>"
        return jpeg(xmpSegment(xml)) + video
    }

    fun v2Xml(length: Int, timestamp: String? = "0", version: String = "1", directory: String? = null, extra: String = ""): String {
        val key = timestamp?.let { "camera:MotionPhotoPresentationTimestampUs='$it'" } ?: ""
        val items = directory ?: "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='image/jpeg' item:Semantic='Primary' item:Length='0' item:Padding='0'/></rdf:li>" +
            "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='video/mp4' item:Semantic='MotionPhoto' item:Length='$length'/></rdf:li>"
        return "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:camera='http://ns.google.com/photos/1.0/camera/' xmlns:container='http://ns.google.com/photos/1.0/container/' xmlns:item='http://ns.google.com/photos/1.0/container/item/' camera:MotionPhoto='1' camera:MotionPhotoVersion='$version' $key><container:Directory><rdf:Seq>$items</rdf:Seq></container:Directory>$extra</rdf:Description></rdf:RDF></x:xmpmeta>"
    }

    fun v2Photo(video: ByteArray = GoogleFixtures.video().bytes, timestamp: String? = "0", version: String = "1", directory: String? = null, extra: String = ""): ByteArray =
        jpeg(xmpSegment(v2Xml(video.size, timestamp, version, directory, extra))) + video
    fun segment(marker: Int, payload: ByteArray): ByteArray = bytes(0xff, marker, (payload.size + 2) ushr 8, (payload.size + 2) and 255) + payload
    fun box(type: String, payload: ByteArray): ByteArray = u32((payload.size + 8).toUInt()) + type.encodeToByteArray() + payload
    fun fullBox(type: String, payload: ByteArray): ByteArray = box(type, ByteArray(4) + payload)
    fun u32(value: UInt): ByteArray = ByteArray(4) { (value shr ((3 - it) * 8)).toByte() }
    fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
    private fun put16(buffer: ByteArray, offset: Int, value: Int) { buffer[offset] = (value ushr 8).toByte(); buffer[offset + 1] = value.toByte() }
    private fun put32(buffer: ByteArray, offset: Int, value: UInt) { u32(value).copyInto(buffer, offset) }
    private fun matrix(buffer: ByteArray, offset: Int) {
        put32(buffer, offset, 0x10000u)
        put32(buffer, offset + 16, 0x10000u)
        put32(buffer, offset + 32, 0x40000000u)
    }
}
