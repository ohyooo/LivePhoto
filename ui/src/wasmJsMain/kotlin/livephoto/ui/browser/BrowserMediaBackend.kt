@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package livephoto.ui.browser

import kotlin.js.*
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import livephoto.core.*
import livephoto.core.ffmpeg.*

private external interface MemoryProcess : JsAny {
    val code: Int?
    val output: String
    val outputLimited: Boolean
    val cancelled: Boolean
    val timedOut: Boolean
    val ioFailed: Boolean
    val bytes: Int8Array?
}
private fun argumentsArray(): JsAny = js("[]")
private fun appendArgument(args: JsAny, value: String): Unit = js("{ args.push(value); }")
private fun executeMemory(args: JsAny, input: Int8Array, previous: Int8Array?, name: String, timeout: Double, cancelled: () -> Boolean): Promise<MemoryProcess> =
    js("import(/* webpackIgnore: true */ new URL('./media/core-runtime.mjs', document.baseURI).href).then(module => module.executeCoreFfmpeg(JSON.stringify(args), input, previous, name, timeout, cancelled))")
private fun typedBytes(bytes: Bytes): Int8Array = Int8Array(bytes.size).also { array ->
    bytes.toByteArray().forEachIndexed { index, value -> browserSetByte(array, index, value) }
}

/** Same common backend as the JS library, with Wasm's typed boundary only. */
internal fun browserMediaCore(): LivePhotoCore = DefaultLivePhotoCore(BrowserFfmpegBackend(MemoryFfmpegExecutor { args, input, previous, name, timeout, context ->
    val array = argumentsArray(); args.forEach { appendArgument(array, it) }
    val result = executeMemory(array, typedBytes(input), previous?.let(::typedBytes), name, timeout.toDouble(), { context.cancellation?.isCancelled() == true }).await()
    MemoryFfmpegResult(FfmpegProcessResult(result.code, result.output, result.outputLimited, result.cancelled, result.timedOut, result.ioFailed),
        result.bytes?.let { bytes -> Bytes(ByteArray(bytes.length) { browserByte(bytes, it) }) })
}))
