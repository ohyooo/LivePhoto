package livephoto.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

public enum class ThemeMode { System, Light, Dark }
internal val ControlShape = RoundedCornerShape(8.dp)

public fun livePhotoColors(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xff5b9bff), onPrimary = Color(0xff11151b), background = Color(0xff11151b),
    surface = Color(0xff1a2029), onSurface = Color(0xffe7ecf3), onBackground = Color(0xffe7ecf3),
    onSurfaceVariant = Color(0xffa2adbc), outlineVariant = Color(0xff2e3744), surfaceVariant = Color(0xff242d3a),
) else lightColorScheme(
    primary = Color(0xff2563eb), onPrimary = Color.White, background = Color(0xfff5f7fa),
    surface = Color.White, onSurface = Color(0xff17212f), onBackground = Color(0xff17212f),
    onSurfaceVariant = Color(0xff637083), outlineVariant = Color(0xffe3e8ef), surfaceVariant = Color(0xffedf1f7),
)

@Composable
internal fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun Disclosure(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = title }, shape = ControlShape,
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                Text(summary, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(if (expanded) "−" else "+")
        }
        if (expanded) content()
    }
}

@Composable
internal fun ReportPanel(text: String, failed: Boolean, tag: String, title: String, platform: LivePhotoUiPlatform) {
    var expanded by remember(text) { mutableStateOf(false) }
    var copied by remember(text) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxWidth(), shape = ControlShape,
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer { Text(if (expanded) text else text.lineSequence().take(if (failed) 6 else 1).joinToString("\n"), Modifier.testTag(tag)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.semantics {
                    contentDescription = (if (expanded) "收起" else "展开") + title + "详情"
                }) { Text(if (expanded) "收起详情" else "处理详情") }
                if (platform.supportsClipboard) TextButton(onClick = {
                    scope.launch {
                        copied = try { if (platform.copyText(text)) "已复制" else "复制失败，请手动选择文本" }
                        catch (_: Exception) { "复制失败，请手动选择文本" }
                    }
                }) { Text("复制报告") }
            }
            copied?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Retain state and the native media element when moving a module between columns. */
@Composable
internal fun retainedContent(content: @Composable () -> Unit): @Composable () -> Unit {
    val current by rememberUpdatedState(content)
    return remember { movableContentOf { current() } }
}
