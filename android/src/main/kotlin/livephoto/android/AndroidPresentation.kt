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
internal fun formatCoreFailure(error: CoreError): String = buildString {
    append("生成失败\n错误码：").append(error.code.value.take(128))
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
    error.details.entries.take(8).forEach { (key, value) ->
        append('\n').append(key.take(128)).append("：").append(displayValue(value))
    }
}

internal fun formatAndroidFailure(error: Exception): String = buildString {
    append("Android 读取或保存失败\n原因：").append(error.javaClass.simpleName.take(128))
    error.message?.take(2000)?.let { append("：").append(it) }
    when (error) {
        is SecurityException -> append("\n请重新选择文件并确认授权；不会覆盖原片。")
        is IllegalArgumentException -> append("\n请检查输入参数和大小；输入合计上限 32 MiB。")
        else -> append("\n请检查文件、存储空间和相册后重试；不会覆盖原片。")
    }
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
