@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package livephoto.ui.browser

import kotlin.js.*
import kotlinx.coroutines.*
import org.khronos.webgl.ArrayBuffer
import org.w3c.files.Blob

internal external interface TaskReply : JsAny {
    val report: String
    val failed: Boolean
    val code: String?
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
private fun cancelledReply(): TaskReply = js("({report:'',failed:true,code:'CANCELLED'})")

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
}

internal fun taskFailure(kind: String, response: TaskReply): String {
    val code = response.code ?: return response.report
    val label = when (kind) {
        "Create" -> "生成"; "Analyze" -> "检查"; "Extract" -> "提取"; "Split" -> "清理拆分"
        "Convert" -> "转换"; "Repair" -> "修复"; "Key" -> "关键照片修改"; else -> "媒体处理"
    }
    val reason = when (code) {
        "CANCELLED" -> "已取消处理；修复需要重新预览。"
        "RESOURCE_LIMIT_EXCEEDED" -> "输入超过 32 MiB，未读取文件。"
        "TIMEOUT" -> "处理超过 120 秒，后台任务已终止。"
        else -> "无法完成后台任务，请重试。"
    }
    return "${label}失败\n错误码：$code\n阶段：Read\n$reason\n原片未修改，未发起新的下载。"
}
