package livephoto.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue
import livephoto.core.CoreError
import livephoto.core.CoreResult
import livephoto.core.IssueCode
import livephoto.core.Stage

/** Real Activity/Compose layout and picker-result state; no gallery compatibility claim. */
@RunWith(AndroidJUnit4::class)
class AndroidUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun selectionButtonsAreBelowAppBarAndMissingInputsDisableCreate() {
        val bar = compose.onNodeWithTag("app-bar").fetchSemanticsNode().boundsInRoot
        val image = compose.onNodeWithTag("select-image").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(image.top >= bar.bottom, "App bar must not cover image selection")
        val video = compose.onNodeWithTag("select-video").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(video.top >= bar.bottom, "App bar must not cover video selection")
        compose.onNodeWithTag("create").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun videoOnlyClearlyRequiresImageAndCannotGenerate() {
        withPickerResults(Uri.parse("content://livephoto-ui-test/video")) {
            compose.onNodeWithTag("select-video").performScrollTo().performClick()
            compose.onNodeWithTag("video-selection").assertTextEquals("视频：已选择")
            compose.onNodeWithTag("image-selection").assertTextEquals("图片：未选择")
            compose.onNodeWithTag("create").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("input-readiness").assertTextEquals("已选择视频，还需选择图片")
        }
    }

    @Test fun pickerCancellationAndActivityRecreationKeepBothSelections() {
        withPickerResults(Uri.parse("content://livephoto-ui-test/image"), Uri.parse("content://livephoto-ui-test/video"), null) {
            compose.onNodeWithTag("select-image").performScrollTo().performClick()
            compose.onNodeWithTag("select-video").performScrollTo().performClick()
            compose.onNodeWithTag("select-image").performScrollTo().performClick() // Cancel; do not erase image.
            compose.onNodeWithTag("image-selection").assertTextEquals("图片：已选择")
            compose.onNodeWithTag("video-selection").assertTextEquals("视频：已选择")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("image-selection").assertTextEquals("图片：已选择")
            compose.onNodeWithTag("video-selection").assertTextEquals("视频：已选择")
            compose.onNodeWithTag("create").performScrollTo().assertIsEnabled()
        }
    }

    @Test fun selectedFileSizesAndActualParseFailureAreVisibleAtTheTop() {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    LivePhotoScreen(describe = { uri ->
                        if (uri.lastPathSegment == "image") SelectedMediaInfo("cover.jpg", 2048)
                        else SelectedMediaInfo("clip.mp4", 3145728)
                    }) { _, _, _ ->
                        CoreResult.Failure(CoreError(IssueCode("UNSUPPORTED_CONTAINER"), Stage.Parse,
                            "Multiple sample descriptions are not implemented"))
                    }
                }
            }
        }
        withPickerResults(Uri.parse("content://livephoto-ui-test/image"), Uri.parse("content://livephoto-ui-test/video")) {
            compose.onNodeWithTag("select-image").performScrollTo().performClick()
            compose.onNodeWithTag("image-name").assertTextEquals("名称：cover.jpg")
            compose.onNodeWithTag("image-size").assertTextEquals("大小：2.00 KiB（2048 字节）")
            compose.onNodeWithTag("select-video").performScrollTo().performClick()
            compose.onNodeWithTag("video-name").assertTextEquals("名称：clip.mp4")
            compose.onNodeWithTag("video-size").assertTextEquals("大小：3.00 MiB（3145728 字节）")
            compose.onNodeWithTag("create").performScrollTo().performClick()
            compose.onNodeWithTag("status").assertIsDisplayed()
                .assertTextContains("UNSUPPORTED_CONTAINER", substring = true)
                .assertTextContains("Multiple sample descriptions are not implemented", substring = true)
            val errorY = compose.onNodeWithTag("status").fetchSemanticsNode().positionInRoot.y
            val imageY = compose.onNodeWithTag("select-image").fetchSemanticsNode().positionInRoot.y
            assertTrue(errorY < imageY, "Error details must appear above the input buttons")
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
