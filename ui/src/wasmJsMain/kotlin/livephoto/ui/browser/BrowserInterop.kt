package livephoto.ui.browser

import kotlin.js.JsAny
import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLElement
import org.w3c.files.Blob
import org.w3c.files.File

// Browser capabilities only. All controls and generation remain in shared Kotlin.
internal external interface BrowserMedia : JsAny {
    val player: BrowserPlayerModule
    val editor: BrowserEditorModule
}
internal external interface BrowserPlayerModule : JsAny {
    fun createPlayer(file: Blob, changed: (BrowserPlayback) -> Unit, scroll: (Float) -> Unit): BrowserPlayer
    fun imagePreview(file: Blob, scroll: (Float) -> Unit): BrowserPreview
    fun resultPreview(photo: Blob, movie: Blob, scroll: (Float) -> Unit): BrowserPreview
}
internal external interface BrowserEditorModule : JsAny {
    fun startVideoEdit(file: Blob, options: BrowserEditOptions): BrowserEditJob
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
internal external interface BrowserEditOptions : JsAny
internal external interface BrowserEditJob : JsAny {
    val promise: Promise<BrowserEditResult>
    fun cancel()
}
internal external interface BrowserEditResult : JsAny {
    val blob: Blob
    val timelineKnown: Boolean
    val origin: Double
    val actualStart: Double?
    val actualEnd: Double?
}

internal fun browserMediaReady(): Promise<BrowserMedia> =
    js("window.livePhotoMediaReady.then(modules => ({player: modules[0], editor: modules[1]}))")
internal fun browserFont(): Promise<ArrayBuffer> =
    js("fetch('fonts/NotoSansSC-Regular.otf').then(response => { if (!response.ok) throw new Error('Bundled font unavailable'); return response.arrayBuffer(); })")
internal fun browserFileBytes(file: Blob): Promise<ArrayBuffer> = js("file.arrayBuffer()")
internal fun browserFileSize(file: Blob): Double = js("file.size")
internal fun browserByte(values: Int8Array, index: Int): Byte = js("values[index]")
internal fun browserSetByte(values: Int8Array, index: Int, value: Byte): Unit = js("{ values[index] = value; }")
internal fun browserBlob(values: Int8Array, mime: String): Blob = js("new Blob([values], {type: mime})")
internal fun browserFile(blob: Blob, name: String, mime: String): File = js("new File([blob], name, {type: mime})")
internal fun browserObjectUrl(blob: Blob): String = js("URL.createObjectURL(blob)")
internal fun browserRevokeUrl(url: String): Unit = js("{ URL.revokeObjectURL(url); }")
internal fun browserFullscreenEnabled(): Boolean = js("!!document.fullscreenEnabled")
internal fun browserEditOptions(start: Double, end: Double, transcodeVideo: Boolean): BrowserEditOptions =
    js("({start, end, transcodeVideo})")
internal fun browserPreviewElement(preview: BrowserPreview): HTMLElement =
    js("{ preview.element.releasePreview = () => preview.dispose(); return preview.element; }")
internal fun browserReleasePreview(element: HTMLElement): Unit = js("{ element.releasePreview(); }")
internal fun browserRuntimeReady(): Unit = js("{ document.documentElement.dataset.livephotoRuntime = 'kotlin-wasm-compose'; }")
