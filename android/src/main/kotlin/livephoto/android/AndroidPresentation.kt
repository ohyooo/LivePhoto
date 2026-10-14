package livephoto.android

import livephoto.core.CoreError
import livephoto.core.Value
import java.util.Locale

internal fun formatMediaSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "未知（文件提供方未返回大小）"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    return String.format(Locale.ROOT, "%.2f %s（%d 字节）", size, units[unit], bytes)
}

/** Preserve the actual structured failure; error codes alone cannot explain a rejected MP4. */
internal fun formatCoreFailure(error: CoreError, imageName: String? = null, videoName: String? = null): String = buildString {
    append("生成失败")
    appendFailureContext((error.details["livephoto.step"] as? Value.Text)?.value,
        (error.details["livephoto.inputRole"] as? Value.Text)?.value, imageName, videoName)
    append("\n错误码：").append(error.code.value.take(128))
    append("\n阶段：").append(error.stage)
    append("\n具体原因：").append(error.message.take(4000))
    if (error.code.value == "UNSUPPORTED_CONTAINER") {
        append("\n说明：当前解析器不支持这份文件中的某种容器、轨道或编码配置；JPG/MP4 扩展名不代表内部结构一定受支持。不会自动转码。")
    }
    error.location?.let { location ->
        location.source?.let { append("\n来源：").append(it.value.take(256)) }
        location.track?.let { append("\n轨道：").append(it.value.take(128)) }
        location.range?.let { append("\n字节范围：").append(it.offset).append(" + ").append(it.length) }
        location.selector?.let { append("\n位置：").append(it.take(256)) }
    }
    error.details.entries.filter { it.key !in contextKeys }.take(8).forEach { (key, value) ->
        append('\n').append(key.take(128)).append("：").append(displayValue(value))
    }
}

internal fun formatAndroidFailure(error: Exception, imageName: String? = null, videoName: String? = null): String = buildString {
    val context = error as? AndroidOperationFailure
    val cause = context?.cause ?: error
    append("生成失败")
    appendFailureContext(context?.step, context?.inputRole, imageName, videoName)
    append("\n原因：").append(cause.javaClass.simpleName.take(128))
    cause.message?.take(2000)?.let { append("：").append(it) }
    when (cause) {
        is SecurityException -> append("\n请重新选择文件并确认授权；不会覆盖原片。")
        is IllegalArgumentException -> append("\n请检查输入参数和大小；输入合计上限 32 MiB。")
        else -> append("\n请检查文件、存储空间和相册后重试；不会覆盖原片。")
    }
}

private val contextKeys = setOf("livephoto.operation", "livephoto.step", "livephoto.inputRole")

private fun StringBuilder.appendFailureContext(step: String?, role: String?, imageName: String?, videoName: String?) {
    append("\n关联对象：").append(when (role) {
        "image" -> "图片输入" + (imageName?.take(256)?.let { "（$it）" } ?: "")
        "video" -> "视频输入" + (videoName?.take(256)?.let { "（$it）" } ?: "")
        "output" -> "合并结果 / 输出"
        "input" -> "输入素材（尚未定位到图片或视频）"
        "request" -> "合并请求 / 目标配置"
        else -> "未知（错误未提供定位，不能判断是图片、视频还是合并流程）"
    })
    append("\n处理步骤：").append(when (step) {
        "read-image" -> "读取所选图片"
        "read-video" -> "读取所选视频"
        "inspect-image" -> "解析图片容器与元数据"
        "inspect-video" -> "解析视频容器、轨道与采样表"
        "check-video-profile" -> "检查视频是否满足目标协议要求"
        "prepare-image-metadata" -> "合并准备：处理图片元数据与厂商标记"
        "verify-input" -> "复核输入完整性与保真信息"
        "validate-request" -> "检查合并请求参数"
        "plan-target" -> "规划目标协议、格式与关键帧位置"
        "invoke-core" -> "调用 Core 合并流程（未返回结构化错误）"
        "write-output" -> "合并写入临时输出"
        "prepare-output" -> "准备输出事务与待校验数据"
        "verify-output" -> "回读校验合并结果"
        "publish-output" -> "提交已验证的输出事务"
        "save-output" -> "保存生成结果到系统相册"
        else -> step?.take(128) ?: "未知"
    })
}

private fun displayValue(value: Value, depth: Int = 0): String = (if (depth >= 3) "…" else when (value) {
    Value.Null -> "null"
    is Value.Text -> value.value.take(512)
    is Value.BooleanValue -> value.value.toString()
    is Value.Number -> value.decimal.take(128)
    is Value.ArrayValue -> value.values.take(8).joinToString(prefix = "[", postfix = "]") { displayValue(it, depth + 1) }
    is Value.ObjectValue -> value.entries.entries.take(8).joinToString(prefix = "{", postfix = "}") { (key, item) ->
        "${key.take(128)}: ${displayValue(item, depth + 1)}"
    }
}).take(1024)
