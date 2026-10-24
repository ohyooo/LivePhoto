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
private external interface TaskModule : JsAny { fun createTaskRunner(): TaskRunner }
private fun loadRunner(): Promise<TaskModule> =
    js("import(/* webpackIgnore: true */ new URL('./ui-task-runtime.mjs', document.baseURI).href)")
internal fun taskOptions(kind: String, reference: String, protocol: String = "", profile: String = "", time: String = "", scale: String = "",
    end: String = "", endScale: String = "", review: String = "", imageName: String = "", videoName: String = "", publication: String = ""): JsAny =
    js("({kind,reference,protocol,profile,time,scale,end,endScale,review,imageName,videoName,publication})")
private fun taskFiles(first: Blob, second: Blob?): JsAny = js("second ? [first,second] : [first]")
internal fun taskBlob(bytes: ArrayBuffer, mime: String): Blob = js("new Blob([bytes],{type:mime})")
private fun cancelledReply(): TaskReply = js("({report:'',failed:true,code:'CANCELLED'})")
private fun timeoutReply(): TaskReply = js("({report:'',failed:true,code:'TIMEOUT'})")

internal class BrowserTasks {
    private var runner: TaskRunner? = null
    private var cancelled = false
    private var operation: Deferred<TaskReply>? = null
    suspend fun run(options: JsAny, first: Blob, second: Blob? = null): TaskReply = coroutineScope {
        check(operation == null) { "已有后台任务" }
        cancelled = false
        val work = async {
            withTimeout(120_000) {
                val active = runner ?: loadRunner().await().createTaskRunner().also { runner = it }
                currentCoroutineContext().ensureActive()
                active.run(options, taskFiles(first, second)).await()
            }
        }
        operation = work
        try {
            val result = work.await()
            currentCoroutineContext().ensureActive()
            if (cancelled) cancelledReply() else result
        } catch (error: CancellationException) {
            runner?.cancel()
            currentCoroutineContext().ensureActive()
            when { cancelled -> cancelledReply(); error is TimeoutCancellationException -> timeoutReply(); else -> throw error }
        } finally { operation = null }
    }
    fun cancel() { cancelled = true; runner?.cancel(); operation?.cancel() }
}

internal fun taskFailure(kind: String, response: TaskReply): String {
    val code = response.code ?: return response.report
    val label = when (kind) {
        "Create" -> "生成"; "Analyze" -> "检查"; "Extract" -> "提取"; "Split" -> "清理拆分"
        "Convert" -> "转换"; "Repair" -> "修复"; "Key" -> "关键照片修改"; else -> "媒体处理"
    }
    val reason = when (code) {
        "CANCELLED" -> "已取消处理；修复需要重新预览。"
        "RESOURCE_LIMIT_EXCEEDED" -> if (kind in setOf("Probe", "Remux", "Trim", "ExtractFrame", "Transcode"))
            "媒体预处理输入超过 256 MiB，未读取文件。" else if (kind == "Create")
            "图片与当前视频合计须小于 32 MiB；请先裁剪视频。未读取文件。" else "输入超过 32 MiB，未读取文件。"
        "TIMEOUT" -> "处理超过 120 秒，后台任务已终止。"
        else -> "无法完成后台任务，请重试。"
    }
    val stage = if (code == "RESOURCE_LIMIT_EXCEEDED") "Read" else "后台任务"
    return "${label}失败\n错误码：$code\n阶段：$stage\n$reason\n原片未修改，未发起新的下载。"
}
