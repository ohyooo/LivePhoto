package livephoto.core

/** Reproducible small mutations supplement handwritten positive and negative fixtures. */
internal fun boundedMutations(original: ByteArray): List<ByteArray> {
    val results = mutableListOf<ByteArray>()
    for (length in 0 until minOf(original.size, 32)) {
        results += original.copyOf(length)
    }
    var seed = 0x51ed270bu
    repeat(64) {
        val changed = original.copyOf()
        repeat(1 + it % 3) {
            seed = seed * 1_664_525u + 1_013_904_223u
            val position = (seed % changed.size.toUInt()).toInt()
            seed = seed * 1_664_525u + 1_013_904_223u
            changed[position] = (seed shr 24).toByte()
        }
        results += changed
    }
    return results
}
