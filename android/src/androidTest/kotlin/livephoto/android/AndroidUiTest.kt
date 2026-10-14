package livephoto.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Real Activity/Compose layout and picker-result state; no gallery compatibility claim. */
@RunWith(AndroidJUnit4::class)
class AndroidUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun selectionButtonsAreBelowAppBarAndMissingInputsDisableCreate() {
        val bar = compose.onNodeWithTag("app-bar").fetchSemanticsNode().boundsInRoot
        val image = compose.onNodeWithTag("select-image").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val video = compose.onNodeWithTag("select-video").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(image.top >= bar.bottom, "App bar must not cover image selection")
        assertTrue(video.top >= image.bottom, "Selection buttons must not overlap")
        compose.onNodeWithTag("create").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun videoOnlyClearlyRequiresImageAndCannotGenerate() {
        withPickerResults(Uri.parse("content://livephoto-ui-test/video")) {
            compose.onNodeWithTag("select-video").performClick()
            compose.onNodeWithTag("video-selection").assertTextEquals("视频：已选择")
            compose.onNodeWithTag("image-selection").assertTextEquals("图片：未选择")
            compose.onNodeWithTag("create").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("input-readiness").assertTextEquals("已选择视频，还需选择图片")
        }
    }

    @Test fun pickerCancellationAndActivityRecreationKeepBothSelections() {
        withPickerResults(Uri.parse("content://livephoto-ui-test/image"), Uri.parse("content://livephoto-ui-test/video"), null) {
            compose.onNodeWithTag("select-image").performClick()
            compose.onNodeWithTag("select-video").performClick()
            compose.onNodeWithTag("select-image").performClick() // Cancel; do not erase image.
            compose.onNodeWithTag("image-selection").assertTextEquals("图片：已选择")
            compose.onNodeWithTag("video-selection").assertTextEquals("视频：已选择")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("image-selection").assertTextEquals("图片：已选择")
            compose.onNodeWithTag("video-selection").assertTextEquals("视频：已选择")
            compose.onNodeWithTag("create").performScrollTo().assertIsEnabled()
        }
    }

    private fun withPickerResults(vararg results: Uri?, block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var position = 0
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                check(position < results.size) { "Unexpected document picker launch" }
                val uri = results[position++]
                return if (uri == null) Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent())
                else Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        try { block(); assertTrue(position == results.size, "All picker results must be consumed") }
        finally { instrumentation.removeMonitor(monitor) }
    }
}
