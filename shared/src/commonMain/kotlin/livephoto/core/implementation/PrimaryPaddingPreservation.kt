package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*

/** Cleaning/reprojection must not undo Create's byte-preserved opaque image suffix. */
internal suspend fun requireDiscardablePrimaryPadding(session: SourceSession) {
    if (session.jpeg == null || session.sef != null || session.gainMaps.isNotEmpty()) return
    val ranges = session.bindings.filter { it.protocol in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2, ProtocolIds.Oplus) }
        .mapNotNull { it.padding }.distinct()
    for (range in ranges) {
        var offset = range.offset
        while (offset < range.endExclusive) {
            checkCancelled(session.reader.context)
            val bytes = session.reader.readBuffer(offset, minOf(65536uL, range.endExclusive - offset).toUInt()).orThrow()
            if (bytes.size == 0) fail("UNEXPECTED_EOF", "Image padding preservation check was truncated", Stage.Read)
            if ((0 until bytes.size).any { bytes[it] != 0.toByte() })
                fail("UNSAFE_METADATA_REWRITE", "Cleaning or conversion cannot discard an opaque image suffix; raw extraction and preserving the carrier remain available",
                    Stage.Plan, Location(source = session.snapshot.identities.single().id, range = range, selector = "jpeg:opaque-suffix"))
            offset = checkedAdd(offset, bytes.size.toULong())
        }
    }
}
