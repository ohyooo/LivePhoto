package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Restores only proved original ESDS hint bytes in backend-owned temporary output, before Core staging. */
internal object AacRemuxMetadata {
    suspend fun restore(before: VideoStructure, path: Path, context: Context): Boolean {
        val originals = before.tracks.filter { it.handler == "soun" }
        if (originals.isEmpty()) return false
        val source = FileBinarySource(path)
        val patches = mutableListOf<Pair<ByteRange, Bytes>>()
        try {
            val reader = BinaryReader(source, context)
            val actual = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            if (actual.tracks.map { it.trackId } != before.tracks.map { it.trackId }) fail("POSTCONDITION_FAILED", "Remux changed the track graph before AAC metadata restoration", Stage.Verify)
            val boxes = BmffReader(reader, ParseBudget(context))
            suspend fun children(box: BmffBox) = boxes.readBoxes(box.payload, 1u).orThrow()
            fun one(values: List<BmffBox>, type: String) = values.singleOrNull { it.type == type }
                ?: fail("POSTCONDITION_FAILED", "AAC remux requires a unique parsed descriptor path", Stage.Verify)
            val root = boxes.readBoxes(actual.range).orThrow()
            val tracks = children(one(root, "moov")).filter { it.type == "trak" }
            if (tracks.size != actual.tracks.size) fail("POSTCONDITION_FAILED", "AAC remux container/track graph disagrees", Stage.Verify)
            for (original in originals) {
                checkCancelled(context)
                val index = actual.tracks.indexOfFirst { it.trackId == original.trackId }
                val output = actual.tracks[index]
                if (original.codecConfiguration == output.codecConfiguration) continue
                val patch = AacDescriptorHints.patchRange(original.codecConfiguration, output.codecConfiguration, ParseBudget(context)).orThrow()
                val mdia = one(children(tracks[index]), "mdia")
                val minf = one(children(mdia), "minf")
                val stbl = one(children(minf), "stbl")
                val stsd = one(children(stbl), "stsd")
                if (stsd.payload.length < 8uL) fail("POSTCONDITION_FAILED", "Truncated AAC sample description", Stage.Verify)
                val entry = boxes.readBoxes(ByteRange(stsd.payload.offset + 8uL, stsd.payload.length - 8uL), 6u).orThrow().singleOrNull()
                    ?: fail("POSTCONDITION_FAILED", "AAC sample description is not unique", Stage.Verify)
                if (entry.type != "mp4a" || entry.payload.length < 28uL) fail("POSTCONDITION_FAILED", "AAC sample entry is outside the verified profile", Stage.Verify)
                val esds = one(boxes.readBoxes(ByteRange(entry.payload.offset + 28uL, entry.payload.length - 28uL), 7u).orThrow(), "esds")
                if (esds.payload.length != output.codecConfiguration.size.toULong() || reader.readExactly(esds.payload.offset, output.codecConfiguration.size.toUInt()).orThrow() != output.codecConfiguration)
                    fail("POSTCONDITION_FAILED", "AAC descriptor extent disagrees with independently parsed configuration", Stage.Verify)
                patches += ByteRange(checkedAdd(esds.payload.offset, patch.offset), patch.length) to original.codecConfiguration.slice(patch.offset.toInt(), patch.endExclusive.toInt())
            }
            reader.validateIdentity().orThrow()
        } finally { source.close() }
        if (patches.isEmpty()) return false
        try {
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                for ((range, bytes) in patches) {
                    val buffer = ByteBuffer.wrap(bytes.toByteArray()); var offset = range.offset.toLong()
                    while (buffer.hasRemaining()) {
                        checkCancelled(context)
                        val written = channel.write(buffer, offset)
                        if (written <= 0) fail("IO_WRITE_FAILED", "AAC metadata hint restoration made no progress", Stage.Remux)
                        offset += written
                    }
                }
                channel.force(true)
            }
        } catch (fault: CoreFault) { throw fault }
        catch (_: Exception) { fail("IO_WRITE_FAILED", "AAC temporary metadata hint restoration failed", Stage.Remux) }
        return patches.isNotEmpty()
    }
}
