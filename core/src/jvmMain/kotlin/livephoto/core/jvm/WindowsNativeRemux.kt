package livephoto.core.jvm

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Internal compressed-sample experiment only, NOT a public remux capability or preservation proof. */
internal object WindowsNativeRemux {
    data class Request(val input: Path, val output: Path, val samples: Int, val maxFile: Long, val maxOutput: Long,
        val timeline: List<Pair<Long, Long>>, val timeGridHz: Int)
    fun request(args: Array<String>): Request? = try {
        if (args.size != 7 || args[0] != "--remux-video-private") null else {
            val input = Path.of(args[1]); val output = Path.of(args[2])
            val samples = args[3].toIntOrNull(); val file = args[4].toLongOrNull(); val limit = args[5].toLongOrNull()
            val times = if (args[6].length <= 3000) args[6].split(',').map { field ->
                val pair = field.split(':'); check(pair.size == 2)
                pair[0].toLong() to pair[1].toLong()
            } else emptyList()
            val validTimes = times.size == samples && times.isNotEmpty() && times.first().first == 0L &&
                times.all { it.first >= 0 && it.second > 0 && it.first <= Long.MAX_VALUE - it.second } &&
                times.zipWithNext().all { (a, b) -> a.first + a.second == b.first }
            fun gcd(a: Long, b: Long): Long {
                var left = a; var right = b
                while (right != 0L) { val next = left % right; left = right; right = next }
                return left
            }
            val grid = times.fold(0L) { a, b -> gcd(a, b.second) }
            val clock = if (validTimes && grid > 0 && 10_000_000L % grid == 0L) 10_000_000L / grid else 0L
            if (!input.isAbsolute || !output.isAbsolute || input.normalize() == output.normalize() ||
                output.fileName.toString() != "remux.mp4" || input.normalize().parent != output.normalize().parent ||
                samples == null || samples !in 1..64 || file == null || file !in 1..8_000_000 ||
                limit == null || limit !in 1..16_065_536 || !validTimes || clock !in 1..120) null
            else Request(input, output, samples, file, limit, times, clock.toInt())
        }
    } catch (_: Exception) { null }

    fun run(request: Request, arena: Arena, mf: SymbolLookup, read: SymbolLookup, kernel: SymbolLookup): String {
        val path = request.input.toRealPath()
        check(Files.isRegularFile(path) && Files.size(path) in 1..request.maxFile)
        check(request.output.parent.toRealPath() == path.parent)
        // CREATE_NEW refuses existing outputs before touching the native sink writer.
        Files.createFile(request.output)
        var completed = false
        try {
            val result = WindowsNativeVideoDecode.bounded(arena, kernel) { api ->
                fun wide(path: Path): MemorySegment {
                    val text = path.toString(); check(text.length <= 32_760)
                    return arena.allocate((text.length + 1L) * 2, 2).also { bytes ->
                        text.forEachIndexed { i, c -> bytes.set(ValueLayout.JAVA_SHORT, i * 2L, c.code.toShort()) }
                    }
                }
                val attributes = api.pointer { out -> api.hr(api.export(mf, "MFCreateAttributes", ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT).invokeWithArguments(out, 2)) }
                try {
                    // Identical compressed input/output is the documented no-transcode combination.
                    // https://learn.microsoft.com/en-us/windows/win32/medfound/using-the-sink-writer
                    for ((key, value) in listOf("98d5b065-1374-4847-8d5d-31520fee7156" to 1,
                        "a634a91c-822b-41b9-a494-4de4643612b0" to 0))
                        api.hr(api.method(attributes, 21, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
                            .invokeWithArguments(attributes, api.guid(key), value))
                    val reader = api.pointer { out -> api.hr(api.export(read, "MFCreateSourceReaderFromURL", ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeWithArguments(wide(path), attributes, out)) }
                    try {
                        val second = arena.allocate(ValueLayout.ADDRESS)
                        val secondStatus = api.method(reader, 5, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
                            .invokeWithArguments(reader, 1, 0, second) as Int
                        val extra = second.get(ValueLayout.ADDRESS, 0)
                        try { check(secondStatus.toUInt() == 0xc00d36b3u && extra.address() == 0L) }
                        finally { if (extra.address() != 0L) api.release(extra) }
                        // Only the sole native stream can be selected, never drop an audio stream.
                        api.hr(api.method(reader, 4, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT).invokeWithArguments(reader, -2, 0))
                        api.hr(api.method(reader, 4, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT).invokeWithArguments(reader, 0, 1))
                        val native = api.pointer { out -> api.hr(api.method(reader, 5, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS).invokeWithArguments(reader, 0, 0, out)) }
                        try {
                            check(api.getGuid(native, "48eba18e-f8c9-4687-bf11-0a74c9f96a8f") == "73646976-0000-0010-8000-00aa00389b71")
                            check(api.getGuid(native, "f7e34c9a-42e8-4714-b74b-cb29d72c35e5") == "34363248-0000-0010-8000-00aa00389b71")
                            api.hr(api.method(reader, 7, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                                .invokeWithArguments(reader, -4, MemorySegment.NULL, native))
                            // The sink derives its timescale from this descriptor. Its default
                            // average-rate clock rounded 40ms to 727/18181 in the actual VFR test.
                            // Use the exact integer-Hz caller timing grid, NOT inferred sample PTS
                            // or a CFR conversion; every output PTS/duration is independently tested.
                            api.hr(api.method(native, 22, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
                                .invokeWithArguments(native, api.guid("c459a2e8-3d2c-4e44-b132-fee5156c7bb0"),
                                    (request.timeGridHz.toLong() shl 32) or 1L))
                            val writer = api.pointer { out -> api.hr(api.export(read, "MFCreateSinkWriterFromURL", ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                                .invokeWithArguments(wide(request.output), MemorySegment.NULL, attributes, out)) }
                            try {
                                val streamOut = arena.allocate(ValueLayout.JAVA_INT)
                                api.hr(api.method(writer, 3, ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeWithArguments(writer, native, streamOut))
                                val stream = streamOut.get(ValueLayout.JAVA_INT, 0)
                                check(stream == 0)
                                api.hr(api.method(writer, 4, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                                    .invokeWithArguments(writer, stream, native, MemorySegment.NULL))
                                api.hr(api.method(writer, 5).invokeWithArguments(writer))
                                val actual = arena.allocate(ValueLayout.JAVA_INT); val flags = arena.allocate(ValueLayout.JAVA_INT)
                                val timestamp = arena.allocate(ValueLayout.JAVA_LONG); val time = arena.allocate(ValueLayout.JAVA_LONG)
                                val duration = arena.allocate(ValueLayout.JAVA_LONG); val length = arena.allocate(ValueLayout.JAVA_INT)
                                val out = arena.allocate(ValueLayout.ADDRESS)
                                val timeline = MessageDigest.getInstance("SHA-256"); val payload = MessageDigest.getInstance("SHA-256")
                                var count = 0; var previous = -1L; var bytes = 0L; var ended = false
                                for (iteration in 0 until request.samples * 4 + 32) {
                                    out.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL)
                                    val status = api.method(reader, 9, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                                        .invokeWithArguments(reader, -4, 0, actual, flags, timestamp, out)
                                    val sample = out.get(ValueLayout.ADDRESS, 0)
                                    try {
                                        api.hr(status)
                                        val bits = flags.get(ValueLayout.JAVA_INT, 0)
                                        check(bits and 2.inv() == 0) // No new streams, gaps or dynamic format changes.
                                        if (sample.address() != 0L) {
                                            check(++count <= request.samples)
                                            api.hr(api.method(sample, 35, ValueLayout.ADDRESS).invokeWithArguments(sample, time))
                                            api.hr(api.method(sample, 37, ValueLayout.ADDRESS).invokeWithArguments(sample, duration))
                                            val pts = time.get(ValueLayout.JAVA_LONG, 0); val delta = duration.get(ValueLayout.JAVA_LONG, 0)
                                            check(pts >= 0 && pts > previous && pts == timestamp.get(ValueLayout.JAVA_LONG, 0) && delta > 0)
                                            // Source Reader durations need not preserve VFR. Use only the
                                            // caller's independently parsed, contiguous exact-100ns table.
                                            val expected = request.timeline[count - 1]
                                            check(pts == expected.first)
                                            api.hr(api.method(sample, 38, ValueLayout.JAVA_LONG).invokeWithArguments(sample, expected.second))
                                            api.hr(api.method(sample, 45, ValueLayout.ADDRESS).invokeWithArguments(sample, length))
                                            bytes = Math.addExact(bytes, api.readPayload(sample, length.get(ValueLayout.JAVA_INT, 0), 1,
                                                request.maxFile.toInt(), payload).toLong())
                                            check(bytes <= request.maxFile + 65536)
                                            timeline.update(ByteBuffer.allocate(16).putLong(pts).putLong(expected.second).array())
                                            api.hr(api.method(writer, 6, ValueLayout.JAVA_INT, ValueLayout.ADDRESS).invokeWithArguments(writer, stream, sample))
                                            check(Files.size(request.output) <= request.maxOutput)
                                            previous = pts
                                        }
                                        if (bits and 2 != 0) { ended = true; break }
                                    } finally { if (sample.address() != 0L) api.release(sample) }
                                }
                                check(ended && count == request.samples)
                                api.hr(api.method(writer, 11).invokeWithArguments(writer))
                                check(Files.size(request.output) in 1..request.maxOutput)
                                fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                                "WINDOWS_MEDIA_API_REMUX=SUCCESS scope=private-compressed-video-not-preservation samples=$count timelineSha256=${hex(timeline)} payloadBytes=$bytes payloadSha256=${hex(payload)}"
                            } finally { api.release(writer) }
                        } finally { api.release(native) }
                    } finally { api.release(reader) }
                } finally { api.release(attributes) }
            }
            completed = true
            return result
        } finally { if (!completed) Files.deleteIfExists(request.output) }
    }
}
