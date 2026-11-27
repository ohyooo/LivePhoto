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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import livephoto.ui.CreatedPhoto
import livephoto.ui.LivePhotoUiPlatform
import livephoto.ui.MediaKind

/** Real Activity/Compose layout and picker-result state; no gallery compatibility claim. */
@RunWith(AndroidJUnit4::class)
class AndroidUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun keyEditingHasIndependentInputValidationAndClearsStaleReports() {
        var calls = 0
        val platform = object : LivePhotoUiPlatform {
            override val outputDescription = "unused"
            override val supportsAnalysis = true
            override val supportsPhotoKey = true
            override val keySuccessMessage = "TEST_KEY_SAVED"
            @Composable override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit = { onResult("same-key-uri") }
            override suspend fun describe(reference: String) = livephoto.ui.SelectedMediaInfo("live.jpg", 100)
            override suspend fun setPhotoKey(reference: String, requested: livephoto.core.Time): CoreResult<livephoto.ui.PhotoKeyReport> {
                check(reference == "same-key-uri"); calls++
                return CoreResult.Success(livephoto.ui.PhotoKeyReport(requested, requested, livephoto.core.Digest("0".repeat(64)), 100u, livephoto.core.PreservationReport(), emptyList()))
            }
            override suspend fun create(image: String, video: String, target: livephoto.core.ProtocolSelector): CoreResult<CreatedPhoto> = error("Must not create")
            override fun formatFailure(error: Exception, imageName: String?, videoName: String?) = error.message.orEmpty()
        }
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { livephoto.ui.LivePhotoScreen(platform) } } }
        openPhotoTool(5)
        compose.onNodeWithTag("set-photo-key").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
        compose.onNodeWithTag("key-seconds").performScrollTo().performTextReplacement("-1")
        compose.onNodeWithTag("set-photo-key").assertIsNotEnabled(); assertTrue(calls == 0)
        compose.onNodeWithTag("key-seconds").performTextReplacement("0.5")
        compose.onNodeWithTag("set-photo-key").performScrollTo().performClick()
        compose.onNodeWithTag("key-report").assertTextContains("TEST_KEY_SAVED", substring = true); assertTrue(calls == 1)
        compose.onNodeWithTag("key-seconds").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("key-report").assertDoesNotExist()
        compose.onNodeWithTag("set-photo-key").performScrollTo().performClick()
        compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
        compose.onNodeWithTag("key-report").assertDoesNotExist()
        compose.onNodeWithTag("key-seconds").assertTextContains("0")
        returnToEmptyCreate()
    }

    @Test fun repairRequiresPreviewAndClearsReviewAfterApplyOrSameUriReselection() {
        var applied = 0
        var blockNext = false
        val digest = livephoto.core.Digest("0".repeat(64))
        val changes = listOf(livephoto.core.Change("test:Length", reason = "test", requested = true))
        val review = livephoto.ui.RepairReview(digest, changes)
        val base = livephoto.ui.PhotoRepairReport(digest, review, emptyList(), changes, emptyList(), emptyList(), emptyList())
        val platform = object : LivePhotoUiPlatform {
            override val outputDescription = "unused"
            override val supportsAnalysis = true
            override val supportsRepair = true
            override val repairSuccessMessage = "TEST_REPAIRED_SAVED"
            @Composable override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit = { onResult("same-repair-uri") }
            override suspend fun describe(reference: String) = livephoto.ui.SelectedMediaInfo("damaged.jpg", 100)
            override suspend fun repairPhoto(reference: String, review: livephoto.ui.RepairReview?): CoreResult<livephoto.ui.PhotoRepairReport> {
                check(reference == "same-repair-uri")
                return CoreResult.Success(when {
                    review != null -> { check(review == base.review); applied++; base.copy(review = null, changesApplied = changes, outputDigest = digest, byteLength = 100u) }
                    blockNext -> base.copy(review = null, proposedChanges = emptyList(), blocked = listOf(livephoto.core.Issue(IssueCode("REPAIR_AMBIGUOUS"), livephoto.core.Severity.Error, livephoto.core.Layer.Protocol)))
                    else -> base
                })
            }
            override suspend fun create(image: String, video: String, target: livephoto.core.ProtocolSelector): CoreResult<CreatedPhoto> = error("Must not create")
            override fun formatFailure(error: Exception, imageName: String?, videoName: String?) = error.message.orEmpty()
        }
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { livephoto.ui.LivePhotoScreen(platform) } } }
        openPhotoTool(4)
        compose.onNodeWithTag("preview-repair").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("apply-repair").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
        compose.onNodeWithTag("apply-repair").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("preview-repair").performScrollTo().performClick()
        compose.onNodeWithTag("repair-report").assertTextContains("修复预览完成（未写入）", substring = true)
        assertTrue(applied == 0)
        compose.onNodeWithTag("apply-repair").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("repair-report").assertTextContains("TEST_REPAIRED_SAVED", substring = true)
        compose.onNodeWithTag("apply-repair").assertIsNotEnabled()
        assertTrue(applied == 1)
        compose.onNodeWithTag("preview-repair").performScrollTo().performClick()
        compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
        compose.onNodeWithTag("repair-report").assertDoesNotExist()
        compose.onNodeWithTag("apply-repair").performScrollTo().assertIsNotEnabled()
        blockNext = true
        compose.onNodeWithTag("preview-repair").performScrollTo().performClick()
        compose.onNodeWithTag("repair-report").assertTextContains("修复被阻止（未写入）", substring = true)
        compose.onNodeWithTag("apply-repair").performScrollTo().assertIsNotEnabled()
        returnToEmptyCreate()
    }

    @Test fun conversionHasIndependentTargetAndClearsReportOnChanges() {
        var selected: livephoto.core.ProtocolSelector? = null
        val platform = object : LivePhotoUiPlatform {
            override val outputDescription = "unused"
            override val supportsAnalysis = true
            override val supportsConversion = true
            override val conversionSuccessMessage = "TEST_CONVERTED_SAVED"
            @Composable override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit = { onResult("same-conversion-uri") }
            override suspend fun describe(reference: String) = livephoto.ui.SelectedMediaInfo("live.jpg", 100)
            override suspend fun convertPhoto(reference: String, target: livephoto.core.ProtocolSelector): CoreResult<livephoto.ui.ConvertedPhoto> {
                check(reference == "same-conversion-uri"); selected = target
                return CoreResult.Success(livephoto.ui.ConvertedPhoto(target, 100u, livephoto.core.Digest("0".repeat(64)), false,
                    livephoto.core.PreservationReport(), emptyList()))
            }
            override suspend fun create(image: String, video: String, target: livephoto.core.ProtocolSelector): CoreResult<CreatedPhoto> = error("Must not create")
            override fun formatFailure(error: Exception, imageName: String?, videoName: String?) = error.message.orEmpty()
        }
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { livephoto.ui.LivePhotoScreen(platform) } } }
        openPhotoTool(3)
        compose.onNodeWithTag("convert-photo").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
        compose.onNodeWithTag("conversion-target-GoogleV2").performScrollTo().performClick()
        compose.onNodeWithTag("convert-photo").performScrollTo().performClick()
        compose.onNodeWithTag("conversion-report").assertTextContains("TEST_CONVERTED_SAVED", substring = true)
        assertTrue(selected == livephoto.ui.ConversionTarget.GoogleV2.selector)
        compose.onNodeWithTag("conversion-target-GoogleV1").performScrollTo().performClick()
        compose.onNodeWithTag("conversion-report").assertDoesNotExist()
        compose.onNodeWithTag("convert-photo").performScrollTo().performClick()
        compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
        compose.onNodeWithTag("conversion-report").assertDoesNotExist()
        returnToEmptyCreate()
    }

    @Test fun cleanSplitUsesExplicitIndependentSavesAndClearsOnReselection() {
        var saves = 0
        val platform = object : LivePhotoUiPlatform {
            override val outputDescription = "unused"
            override val supportsAnalysis = true
            override val supportsCleanSplit = true
            @Composable override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit = { onResult("same-uri") }
            override suspend fun describe(reference: String) = livephoto.ui.SelectedMediaInfo("motion.jpg", 128)
            override suspend fun cleanSplit(reference: String): CoreResult<livephoto.ui.PreparedCleanSplit<livephoto.core.Bytes>> {
                val image = livephoto.ui.SplitFile(livephoto.core.Bytes(byteArrayOf(1)), "image/jpeg", "jpg", livephoto.core.Digest("0".repeat(64)), 1uL)
                val video = livephoto.ui.SplitFile(livephoto.core.Bytes(byteArrayOf(2)), "video/mp4", "mp4", livephoto.core.Digest("1".repeat(64)), 1uL)
                return CoreResult.Success(livephoto.ui.PreparedCleanSplit(image, video, livephoto.core.PreservationReport(), emptyList()))
            }
            override suspend fun saveSplitFile(file: livephoto.ui.SplitFile<livephoto.core.Bytes>): String {
                saves++
                if (file.mime == "video/mp4") error("TEST_VIDEO_SAVE_FAILURE")
                return "TEST_IMAGE_SAVED"
            }
            override suspend fun create(image: String, video: String, target: livephoto.core.ProtocolSelector): CoreResult<CreatedPhoto> = error("Must not create")
            override fun formatFailure(error: Exception, imageName: String?, videoName: String?) = error.message.orEmpty()
        }
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { livephoto.ui.LivePhotoScreen(platform) } } }
        openPhotoTool(2)
        compose.onNodeWithTag("prepare-clean-split").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
        compose.onNodeWithTag("prepare-clean-split").performScrollTo().performClick()
        compose.onNodeWithTag("clean-split-report").assertTextContains("尚未保存", substring = true)
        assertTrue(saves == 0)
        compose.onNodeWithTag("save-split-image").performScrollTo().performClick()
        compose.onNodeWithTag("split-image-status").assertTextEquals("TEST_IMAGE_SAVED")
        compose.onNodeWithTag("save-split-video").performScrollTo().performClick()
        compose.onNodeWithTag("split-video-status").assertTextContains("TEST_VIDEO_SAVE_FAILURE", substring = true)
        compose.onNodeWithTag("split-image-status").assertTextEquals("TEST_IMAGE_SAVED")
        assertTrue(saves == 2)
        compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
        compose.onNodeWithTag("save-split-image").assertDoesNotExist()
        compose.onNodeWithTag("clean-split-report").assertDoesNotExist()
        returnToEmptyCreate()
    }

    @Test fun sharedExtractionUsesItsOwnInputAndReportsExactExport() {
        var extracted = false
        val platform = object : LivePhotoUiPlatform {
            override val outputDescription = "unused"
            override val supportsAnalysis = true
            override val supportsVideoExtraction = true
            override val videoExportSuccessMessage = "TEST_VIDEO_SAVED"
            @Composable override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit = {
                check(kind == MediaKind.LivePhoto); onResult("opaque-extraction")
            }
            override suspend fun describe(reference: String) = livephoto.ui.SelectedMediaInfo("motion.jpg", 128)
            override suspend fun extractVideo(reference: String): CoreResult<livephoto.ui.ExtractedVideo> {
                check(reference == "opaque-extraction"); extracted = true
                return CoreResult.Success(livephoto.ui.ExtractedVideo(4u, "video/mp4", "mp4", livephoto.core.Digest("0".repeat(64)),
                    livephoto.core.PreservationReport(), emptyList()))
            }
            override suspend fun create(image: String, video: String, target: livephoto.core.ProtocolSelector): CoreResult<CreatedPhoto> = error("Must not create")
            override fun formatFailure(error: Exception, imageName: String?, videoName: String?) = error.message.orEmpty()
        }
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { livephoto.ui.LivePhotoScreen(platform) } } }
        openPhotoTool(1)
        compose.onNodeWithTag("extract-video").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
        compose.onNodeWithTag("extract-video").performScrollTo().performClick()
        compose.onNodeWithTag("extraction-report").assertTextContains("TEST_VIDEO_SAVED", substring = true)
        expandReport("提取")
        compose.onNodeWithTag("extraction-report").assertTextContains("ExactExtraction：Verified", substring = true)
        assertTrue(extracted)
        returnToEmptyCreate()
    }

    @Test fun sharedReadonlyPanelWorksWithoutCreationInputs() {
        val platform = object : LivePhotoUiPlatform {
            override val outputDescription = "unused"
            override val supportsAnalysis = true
            @Composable override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit = {
                check(kind == MediaKind.LivePhoto)
                onResult("analysis-only")
            }
            override suspend fun describe(reference: String) = livephoto.ui.SelectedMediaInfo("unknown.jpg", 4)
            override suspend fun analyze(reference: String) = livephoto.ui.analyzeSelectedPhoto(AndroidCoreFactory.create(), byteArrayOf(0, 1, 2, 3))
            override suspend fun create(image: String, video: String, target: livephoto.core.ProtocolSelector): CoreResult<CreatedPhoto> =
                error("Read-only analysis must not generate a photo")
            override fun formatFailure(error: Exception, imageName: String?, videoName: String?) = error.message.orEmpty()
        }
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { livephoto.ui.LivePhotoScreen(platform) } } }
        openPhotoTool(0)
        compose.onNodeWithTag("analyze").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
        compose.onNodeWithTag("analyze").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("analysis-report").assertTextContains("未修改输入、未生成文件", substring = true)
        expandReport("检查")
        compose.onNodeWithTag("analysis-report").assertTextContains("识别结果：Unknown", substring = true)
        returnToEmptyCreate()
    }

    @Test fun sharedScreenUsesOpaqueReferencesAndScrollsInAShortWindow() {
        val platform = object : LivePhotoUiPlatform {
            override val outputDescription = "test-output"
            @Composable override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit =
                { onResult(if (kind == MediaKind.Image) "opaque-image" else "opaque-video") }
            override suspend fun describe(reference: String) = livephoto.ui.SelectedMediaInfo(reference, 2048)
            override suspend fun create(image: String, video: String, target: livephoto.core.ProtocolSelector): CoreResult<CreatedPhoto> {
                check(image == "opaque-image" && video == "opaque-video")
                return CoreResult.Failure(CoreError(livephoto.core.IssueCode("TEST_FAILURE"), Stage.Parse, "shared platform test"))
            }
            override fun formatFailure(error: Exception, imageName: String?, videoName: String?) = error.message.orEmpty()
        }
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme { Box(Modifier.size(320.dp, 360.dp)) { livephoto.ui.LivePhotoScreen(platform) } }
            }
        }
        compose.onNodeWithTag("select-image").performScrollTo().performClick()
        compose.onNodeWithTag("select-video").performScrollTo().performClick()
        compose.onNodeWithTag("create").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("status").assertIsDisplayed().assertTextContains("shared platform test", substring = true)
    }

    @Test fun selectionButtonsAreBelowAppBarAndMissingInputsDisableCreate() {
        val bar = compose.onNodeWithTag("app-bar").fetchSemanticsNode().boundsInRoot
        val image = compose.onNodeWithTag("select-image").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(image.top >= bar.bottom, "App bar must not cover image selection")
        val video = compose.onNodeWithTag("select-video").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(video.top >= bar.bottom, "App bar must not cover video selection")
        compose.onNodeWithTag("create").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun videoOnlyClearlyRequiresImageAndCannotGenerate() {
        withPickerResults(Uri.parse("content://livephoto-ui-test/video")) {
            compose.onNodeWithTag("select-video").performScrollTo().performClick()
            assertInputSelected("video", "视频")
            compose.onNodeWithTag("image-selection").assertTextEquals("图片：未选择")
            compose.onNodeWithTag("create").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithTag("input-readiness").assertTextEquals("已选择视频，还需选择图片")
        }
    }

    @Test fun pickerCancellationAndActivityRecreationKeepBothSelections() {
        withPickerResults(Uri.parse("content://livephoto-ui-test/image"), Uri.parse("content://livephoto-ui-test/video"), null) {
            compose.onNodeWithTag("select-image").performScrollTo().performClick()
            compose.onNodeWithTag("select-video").performScrollTo().performClick()
            compose.onNodeWithContentDescription("重新选择图片").performScrollTo().performClick() // Cancel; keep both inputs.
            assertInputSelected("image", "图片")
            assertInputSelected("video", "视频")
            val references = compose.runOnUiThread { compose.activity.selections.snapshot().references }
            assertTrue(references.values.toSet() == setOf("content://livephoto-ui-test/image", "content://livephoto-ui-test/video"))
            compose.activityRule.scenario.recreate()
            assertInputSelected("image", "图片")
            assertInputSelected("video", "视频")
            compose.onNodeWithTag("create").assertIsDisplayed().assertIsEnabled()
            assertTrue(compose.runOnUiThread { compose.activity.selections.snapshot().references } == references)
        }
    }

    @Test fun pickerFlagsAndUiRemovalUseReadLeaseUntilLastSelection() {
        val uri = Uri.parse("content://livephoto-ui-test/aliased")
        val replacement = Uri.parse("content://livephoto-ui-test/replacement")
        val retained = mutableListOf<String>()
        val released = mutableListOf<String>()
        val permissions = object : DocumentReadPermissions {
            override fun retain(uri: String): Boolean { retained += uri; return true }
            override fun release(uri: String) { released += uri }
        }
        val selections = DocumentReadLeases(permissions)
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    LivePhotoScreen(selections, describe = { SelectedMediaInfo("fixture", 32) }) { _, _, _ -> error("Do not create") }
                }
            }
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        withPickerResults(uri, uri, replacement, flags = flags) {
            compose.onNodeWithTag("select-image").performScrollTo().performClick()
            openPhotoTool(0)
            compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
            compose.onNodeWithTag("page-0").performClick()
            compose.onNodeWithContentDescription("移除图片").performScrollTo().performClick()
            assertTrue(compose.runOnUiThread { retained == listOf(uri.toString(), uri.toString()) && released.isEmpty() })
            openPhotoTool(0)
            compose.onNodeWithTag("select-analysis").performScrollTo().performClick()
            assertTrue(compose.runOnUiThread { released == listOf(uri.toString()) })
        }
        compose.runOnUiThread { selections.close() }
        assertTrue(released == listOf(uri.toString(), replacement.toString()))
    }

    @Test fun selectedFileSizesAndActualParseFailureAreVisibleWithoutManualScroll() {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    LivePhotoScreen(compose.activity.selections, describe = { uri ->
                        if (uri.lastPathSegment == "image") SelectedMediaInfo("cover.jpg", 2048)
                        else SelectedMediaInfo("clip.mp4", 3145728)
                    }) { _, _, _ ->
                        CoreResult.Failure(CoreError(IssueCode("UNSUPPORTED_CONTAINER"), Stage.Parse,
                            "Multiple sample descriptions are not implemented", details = mapOf(
                                "livephoto.step" to livephoto.core.Value.Text("inspect-video"),
                                "livephoto.inputRole" to livephoto.core.Value.Text("video"))))
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
            compose.onNodeWithTag("create").assertIsDisplayed().assertIsEnabled().performClick()
            // The result must be brought into the content viewport without the user scrolling.
            val error = compose.onNodeWithTag("status").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val header = compose.onNodeWithTag("app-bar").fetchSemanticsNode().boundsInRoot
            val footer = compose.onNodeWithTag("main-action").fetchSemanticsNode().boundsInRoot
            assertTrue(error.top >= header.bottom && error.bottom <= footer.top, "Error must be visible between header and fixed action bar")
            expandReport("生成")
            compose.onNodeWithTag("status")
                .assertTextContains("UNSUPPORTED_CONTAINER", substring = true)
                .assertTextContains("Multiple sample descriptions are not implemented", substring = true)
                .assertTextContains("视频输入（clip.mp4）", substring = true)
                .assertTextContains("解析视频容器、轨道与采样表", substring = true)
        }
    }

    private fun openPhotoTool(index: Int) {
        compose.onNodeWithTag("page-1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("create").assertDoesNotExist()
        if (index != 0) {
            compose.onNodeWithTag("tool-selector").performScrollTo().performClick()
            compose.onNodeWithTag("tool-$index").assertIsEnabled().performClick()
        }
    }

    private fun returnToEmptyCreate() {
        compose.onNodeWithTag("create").assertDoesNotExist()
        compose.onNodeWithTag("page-0").assertIsDisplayed().performClick()
        compose.onNodeWithTag("create").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("image-selection").assertTextEquals("图片：未选择")
        compose.onNodeWithTag("video-selection").assertTextEquals("视频：未选择")
    }

    private fun expandReport(title: String) {
        compose.onNodeWithContentDescription("展开${title}详情").performScrollTo().performClick()
    }

    private fun assertInputSelected(tag: String, label: String) {
        compose.onNodeWithTag("select-$tag").assertDoesNotExist()
        compose.onNodeWithTag("$tag-selection").assertDoesNotExist()
        compose.onNodeWithContentDescription("重新选择$label").assertExists()
        compose.onNodeWithContentDescription("移除$label").assertExists()
    }

    private fun withPickerResults(vararg results: Uri?, flags: Int = 0, block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var position = 0
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                check(position < results.size) { "Unexpected document picker launch" }
                val uri = results[position++]
                return if (uri == null) Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent())
                else Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(flags))
            }
        }
        instrumentation.addMonitor(monitor)
        try { block(); assertTrue(position == results.size, "All picker results must be consumed") }
        finally { instrumentation.removeMonitor(monitor) }
    }
}
