package livephoto.core.oplus

import livephoto.core.google.GoogleFixtures

/** Independently encoded vendor XMP + TIFF; no production writer or device claim. */
internal object OplusFixtures {
    const val uri = "http://ns.oplus.com/photos/1.0/camera/"
    const val marker = "oplus_10485792"
    const val description = "ordinary EXIF description"

    fun photo(
        video: ByteArray = GoogleFixtures.video().bytes,
        tail: ByteArray = byteArrayOf(),
        directoryLength: String? = (video.size + tail.size).toString(),
        videoLength: String? = video.size.toString(),
        googleTimestamp: String? = "0",
        vendorTimestamp: String? = "0",
        owner: String = "oplus",
        version: String = "2",
        comment: String? = marker,
        littleEndian: Boolean = true,
        extra: String = "",
        directory: String? = null,
        secondaryPadding: String? = null,
    ): ByteArray {
        val padding = secondaryPadding?.let { " item:Padding='$it'" } ?: ""
        val motionDirectory = directory ?: "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='image/jpeg' item:Semantic='Primary' item:Length='0' item:Padding='0'/></rdf:li>" +
            "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='video/mp4' item:Semantic='MotionPhoto' ${directoryLength?.let { "item:Length='$it'" } ?: ""}$padding/></rdf:li>"
        val key = vendorTimestamp?.let { "o:MotionPhotoPrimaryPresentationTimestampUs='$it'" } ?: ""
        val vendor = " xmlns:o='$uri' o:MotionPhotoOwner='$owner' o:OLivePhotoVersion='$version' ${videoLength?.let { "o:VideoLength='$it'" } ?: ""} $key"
        val xml = GoogleFixtures.v2Xml(video.size, googleTimestamp, directory = motionDirectory, extra = extra)
            .replace("<rdf:Description rdf:about=''", "<rdf:Description rdf:about=''$vendor")
        return GoogleFixtures.jpeg(exifSegment(comment, littleEndian) + GoogleFixtures.xmpSegment(xml)) + video + tail
    }

    fun ordinaryImage(comment: String? = null, littleEndian: Boolean = true): ByteArray =
        GoogleFixtures.jpeg(exifSegment(comment, littleEndian))

    fun exifSegment(comment: String?, littleEndian: Boolean = true): ByteArray {
        val text = (description + "\u0000").encodeToByteArray()
        val user = comment?.let { "ASCII\u0000\u0000\u0000".encodeToByteArray() + (it + "\u0000").encodeToByteArray() }
        val ifdCount = if (user == null) 3 else 4
        val ifdEnd = 8 + 2 + ifdCount * 12 + 4
        val textOffset = ifdEnd + if (user == null) 0 else 18
        val buffer = ByteArray(textOffset + text.size + (user?.size ?: 0))
        fun word(offset: Int, value: Int) { repeat(2) { index -> buffer[offset + index] = (value ushr (if (littleEndian) index * 8 else (1 - index) * 8)).toByte() } }
        fun dword(offset: Int, value: Int) { repeat(4) { index -> buffer[offset + index] = (value ushr (if (littleEndian) index * 8 else (3 - index) * 8)).toByte() } }
        buffer[0] = (if (littleEndian) 0x49 else 0x4d).toByte()
        buffer[1] = buffer[0]
        word(2, 42); dword(4, 8); word(8, ifdCount)
        fun entry(offset: Int, tag: Int, type: Int, count: Int, value: Int) { word(offset, tag); word(offset + 2, type); dword(offset + 4, count); dword(offset + 8, value) }
        entry(10, 0x0100, 4, 1, 1)
        entry(22, 0x0101, 4, 1, 1)
        entry(34, 0x010e, 2, text.size, textOffset)
        if (user != null) {
            entry(46, 0x8769, 4, 1, ifdEnd)
            word(ifdEnd, 1)
            entry(ifdEnd + 2, 0x9286, 7, user.size, textOffset + text.size)
            user.copyInto(buffer, textOffset + text.size)
        }
        text.copyInto(buffer, textOffset)
        return GoogleFixtures.segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + buffer)
    }
}
