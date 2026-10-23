@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package livephoto.ui.browser

import kotlin.js.JsAny
import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.Blob
import org.w3c.files.File

// Browser capabilities only. All controls and generation remain in shared Kotlin.
internal external interface BrowserMedia : JsAny {
    val player: BrowserPlayerModule
}
internal external interface BrowserPlayerModule : JsAny {
    fun createPlayer(file: Blob, changed: (BrowserPlayback) -> Unit, scroll: (Float) -> Unit): BrowserPlayer
    fun imagePreview(file: Blob, scroll: (Float) -> Unit): BrowserPreview
    fun resultPreview(photo: Blob, movie: Blob, scroll: (Float) -> Unit): BrowserPreview
}
internal external interface BrowserPreview : JsAny {
    val element: HTMLElement
    fun dispose()
}
internal external interface BrowserPlayback : JsAny {
    val duration: Double
    val current: Double
    val seeking: Boolean
    val ready: Boolean
    val error: String?
    val width: Double
    val height: Double
    val paused: Boolean
    val volume: Double
    val muted: Boolean
    val rate: Double
    val fullscreen: Boolean
}
internal external interface BrowserPlayer : BrowserPreview {
    fun seek(seconds: Double)
    fun togglePlayback()
    fun setVolume(volume: Double)
    fun setMuted(muted: Boolean)
    fun setRate(rate: Double)
    fun setFullscreen(fullscreen: Boolean)
    fun capture(): Promise<BrowserCapture>
}
internal external interface BrowserCapture : JsAny {
    val blob: Blob
    val time: Double
}
internal fun browserMediaReady(): Promise<BrowserMedia> =
    js("window.livePhotoMediaReady.then(player => ({player}))")
internal fun browserFont(): Promise<ArrayBuffer> =
    js("fetch('fonts/NotoSansSC-Regular.otf').then(response => { if (!response.ok) throw new Error('Bundled font unavailable'); return response.arrayBuffer(); })")
internal fun browserFileSize(file: Blob): Double = js("file.size")
internal fun browserByte(values: Int8Array, index: Int): Byte = js("values[index]")
internal fun browserFile(blob: Blob, name: String, mime: String): File = js("new File([blob], name, {type: mime})")
internal fun browserObjectUrl(blob: Blob): String = js("URL.createObjectURL(blob)")
internal fun browserRevokeUrl(url: String): Unit = js("{ URL.revokeObjectURL(url); }")
internal fun browserFullscreenEnabled(): Boolean = js("!!document.fullscreenEnabled")
internal fun browserPreviewElement(preview: BrowserPreview): HTMLElement =
    js("{ preview.element.releasePreview = () => preview.dispose(); return preview.element; }")
internal fun browserReleasePreview(element: HTMLElement): Unit = js("{ element.releasePreview(); }")
internal fun browserRuntimeReady(): Unit = js("{ document.documentElement.dataset.livephotoRuntime = 'kotlin-wasm-compose'; }")
internal fun browserClipboardAvailable(): Boolean = js("!!navigator.clipboard?.writeText")
internal fun browserOpenPicker(input: HTMLInputElement): Unit = js("{ if (typeof input.showPicker === 'function') input.showPicker(); else input.click(); }")
internal fun browserCopyText(text: String): Promise<JsAny?> = js("navigator.clipboard.writeText(text)")
