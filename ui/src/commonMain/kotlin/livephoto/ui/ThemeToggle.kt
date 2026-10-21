package livephoto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** The icon shows the mode offered by the button, not a platform emoji. */
@Composable
public fun ThemeToggle(dark: Boolean, onClick: () -> Unit, mode: ThemeMode = ThemeMode.System,
    onSelect: ((ThemeMode) -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun closeMenu() { menu = false; focus.requestFocus() }
    val ink = MaterialTheme.colorScheme.onSurface
    Box {
    IconButton(onClick = { if (onSelect == null) onClick() else menu = true }, modifier = Modifier.focusRequester(focus).semantics {
        contentDescription = "主题模式"
    }) {
        Canvas(Modifier.size(22.dp)) {
            val k = size.minDimension / 24f
            if (dark) {
                drawCircle(ink, 4f * k, Offset(12f * k, 12f * k), style = Stroke(1.7f * k))
                for (ray in 0..7) {
                    val angle = ray * kotlin.math.PI / 4
                    drawLine(ink,
                        Offset((12 + cos(angle) * 7).toFloat() * k, (12 + sin(angle) * 7).toFloat() * k),
                        Offset((12 + cos(angle) * 10).toFloat() * k, (12 + sin(angle) * 10).toFloat() * k),
                        1.7f * k, StrokeCap.Round)
                }
            } else {
                drawPath(Path().apply {
                    moveTo(19.8f * k, 15.2f * k)
                    cubicTo(15f * k, 17f * k, 9f * k, 13f * k, 9f * k, 8f * k)
                    cubicTo(9f * k, 6.5f * k, 9.4f * k, 5f * k, 10.2f * k, 3.8f * k)
                    cubicTo(2f * k, 5f * k, 1f * k, 16f * k, 8f * k, 20f * k)
                    cubicTo(13f * k, 23f * k, 19f * k, 20f * k, 19.8f * k, 15.2f * k)
                    close()
                }, ink, style = Stroke(1.7f * k))
            }
        }
    }
    DropdownMenu(expanded = menu, onDismissRequest = ::closeMenu) {
        ThemeMode.entries.forEach { choice ->
            val label = when (choice) { ThemeMode.System -> "跟随系统"; ThemeMode.Light -> "浅色"; ThemeMode.Dark -> "深色" }
            DropdownMenuItem(text = { Text((if (choice == mode) "✓ " else "") + label) }, onClick = { onSelect?.invoke(choice); closeMenu() },
                modifier = Modifier.semantics { contentDescription = "主题：$label"; role = Role.Button })
        }
    }
    }
}
