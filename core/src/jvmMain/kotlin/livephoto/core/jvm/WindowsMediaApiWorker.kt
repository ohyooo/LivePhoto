package livephoto.core.jvm

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Isolated OS API worker. Internal decode evidence is not a public MediaBackend capability. */
internal object WindowsMediaApiWorker {
    @JvmStatic fun main(args: Array<String>) {
        val preflight = args.contentEquals(arrayOf("--preflight"))
        val decoderPreflight = args.contentEquals(arrayOf("--decoder-preflight"))
        val decode = if (!preflight && !decoderPreflight) WindowsNativeVideoDecode.request(args) else null
        val remux = if (!preflight && !decoderPreflight && decode == null) WindowsNativeRemux.request(args) else null
        if (!preflight && !decoderPreflight && decode == null && remux == null) {
            println("WINDOWS_MEDIA_API_PREFLIGHT=INVALID_ARGUMENT")
            exitProcess(2)
        }
        var enteredDecode = false
        var decodeResult: String? = null
        try {
            check(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") in setOf("amd64", "x86_64"))
            // Do not rely on the JDK's warning-only default or enable access in the primary CLI process.
            check(WindowsMediaApiWorker::class.java.module.isNativeAccessEnabled)
            val root = Path.of(System.getenv("SystemRoot") ?: error("Windows root is unavailable"))
            check(root.isAbsolute)
            val system = root.resolve("System32").toRealPath()
            Arena.ofConfined().use { arena ->
                fun library(name: String): SymbolLookup {
                    val path = system.resolve(name).toRealPath()
                    check(path.parent == system && Files.isRegularFile(path))
                    return SymbolLookup.libraryLookup(path, arena)
                }
                val ole = library("ole32.dll")
                val mf = library("mfplat.dll")
                val read = library("mfreadwrite.dll")
                check(read.find("MFCreateSourceReaderFromURL").isPresent)
                check(mf.find("MFCreateMediaType").isPresent)
                val linker = Linker.nativeLinker()
                fun function(lib: SymbolLookup, name: String, signature: FunctionDescriptor) =
                    linker.downcallHandle(lib.find(name).orElseThrow(), signature)
                val initialize = function(ole, "CoInitializeEx", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
                val uninitialize = function(ole, "CoUninitialize", FunctionDescriptor.ofVoid())
                val startup = function(mf, "MFStartup", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
                val shutdown = function(mf, "MFShutdown", FunctionDescriptor.of(ValueLayout.JAVA_INT))
                var comStarted = false; var mfStarted = false
                try {
                    check((initialize.invokeWithArguments(MemorySegment.NULL, 0) as Int) >= 0) // COINIT_MULTITHREADED; S_FALSE also requires balancing.
                    comStarted = true
                    check((startup.invokeWithArguments(0x00020070, 1) as Int) >= 0) // SDK MF_VERSION and MFSTARTUP_NOSOCKET.
                    mfStarted = true
                    if (decoderPreflight) decodeResult = WindowsNativeVideoDecode.decoderPreflight(arena, mf, ole)
                    if (decode != null) {
                        enteredDecode = true
                        decodeResult = WindowsNativeVideoDecode.run(decode, arena, mf, read, library("kernel32.dll"))
                    }
                    if (remux != null) {
                        enteredDecode = true
                        decodeResult = WindowsNativeRemux.run(remux, arena, mf, read, library("kernel32.dll"))
                    }
                } finally {
                    try { if (mfStarted) check((shutdown.invokeWithArguments() as Int) >= 0) }
                    finally { if (comStarted) uninitialize.invokeWithArguments() }
                }
            }
            if (preflight) println("WINDOWS_MEDIA_API_PREFLIGHT=SUCCESS scope=runtime-bootstrap-not-media-decode")
            else println(decodeResult ?: error("Missing decode evidence"))
            if (decoderPreflight && decodeResult?.contains("=UNAVAILABLE") == true) exitProcess(3)
        } catch (failure: Throwable) {
            // A bounded worker diagnostic, not an input path, user content or a simulated decoder result.
            val unavailable = !enteredDecode
            val site = failure.stackTrace.firstOrNull { it.className.startsWith("livephoto.core.jvm.WindowsNativeVideoDecode") ||
                it.className.startsWith("livephoto.core.jvm.WindowsNativeRemux") }
                ?.let { " line=${it.lineNumber}" } ?: ""
            val hresult = (failure as? WindowsNativeVideoDecode.Failure)?.let { " ${it.diagnostic}" } ?: ""
            println("${if (unavailable) "WINDOWS_MEDIA_API_PREFLIGHT=UNAVAILABLE" else "WINDOWS_MEDIA_API_DECODE=FAILED"} reason=${failure.javaClass.simpleName}$site$hresult")
            exitProcess(if (unavailable) 3 else 4)
        }
    }
}
