@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package livephoto.ui.browser

import kotlin.js.*
import kotlinx.coroutines.*
import org.khronos.webgl.ArrayBuffer
import org.w3c.files.Blob

internal external interface TaskReply : JsAny {
    val report: String
    val failed: Boolean
    val bytes: ArrayBuffer?
    val mime: String?
    val name: String?
    val second: ArrayBuffer?
    val secondMime: String?
    val secondName: String?
    val review: String?
    val time: String?
    val scale: String?
    val start: String?
    val end: String?
    val windowScale: String?
}
internal external interface TaskRunner : JsAny {
    fun run(options: JsAny, files: JsAny): Promise<TaskReply>
    fun cancel()
    fun dispose()
}
private fun loadRunner(): Promise<TaskRunner> =
    js("import(/* webpackIgnore: true */ new URL('./ui-task-runtime.mjs', document.baseURI).href).then(module => module.createTaskRunner())")
internal fun taskOptions(kind: String, reference: String, protocol: String = "", profile: String = "", time: String = "", scale: String = "",
    end: String = "", endScale: String = "", review: String = "", imageName: String = "", videoName: String = "", publication: String = ""): JsAny =
    js("({kind,reference,protocol,profile,time,scale,end,endScale,review,imageName,videoName,publication})")
private fun taskFiles(first: Blob, second: Blob?): JsAny = js("second ? [first,second] : [first]")
internal fun taskBlob(bytes: ArrayBuffer, mime: String): Blob = js("new Blob([bytes],{type:mime})")

internal class BrowserTasks {
    private var runner: TaskRunner? = null
    private var cancelled = false
    suspend fun run(options: JsAny, first: Blob, second: Blob? = null): TaskReply {
        cancelled = false
        try {
            val active = runner ?: loadRunner().await().also { runner = it }
            currentCoroutineContext().ensureActive()
            if (cancelled) return cancelledReply()
            val result = active.run(options, taskFiles(first, second)).await()
            currentCoroutineContext().ensureActive()
            return if (cancelled) cancelledReply() else result
        } catch (cancelled: CancellationException) { runner?.cancel(); throw cancelled }
    }
    fun cancel() { cancelled = true; runner?.cancel() }
    private fun cancelledReply(): TaskReply = js("({report:'已取消处理\\n错误码：CANCELLED\\n原片未修改，未发起新的下载；修复需要重新预览。',failed:true})")
}
