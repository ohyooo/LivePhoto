package livephoto.android

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Synthetic only: an ordinary JPEG, minimal Google V1 XMP and an unchanged MP4 plus a sparse free box. */
internal fun writeLargeGoogleV1Fixture(file: File, jpeg: ByteArray, movie: ByteArray, total: Int): Int {
    fun packet(length: Int) = ("http://ns.adobe.com/xap/1.0/\u0000" +
        "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>" +
        "<rdf:Description rdf:about='' xmlns:g='http://ns.google.com/photos/1.0/camera/' " +
        "g:MicroVideo='1' g:MicroVideoVersion='1' g:MicroVideoOffset='$length' " +
        "g:MicroVideoPresentationTimestampUs='0'/></rdf:RDF>").encodeToByteArray()
    val estimated = packet(total)
    val videoLength = total - jpeg.size - 4 - estimated.size
    val xmp = packet(videoLength)
    val freeSize = videoLength - movie.size
    check(xmp.size == estimated.size && freeSize >= 8 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
    RandomAccessFile(file, "rw").use { stream ->
        stream.setLength(0)
        stream.write(jpeg, 0, 2)
        stream.writeShort(0xffe1)
        stream.writeShort(xmp.size + 2)
        stream.write(xmp)
        stream.write(jpeg, 2, jpeg.size - 2)
        stream.write(movie)
        stream.writeInt(freeSize)
        stream.writeBytes("free")
        stream.setLength(total.toLong())
    }
    return videoLength
}

/** Write a structurally bounded MP4 free box instead of allocating a giant fixture byte array. */
internal fun writeLargeMovieFixture(file: File, movie: ByteArray, size: Int) {
    val freeSize = size - movie.size
    require(freeSize >= 8)
    RandomAccessFile(file, "rw").use { stream ->
        stream.setLength(0)
        stream.write(movie)
        stream.writeInt(freeSize)
        stream.writeBytes("free")
        stream.setLength(size.toLong())
    }
}

internal fun fixtureFileDigest(file: File): String = file.inputStream().use(::fixtureStreamDigest)

internal fun fixtureStreamDigest(input: InputStream): String {
    val sha = MessageDigest.getInstance("SHA-256")
    val block = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(block)
        if (count < 0) break
        sha.update(block, 0, count)
    }
    return sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
