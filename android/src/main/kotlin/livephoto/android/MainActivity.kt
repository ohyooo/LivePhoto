package livephoto.android

import android.os.Bundle
import android.net.Uri
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*
import livephoto.core.*

/** Minimal manual test screen; no protocol or media processing implementation in UI. */
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var image: Uri? = null
    private var video: Uri? = null
    private lateinit var status: TextView
    private lateinit var createButton: Button
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        image = uri
        status.text = if (uri == null) "未选择图片" else "图片已选择；请选择视频或点击生成"
    }
    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        video = uri
        status.text = if (uri == null) "未选择视频" else "视频已选择；请选择图片或点击生成"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        image = savedInstanceState?.getString("image")?.let(Uri::parse)
        video = savedInstanceState?.getString("video")?.let(Uri::parse)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        status = TextView(this).apply {
            text = "选图片＋视频 → Core 生成 → 保存 Pictures/LivePhoto。\n当前仅 JPEG＋MP4；不裁剪、转码或抽帧。输入合计上限 32 MiB。"
        }
        val target = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("OPPO / OnePlus", "Google Motion Photo V2", "Google MicroVideo V1"))
        }
        root.addView(Button(this).apply { text = "选择图片"; setOnClickListener { imagePicker.launch(arrayOf("image/*")) } })
        root.addView(Button(this).apply { text = "选择视频"; setOnClickListener { videoPicker.launch(arrayOf("video/*")) } })
        root.addView(target)
        createButton = Button(this).apply {
            text = "生成并保存 Live Photo"
            setOnClickListener {
                val chosenImage = image
                val chosenVideo = video
                if (chosenImage == null || chosenVideo == null) { status.text = "请先选择图片和视频"; return@setOnClickListener }
                val selection = when (target.selectedItemPosition) {
                    0 -> ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail"))
                    1 -> ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg"))
                    else -> ProtocolSelector(ProtocolIds.GoogleV1, ProfileId("jpeg"))
                }
                scope.launch {
                    createButton.isEnabled = false
                    status.text = "正在生成与验证…"
                    try {
                        val app = application as LivePhotoApplication
                        when (val result = AndroidCreateController(app, app.core).create(chosenImage, chosenVideo, selection)) {
                            is CoreResult.Success -> status.text = "已保存到 Pictures/LivePhoto。\nCore 验证：${result.value.operation.validation.verdict}；问题报告：${result.value.operation.issues.size} 项。\n默认 BestEffortWithReport，不保证全部 metadata 无损。请在相册测试动态播放与声音；生成成功不等于相册兼容。"
                            is CoreResult.Failure -> status.text = "Core 拒绝：${result.error.code.value}（${result.error.stage}）"
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status.text = "Android 读取/保存失败或超出预算。请检查输入和相册后重试；不会覆盖原片。" }
                    finally { createButton.isEnabled = true }
                }
            }
        }
        root.addView(createButton)
        root.addView(status)
        setContentView(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("image", image?.toString())
        outState.putString("video", video?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
