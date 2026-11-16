package livephoto.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import java.awt.EventQueue
import java.awt.Rectangle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLifecycleTest {
    @Test fun fullscreenRestoresWindowWithoutRecreatingComposition() {
        configureDesktopRendering()
        val initialized = AtomicInteger()
        val shown = CountDownLatch(1)
        var window: JFrame? = null
        var fullscreen: DesktopFullscreen? = null
        var active = false
        var original: Rectangle? = null
        try {
            EventQueue.invokeAndWait {
                val frame = desktopWindow("LivePhoto fullscreen regression", 640, 420) {
                    val draft = remember { initialized.incrementAndGet() }
                    Text("Retained draft $draft")
                    SideEffect { shown.countDown() }
                }
                window = frame
                fullscreen = DesktopFullscreen({ frame }, { fullscreen!!.set(false) }) { active = it }
                frame.isVisible = true
            }
            assertTrue(shown.await(15, TimeUnit.SECONDS), "Window composition was not rendered")
            EventQueue.invokeAndWait {
                val frame = window!!
                original = Rectangle(frame.bounds)
                fullscreen!!.set(true)
                assertTrue(active)
                assertTrue(frame.isUndecorated)
            }
            EventQueue.invokeAndWait {
                fullscreen!!.set(false)
                assertFalse(active)
                assertFalse(window!!.isUndecorated)
                assertTrue(window!!.isVisible)
                assertEquals(original, window!!.bounds)
            }
            EventQueue.invokeAndWait { assertEquals(1, initialized.get(), "Fullscreen discarded the editing composition") }
        } finally {
            EventQueue.invokeAndWait { fullscreen?.set(false); window?.dispose() }
        }
    }
}
