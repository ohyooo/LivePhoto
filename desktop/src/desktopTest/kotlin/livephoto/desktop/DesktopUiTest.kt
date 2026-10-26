package livephoto.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import livephoto.ui.LivePhotoScreen
import org.junit.Rule
import org.junit.Test

class DesktopUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sharedScreenSupportsPointerNavigationWithoutPlatformMocks() {
        System.setProperty("compose.interop.blending", "true")
        val platform = DesktopPlatform { _, _ -> null }
        try {
            rule.setContent { MaterialTheme { LivePhotoScreen(platform) } }
            rule.onNodeWithTag("select-image").assertExists()
            rule.onNodeWithTag("create").assertIsNotEnabled()
            rule.onNodeWithTag("page-1").performMouseInput { click() }
            rule.onNodeWithTag("page-0").performMouseInput { click() }
            rule.onNodeWithTag("select-video").assertExists()
            rule.onNodeWithTag("select-image").performMouseInput { click() }
            rule.onNodeWithTag("create").assertIsNotEnabled()
        } finally { platform.close() }
    }
}
