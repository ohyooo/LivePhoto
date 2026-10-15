package livephoto.core.binary

import livephoto.core.*

/** SHA-256 over exact bytes, with a fixed 64-byte block and a checked 64-bit bit count. */
internal class Sha256 {
    private val state = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(), 0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
    private val buffer = ByteArray(64)
    private val schedule = IntArray(64)
    private var buffered = 0
    private var length = 0uL
    private var result: Digest? = null

    fun update(bytes: Bytes) {
        if (result != null) fail("INVALID_ARGUMENT", "SHA-256 is already finalized")
        if (bytes.size.toULong() > ULong.MAX_VALUE / 8uL - length) fail("INTEGER_OVERFLOW", "SHA-256 bit count exceeds UInt64")
        length += bytes.size.toULong()
        for (index in 0 until bytes.size) {
            buffer[buffered++] = bytes[index]
            if (buffered == 64) { compress(); buffered = 0 }
        }
    }

    fun finish(): Digest {
        result?.let { return it }
        val bits = length * 8uL
        buffer[buffered++] = 0x80.toByte()
        if (buffered > 56) {
            while (buffered < 64) buffer[buffered++] = 0
            compress(); buffered = 0
        }
        while (buffered < 56) buffer[buffered++] = 0
        for (index in 0 until 8) buffer[56 + index] = (bits shr ((7 - index) * 8)).toByte()
        compress()
        val alphabet = "0123456789abcdef"
        val hex = buildString(64) {
            for (word in state) for (shift in 28 downTo 0 step 4) append(alphabet[(word ushr shift) and 15])
        }
        return Digest(hex).also { result = it }
    }

    private fun compress() {
        val w = schedule
        for (i in 0 until 16) {
            val offset = i * 4
            w[i] = ((buffer[offset].toInt() and 255) shl 24) or ((buffer[offset + 1].toInt() and 255) shl 16) or
                ((buffer[offset + 2].toInt() and 255) shl 8) or (buffer[offset + 3].toInt() and 255)
        }
        for (i in 16 until 64) {
            val a = w[i - 15]; val b = w[i - 2]
            w[i] = w[i - 16] + (rotate(a, 7) xor rotate(a, 18) xor (a ushr 3)) + w[i - 7] + (rotate(b, 17) xor rotate(b, 19) xor (b ushr 10))
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in 0 until 64) {
            val t1 = h + (rotate(e, 6) xor rotate(e, 11) xor rotate(e, 25)) + ((e and f) xor (e.inv() and g)) + K[i] + w[i]
            val t2 = (rotate(a, 2) xor rotate(a, 13) xor rotate(a, 22)) + ((a and b) xor (a and c) xor (b and c))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    private fun rotate(value: Int, bits: Int): Int = (value ushr bits) or (value shl (32 - bits))

    private companion object {
        val K = intArrayOf(
            0x428a2f98,0x71374491,0xb5c0fbcf.toInt(),0xe9b5dba5.toInt(),0x3956c25b,0x59f111f1,0x923f82a4.toInt(),0xab1c5ed5.toInt(),
            0xd807aa98.toInt(),0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe.toInt(),0x9bdc06a7.toInt(),0xc19bf174.toInt(),
            0xe49b69c1.toInt(),0xefbe4786.toInt(),0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152.toInt(),0xa831c66d.toInt(),0xb00327c8.toInt(),0xbf597fc7.toInt(),0xc6e00bf3.toInt(),0xd5a79147.toInt(),0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e.toInt(),0x92722c85.toInt(),
            0xa2bfe8a1.toInt(),0xa81a664b.toInt(),0xc24b8b70.toInt(),0xc76c51a3.toInt(),0xd192e819.toInt(),0xd6990624.toInt(),0xf40e3585.toInt(),0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814.toInt(),0x8cc70208.toInt(),0x90befffa.toInt(),0xa4506ceb.toInt(),0xbef9a3f7.toInt(),0xc67178f2.toInt())
    }
}

internal suspend fun sha256Range(reader: BinaryReader, range: ByteRange): CoreResult<Digest> = attempt {
    checkedRange(range.offset, range.length, reader.identity().orThrow().size)
    val hash = Sha256()
    var completed = 0uL
    while (completed < range.length) {
        checkCancelled(reader.context)
        val count = minOf(range.length - completed, 64uL * 1024uL).toUInt()
        hash.update(reader.readBuffer(checkedAdd(range.offset, completed), count).orThrow())
        completed += count.toULong()
    }
    reader.validateIdentity().orThrow()
    hash.finish()
}
