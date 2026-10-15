package livephoto.android

/** Independent synthetic EXIF with an opaque MakerNote; no private capture bytes. */
internal fun cameraCommentExif(): ByteArray {
    val tiff = ByteArray(160)
    fun number(at: Int, value: Int, width: Int) { repeat(width) { tiff[at + it] = (value ushr (it * 8)).toByte() } }
    fun entry(at: Int, tag: Int, type: Int, count: Int, value: Int) {
        number(at, tag, 2); number(at + 2, type, 2); number(at + 4, count, 4); number(at + 8, value, 4)
    }
    tiff[0] = 0x49; tiff[1] = 0x49; number(2, 42, 2); number(4, 8, 4)
    number(8, 1, 2); entry(10, 0x8769, 4, 1, 26)
    number(26, 3, 2); entry(28, 0x9286, 7, 21, 80)
    entry(40, 0x927c, 7, 8, 104); entry(52, 0xa005, 4, 1, 120)
    "ASCII\u0000\u0000\u0000oplus_1048576".encodeToByteArray().copyInto(tiff, 80)
    byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).copyInto(tiff, 104)
    number(120, 1, 2); entry(122, 1, 2, 4, 0)
    "R98\u0000".encodeToByteArray().copyInto(tiff, 130)
    val payload = "Exif\u0000\u0000".encodeToByteArray() + tiff
    return byteArrayOf(0xff.toByte(), 0xe1.toByte(), ((payload.size + 2) ushr 8).toByte(), (payload.size + 2).toByte()) + payload
}

/** Same artificial directories with an unprefixed, ordinary TIFF ASCII camera comment. */
internal fun asciiCameraCommentExif(): ByteArray = cameraCommentExif().also { app ->
    app[40] = 2; app[41] = 0 // TIFF field type, not an EXIF character-set prefix.
    app[42] = 14; app[43] = 0; app[44] = 0; app[45] = 0
    app.fill(0, 90, 111)
    "oplus_1056768\u0000".encodeToByteArray().copyInto(app, 90)
}
