package livephoto.core.jvm

import java.lang.foreign.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Narrow internal SourceReader experiment, confined to the native-enabled worker process. */
internal object WindowsNativeVideoDecode {
    class Failure(val diagnostic: String) : Exception()
    data class Request(val path: Path, val maxFrames: Int, val maxBuffer: Int, val maxFile: Long,
        val payloadEvidence: Boolean = false, val selectedIndex: Int? = null, val output: Path? = null)

    fun request(args: Array<String>): Request? = try {
        val selected = args.firstOrNull() == "--select-video-frame"
        if (args.size != (if (selected) 7 else 5) || args[0] !in setOf("--decode-video", "--decode-video-payload", "--select-video-frame")) null else {
            val path = Path.of(args[1])
            val frames = args[2].toIntOrNull()
            val buffer = args[3].toIntOrNull()
            val file = args[4].toLongOrNull()
            if (!path.isAbsolute || frames == null || frames !in 1..10_000 ||
                buffer == null || buffer !in 1..32_000_000 || file == null || file !in 1..128_000_000) null
            else if (selected) {
                val index = args[5].toIntOrNull()
                val output = Path.of(args[6])
                if (index == null || index !in 0 until frames || frames > 64 || !output.isAbsolute ||
                    output.fileName.toString() != "selected.nv12" || output.normalize().parent != path.normalize().parent) null
                else Request(path, frames, buffer, file, selectedIndex = index, output = output)
            } else Request(path, frames, buffer, file, args[0] == "--decode-video-payload")
        }
    } catch (_: Exception) { null }

    private val major = "48eba18e-f8c9-4687-bf11-0a74c9f96a8f"
    private val subtype = "f7e34c9a-42e8-4714-b74b-cb29d72c35e5"
    private val video = "73646976-0000-0010-8000-00aa00389b71"
    private val h264 = "34363248-0000-0010-8000-00aa00389b71"
    private val nv12 = "3231564e-0000-0010-8000-00aa00389b71"
    private val frameSize = "1652c33d-d6b2-4012-b834-72030849a37d"
    private const val FIRST_VIDEO = -4

    fun decoderPreflight(arena: Arena, mf: SymbolLookup, ole: SymbolLookup): String {
        val api = Api(arena)
        val guidLayout = MemoryLayout.structLayout(ValueLayout.JAVA_INT, ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_SHORT, MemoryLayout.sequenceLayout(8, ValueLayout.JAVA_BYTE))
        fun type(sub: String) = arena.allocate(32, 4).also {
            it.asSlice(0, 16).copyFrom(api.guid(video)); it.asSlice(16, 16).copyFrom(api.guid(sub))
        }
        val out = arena.allocate(ValueLayout.ADDRESS)
        val countOut = arena.allocate(ValueLayout.JAVA_INT)
        val result = api.export(mf, "MFTEnumEx", ValueLayout.JAVA_INT, guidLayout, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            .invokeWithArguments(api.guid("d6c02d4b-6833-45b4-971a-05a4b04bab91"), 0x43, type(h264), type(nv12), out, countOut)
        val array = out.get(ValueLayout.ADDRESS, 0)
        val count = countOut.get(ValueLayout.JAVA_INT, 0)
        try {
            check(count in 0..128)
            api.hr(result)
            check(count == 0 || array.address() != 0L)
            return "WINDOWS_MEDIA_API_DECODER=${if (count > 0) "SUCCESS" else "UNAVAILABLE"} scope=registered-software-avc-to-nv12 candidates=$count"
        } finally {
            if (array.address() != 0L) {
                try {
                    if (count in 0..128) for (index in 0 until count) {
                        val activate = array.reinterpret(count * 8L).get(ValueLayout.ADDRESS, index * 8L)
                        if (activate.address() != 0L) api.release(activate)
                    }
                } finally {
                    Linker.nativeLinker().downcallHandle(ole.find("CoTaskMemFree").orElseThrow(),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)).invokeWithArguments(array)
                }
            }
        }
    }

    fun run(request: Request, arena: Arena, mf: SymbolLookup, read: SymbolLookup, kernel: SymbolLookup): String {
        // No URLs or extension-based trust. A caller must provide an existing local file within bounds.
        val path = request.path.toRealPath()
        check(Files.isRegularFile(path) && Files.size(path) in 1..request.maxFile)
        return bounded(arena, kernel) { api -> decode(request, path, api, mf, read) }
    }

    internal fun <T> bounded(arena: Arena, kernel: SymbolLookup, action: (Api) -> T): T {
        val api = Api(arena)
        // The worker's committed native + managed memory is bounded independently of its JVM heap.
        val job = api.export(kernel, "CreateJobObjectW", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            .invokeWithArguments(MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
        check(job.address() != 0L)
        try {
            val limits = arena.allocate(144, 8)
            limits.set(ValueLayout.JAVA_INT, 16, 0x100) // JOB_OBJECT_LIMIT_PROCESS_MEMORY, no kill-on-close flag.
            limits.set(ValueLayout.JAVA_LONG, 112, 512L * 1024 * 1024)
            check((api.export(kernel, "SetInformationJobObject", ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
                .invokeWithArguments(job, 9, limits, 144) as Int) != 0)
            check((api.export(kernel, "AssignProcessToJobObject", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                .invokeWithArguments(job, MemorySegment.ofAddress(-1L)) as Int) != 0)
            return action(api)
        } finally {
            check((api.export(kernel, "CloseHandle", ValueLayout.JAVA_INT, ValueLayout.ADDRESS).invokeWithArguments(job) as Int) != 0)
        }
    }

    private fun decode(request: Request, path: Path, api: Api, mf: SymbolLookup, read: SymbolLookup): String {
        val attributes = api.pointer { out -> api.hr(api.export(mf, "MFCreateAttributes", ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT).invokeWithArguments(out, 1)) }
        try {
            api.hr(api.method(attributes, 21, ValueLayout.ADDRESS, ValueLayout.JAVA_INT).invokeWithArguments(attributes,
                api.guid("aa456cfd-3943-4a1e-a77d-1838c0ea2e35"), 1)) // disable DXVA, software-only.
            val text = path.toString()
            check(text.length <= 32_760)
            val wide = api.arena.allocate((text.length + 1L) * 2, 2)
            text.forEachIndexed { i, c -> wide.set(ValueLayout.JAVA_SHORT, i * 2L, c.code.toShort()) }
            val reader = api.pointer { out -> api.hr(api.export(read, "MFCreateSourceReaderFromURL", ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeWithArguments(wide, attributes, out)) }
            try {
                api.hr(api.method(reader, 4, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT).invokeWithArguments(reader, -2, 0))
                api.hr(api.method(reader, 4, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT).invokeWithArguments(reader, FIRST_VIDEO, 1))
                val native = api.pointer { out -> api.hr(api.method(reader, 5, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS).invokeWithArguments(reader, FIRST_VIDEO, 0, out)) }
                try {
                    check(api.getGuid(native, major) == video && api.getGuid(native, subtype) == h264)
                    api.dimensions(native, request.maxBuffer)
                    if (request.selectedIndex != null) api.frameProfile(native)
                } finally { api.release(native) }
                val type = api.pointer { out -> api.hr(api.export(mf, "MFCreateMediaType", ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS).invokeWithArguments(out)) }
                try {
                    for ((key, value) in listOf(major to video, subtype to nv12)) api.hr(api.method(type, 24,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeWithArguments(type, api.guid(key), api.guid(value)))
                    api.hr(api.method(reader, 7, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                        .invokeWithArguments(reader, FIRST_VIDEO, MemorySegment.NULL, type))
                } finally { api.release(type) }
                var decodedInterlace: Int? = null
                fun current(): Pair<Int, Int> {
                    val decoded = api.pointer { out -> api.hr(api.method(reader, 6, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS).invokeWithArguments(reader, FIRST_VIDEO, out)) }
                    try {
                        check(api.getGuid(decoded, major) == video && api.getGuid(decoded, subtype) == nv12)
                        if (request.selectedIndex != null) decodedInterlace = api.frameProfile(decoded)
                        return api.dimensions(decoded, request.maxBuffer)
                    } finally { api.release(decoded) }
                }
                val dimensions = current()
                val actual = api.arena.allocate(ValueLayout.JAVA_INT)
                val flags = api.arena.allocate(ValueLayout.JAVA_INT)
                val timestamp = api.arena.allocate(ValueLayout.JAVA_LONG)
                val sampleTime = api.arena.allocate(ValueLayout.JAVA_LONG)
                val length = api.arena.allocate(ValueLayout.JAVA_INT)
                val out = api.arena.allocate(ValueLayout.ADDRESS)
                val digest = MessageDigest.getInstance("SHA-256")
                val payloadDigest = MessageDigest.getInstance("SHA-256")
                var payloadBytes = 0L
                var selectedBytes: ByteArray? = null
                var selectedTime = -1L
                var frames = 0; var previous = -1L; var ended = false
                for (iteration in 0 until request.maxFrames * 4 + 32) {
                    out.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL)
                    val status = api.method(reader, 9, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                        .invokeWithArguments(reader, FIRST_VIDEO, 0, actual, flags, timestamp, out)
                    val sample = out.get(ValueLayout.ADDRESS, 0)
                    try {
                        api.hr(status)
                        val bits = flags.get(ValueLayout.JAVA_INT, 0)
                        check(bits and (2 or 16 or 32 or 256).inv() == 0) // Reject error/new-stream/unknown effects.
                        if (bits and (16 or 32) != 0) check(current() == dimensions)
                        if (sample.address() != 0L) {
                            check(++frames <= request.maxFrames)
                            if (request.selectedIndex != null) api.progressiveSample(sample, decodedInterlace)
                            api.hr(api.method(sample, 35, ValueLayout.ADDRESS).invokeWithArguments(sample, sampleTime))
                            api.hr(api.method(sample, 45, ValueLayout.ADDRESS).invokeWithArguments(sample, length))
                            val pts = sampleTime.get(ValueLayout.JAVA_LONG, 0)
                            check(pts == timestamp.get(ValueLayout.JAVA_LONG, 0) && pts >= 0 && pts >= previous)
                            check(length.get(ValueLayout.JAVA_INT, 0) in 1..request.maxBuffer)
                            payloadBytes = Math.addExact(payloadBytes, api.readPayload(sample, length.get(ValueLayout.JAVA_INT, 0),
                                dimensions.first.toLong() * dimensions.second * 3 / 2, request.maxBuffer, payloadDigest).toLong())
                            if (frames - 1 == request.selectedIndex) {
                                selectedBytes = api.packedFrame(sample, dimensions.first, dimensions.second, request.maxBuffer)
                                selectedTime = pts
                            }
                            previous = pts
                            digest.update(ByteBuffer.allocate(8).putLong(pts).array())
                        }
                        if (bits and 2 != 0) { ended = true; break }
                    } finally { if (sample.address() != 0L) api.release(sample) }
                }
                val finalDimensions = current()
                if (!ended || frames == 0 || finalDimensions != dimensions)
                    throw Failure("state_frames=${frames}_eos=${ended}_size=${dimensions.first}x${dimensions.second}_final=${finalDimensions.first}x${finalDimensions.second}")
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                val payload = if (request.payloadEvidence) " payloadBytes=$payloadBytes payloadSha256=${payloadDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }}" else ""
                if (request.selectedIndex != null) {
                    val bytes = selectedBytes ?: error("Selected presentation frame was not decoded")
                    val output = request.output!!
                    check(output.parent.toRealPath() == path.parent && !Files.exists(output))
                    Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    val selectedHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    return "WINDOWS_MEDIA_API_FRAME=SUCCESS frames=$frames width=${dimensions.first} height=${dimensions.second} ptsSha256=$hash index=${request.selectedIndex} time100ns=$selectedTime bytes=${bytes.size} sha256=$selectedHash layout=packed-nv12"
                }
                return "WINDOWS_MEDIA_API_DECODE=SUCCESS scope=selected-avc-video frames=$frames width=${dimensions.first} height=${dimensions.second} ptsSha256=$hash$payload"
            } finally { api.release(reader) }
        } finally { api.release(attributes) }
    }

    internal class Api(val arena: Arena) {
        private val linker = Linker.nativeLinker()
        fun export(lib: SymbolLookup, name: String, result: MemoryLayout, vararg args: MemoryLayout) =
            linker.downcallHandle(lib.find(name).orElseThrow(), FunctionDescriptor.of(result, *args))
        fun method(pointer: MemorySegment, slot: Int, vararg args: MemoryLayout): java.lang.invoke.MethodHandle {
            check(pointer.address() != 0L && slot in 0..46)
            val vtable = pointer.reinterpret(8).get(ValueLayout.ADDRESS, 0)
            check(vtable.address() != 0L)
            val address = vtable.reinterpret((slot + 1L) * 8).get(ValueLayout.ADDRESS, slot * 8L)
            check(address.address() != 0L)
            return linker.downcallHandle(address, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, *args))
        }
        fun hr(result: Any?) { if ((result as Int) < 0) throw Failure("HRESULT_${result.toUInt().toString(16).padStart(8, '0')}") }
        fun release(pointer: MemorySegment) { method(pointer, 2).invokeWithArguments(pointer) }
        fun pointer(call: (MemorySegment) -> Unit): MemorySegment {
            val out = arena.allocate(ValueLayout.ADDRESS)
            try { call(out) } catch (failure: Throwable) {
                val value = out.get(ValueLayout.ADDRESS, 0)
                if (value.address() != 0L) release(value)
                throw failure
            }
            return out.get(ValueLayout.ADDRESS, 0).also { check(it.address() != 0L) }
        }
        // Read valid contiguous sample bytes only while locked. This is raw buffer evidence,
        // not a stride/color interpretation or a public extracted image.
        // https://learn.microsoft.com/en-us/windows/win32/api/mfobjects/nf-mfobjects-imfmediabuffer-lock
        fun readPayload(sample: MemorySegment, expectedLength: Int, minimum: Long, limit: Int, digest: MessageDigest): Int {
            val buffer = pointer { out -> hr(method(sample, 41, ValueLayout.ADDRESS).invokeWithArguments(sample, out)) }
            try {
                val bytes = arena.allocate(ValueLayout.ADDRESS)
                val maximum = arena.allocate(ValueLayout.JAVA_INT)
                val current = arena.allocate(ValueLayout.JAVA_INT)
                hr(method(buffer, 3, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                    .invokeWithArguments(buffer, bytes, maximum, current))
                try {
                    val size = current.get(ValueLayout.JAVA_INT, 0)
                    check(size == expectedLength && size.toLong() >= minimum && size in 1..limit)
                    check(maximum.get(ValueLayout.JAVA_INT, 0) in size..limit)
                    val pointer = bytes.get(ValueLayout.ADDRESS, 0)
                    check(pointer.address() != 0L)
                    val memory = pointer.reinterpret(size.toLong())
                    digest.update(ByteBuffer.allocate(8).putLong(size.toLong()).array())
                    var offset = 0L
                    while (offset < size) {
                        val count = minOf(65_536L, size - offset)
                        digest.update(memory.asSlice(offset, count).toArray(ValueLayout.JAVA_BYTE))
                        offset += count
                    }
                    return size
                } finally { hr(method(buffer, 4).invokeWithArguments(buffer)) }
            } finally { release(buffer) }
        }
        fun guid(text: String): MemorySegment {
            val id = UUID.fromString(text)
            val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((id.mostSignificantBits ushr 32).toInt()).putShort((id.mostSignificantBits ushr 16).toShort())
                .putShort(id.mostSignificantBits.toShort()).order(ByteOrder.BIG_ENDIAN).putLong(id.leastSignificantBits).array()
            return arena.allocate(16, 4).also { it.copyFrom(MemorySegment.ofArray(bytes)) }
        }
        fun getGuid(pointer: MemorySegment, key: String): String {
            val out = arena.allocate(16, 4)
            hr(method(pointer, 10, ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeWithArguments(pointer, guid(key), out))
            val buffer = ByteBuffer.wrap(out.toArray(ValueLayout.JAVA_BYTE)).order(ByteOrder.LITTLE_ENDIAN)
            val msb = ((buffer.int.toLong() and 0xffffffffL) shl 32) or ((buffer.short.toLong() and 65535L) shl 16) or (buffer.short.toLong() and 65535L)
            return UUID(msb, buffer.order(ByteOrder.BIG_ENDIAN).long).toString()
        }
        // Missing MF attributes are not a color proof: the parent independently requires
        // explicit SPS/VUI before interpreting the packed pixels. Present contradictions fail.
        private fun uint(type: MemorySegment, key: String): Int? {
            val out = arena.allocate(ValueLayout.JAVA_INT)
            val result = method(type, 7, ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeWithArguments(type, guid(key), out) as Int
            if (result.toUInt() == 0xc00d36e6u) return null // MF_E_ATTRIBUTENOTFOUND, never a value default.
            hr(result)
            return out.get(ValueLayout.JAVA_INT, 0)
        }
        fun frameProfile(type: MemorySegment): Int? {
            for ((key, expected) in listOf(
                "dbfbe4d7-0740-4ee0-8192-850ab0e21935" to 2, // BT.709 primaries
                "5fb0fce9-be5c-4935-a811-ec838f8eed93" to 5, // BT.709 transfer
                "3e23d450-2c75-4d25-a00e-b91670d12327" to 1, // BT.709 matrix
                "c21b8ee5-b956-4071-8daf-325edf5cab11" to 2)) { // studio range
                val actual = uint(type, key)
                if (actual != null && actual != expected) throw Failure("frame_profile_${key.take(8)}=$actual")
            }
            val siting = uint(type, "65df2370-c773-4c33-aa64-843e068efb0c")
            // MPEG-2 left chroma: horizontally cosited, vertically centered, aligned UV.
            if (siting != null && siting !in setOf(5, 13)) throw Failure("frame_chroma_siting=$siting")
            val aspect = arena.allocate(ValueLayout.JAVA_LONG)
            val result = method(type, 8, ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeWithArguments(type,
                guid("c6376a1e-8d0a-4027-be45-6d9a0ad39bb6"), aspect) as Int
            if (result.toUInt() != 0xc00d36e6u) {
                hr(result); check(aspect.get(ValueLayout.JAVA_LONG, 0) == 0x100000001L)
            }
            val interlace = uint(type, "e2724bb8-e676-4806-b4b2-a8d6efb44ccd")
            if (interlace != null && interlace !in setOf(2, 7)) throw Failure("frame_interlace=$interlace")
            return interlace
        }
        fun progressiveSample(sample: MemorySegment, mediaMode: Int?) {
            // A generic mixed media type is not a frame verdict. Require the actual
            // sample's explicit FALSE unless its current media type is Progressive.
            // https://learn.microsoft.com/en-us/windows/win32/medfound/mfsampleextension-interlaced-attribute
            val interlaced = uint(sample, "b1d5830a-deb8-40e3-90fa-389943716461")
            if (interlaced != 0 && !(interlaced == null && mediaMode == 2)) throw Failure("sample_interlace_not_progressive")
            val single = uint(sample, "9d85f816-658b-455a-bde0-9fa7e15ab8f9")
            if (single != null && single != 0) throw Failure("sample_single_field")
        }
        // IMF2DBuffer's packed representation removes row padding without treating the
        // native surface's current/max length as a stride. No plain Lock fallback.
        // https://learn.microsoft.com/en-us/windows/win32/api/mfobjects/nn-mfobjects-imf2dbuffer
        fun packedFrame(sample: MemorySegment, width: Int, height: Int, limit: Int): ByteArray {
            check(width in 48..1024 && height in 48..1024 && width % 2 == 0 && height % 2 == 0)
            val size = Math.multiplyExact(Math.multiplyExact(width, height), 3) / 2
            check(size <= limit)
            val buffer = pointer { out -> hr(method(sample, 41, ValueLayout.ADDRESS).invokeWithArguments(sample, out)) }
            try {
                val twoD = pointer { out -> hr(method(buffer, 0, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                    .invokeWithArguments(buffer, guid("7dc9d5f9-9ed9-44ec-9bbf-0600bb589fbb"), out)) }
                try {
                    val length = arena.allocate(ValueLayout.JAVA_INT)
                    hr(method(twoD, 7, ValueLayout.ADDRESS).invokeWithArguments(twoD, length))
                    check(length.get(ValueLayout.JAVA_INT, 0) == size)
                    val memory = arena.allocate(size.toLong(), 16)
                    hr(method(twoD, 8, ValueLayout.ADDRESS, ValueLayout.JAVA_INT).invokeWithArguments(twoD, memory, size))
                    return memory.toArray(ValueLayout.JAVA_BYTE)
                } finally { release(twoD) }
            } finally { release(buffer) }
        }
        fun dimensions(type: MemorySegment, maxBuffer: Int): Pair<Int, Int> {
            val out = arena.allocate(ValueLayout.JAVA_LONG)
            hr(method(type, 8, ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeWithArguments(type, guid(frameSize), out))
            val packed = out.get(ValueLayout.JAVA_LONG, 0)
            val width = (packed ushr 32).toInt(); val height = packed.toInt()
            // Official software H.264 decoder limits; do not report EOS-with-no-frames as success.
            check(width in 48..4096 && height in 48..2304 && width.toLong() * height * 3 / 2 <= maxBuffer)
            return width to height
        }
    }
}
