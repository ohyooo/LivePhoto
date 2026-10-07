package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*

/** Constant 40-byte edit envelope: zero uses a one-entry elst plus owned zero-filled free padding. */
internal object AppleKeyEditTable {
    fun bytes(delay: ULong): Bytes {
        if (delay >= UInt.MAX_VALUE.toULong()) fail("VALUE_NOT_REPRESENTABLE", "Apple key edit exceeds fixed 32-bit ticks", Stage.Plan)
        fun u32(value: ULong) = unsignedBytes(value, 4, Endian.Big).toByteArray()
        val entry = u32(1uL) + u32(0uL) + u32(0x10000uL)
        return Bytes(if (delay == 0uL) u32(28uL) + "elst".encodeToByteArray() + u32(0uL) + u32(1uL) + entry +
            u32(12uL) + "free".encodeToByteArray() + u32(0uL)
        else u32(40uL) + "elst".encodeToByteArray() + u32(0uL) + u32(2uL) + u32(delay) + u32(UInt.MAX_VALUE.toULong()) + u32(0x10000uL) + entry)
    }
}
