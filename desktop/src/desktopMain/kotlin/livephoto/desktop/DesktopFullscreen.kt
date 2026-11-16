package livephoto.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import java.awt.Frame
import java.awt.GraphicsDevice
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.Rectangle
import java.awt.event.KeyEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Recreate only the window peer; keep the composition, drafts and media player alive. */
@OptIn(ExperimentalComposeUiApi::class)
internal class DesktopFullscreen(private val window: () -> JFrame?, private val exit: () -> Unit,
    private val changed: (Boolean) -> Unit) {
    private data class SavedWindow(
        val frame: JFrame, val panel: ComposePanel, val device: GraphicsDevice,
        val bounds: Rectangle, val extendedState: Int, val undecorated: Boolean,
        val menuVisible: Boolean?, val disposeOnRemove: Boolean,
    )
    private var saved: SavedWindow? = null
    private val escape = KeyEventDispatcher { event ->
        val frame = saved?.frame
        if (frame != null && event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ESCAPE &&
            (event.component == frame || SwingUtilities.getWindowAncestor(event.component) == frame)) {
            exit()
            true
        } else false
    }

    fun set(enabled: Boolean) {
        check(SwingUtilities.isEventDispatchThread())
        if (enabled == (saved != null)) return
        if (!enabled) {
            restore(show = saved?.frame?.isDisplayable == true)
            return
        }
        val frame = window() ?: return
        val panel = frame.contentPane as? ComposePanel ?: return
        if (!frame.isDisplayable) return
        val device = frame.graphicsConfiguration.device
        check(device.fullScreenWindow == null) { "当前屏幕已有全屏窗口" }
        val before = SavedWindow(frame, panel, device, Rectangle(frame.bounds), frame.extendedState,
            frame.isUndecorated, frame.jMenuBar?.isVisible, panel.isDisposeOnRemove)
        saved = before
        try {
            panel.isDisposeOnRemove = false
            frame.dispose()
            frame.extendedState = Frame.NORMAL
            frame.isUndecorated = true
            frame.jMenuBar?.isVisible = false
            device.fullScreenWindow = frame
            panel.isDisposeOnRemove = before.disposeOnRemove
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escape)
            changed(true)
        } catch (failure: Exception) {
            try { restore(show = true) } catch (restoration: Exception) { failure.addSuppressed(restoration) }
            throw failure
        }
    }

    private fun restore(show: Boolean) {
        val before = saved ?: return
        saved = null
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escape)
        try {
            // Never evict a different window which subsequently took over this monitor.
            if (before.device.fullScreenWindow == before.frame) before.device.fullScreenWindow = null
        } finally {
            try {
                before.panel.isDisposeOnRemove = false
                before.frame.dispose()
                before.frame.isUndecorated = before.undecorated
                before.frame.bounds = before.bounds
                before.frame.extendedState = before.extendedState
                before.menuVisible?.let { before.frame.jMenuBar?.isVisible = it }
                if (show) before.frame.isVisible = true
            } finally {
                before.panel.isDisposeOnRemove = before.disposeOnRemove
                changed(false)
            }
        }
    }
}
