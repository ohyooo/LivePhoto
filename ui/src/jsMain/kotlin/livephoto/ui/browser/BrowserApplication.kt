@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package livephoto.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.material3.Text
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Promise
import livephoto.core.*
import livephoto.core.memory.*
import livephoto.ui.*

public fun main() {
    ComposeViewport(document.getElementById("app")!!, configure = { isA11YEnabled = true }) {
        val platform = remember { BrowserPlatform() }
        val resolver = LocalFontFamilyResolver.current
        var fontsReady by remember { mutableStateOf(false) }
        var fontError by remember { mutableStateOf(false) }
        LaunchedEffect(resolver) {
            try {
                val response = window.fetch("fonts/NotoSansSC-Regular.otf").await()
                check(response.ok) { "Bundled font unavailable" }
                val values = Int8Array(response.arrayBuffer().await())
                val bytes = ByteArray(values.length) { values.asDynamic()[it].unsafeCast<Byte>() }
                resolver.preload(FontFamily(Font("NotoSansSC", bytes)))
                fontsReady = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { fontError = true }
        }
        MaterialTheme {
            if (fontsReady) LivePhotoScreen(platform)
            else Text(if (fontError) "Unable to load bundled font. Reload the page or check the static server." else "Loading fonts...")
        }
    }
}

private class BrowserPlatform : LivePhotoUiPlatform {
    private val files = mutableMapOf<String, File>()
    private var next = 0
    override val outputDescription = "浏览器下载"
    override val successMessage = "已生成并发起浏览器下载，请检查下载记录。"

    @Composable
    override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit {
        val currentResult by rememberUpdatedState(onResult)
        val input = remember {
            (document.createElement("input") as HTMLInputElement).apply {
                type = "file"
                accept = if (kind == MediaKind.Image) "image/*" else "video/*"
                id = if (kind == MediaKind.Image) "livephoto-image-input" else "livephoto-video-input"
                style.display = "none"
            }
        }
        DisposableEffect(input) {
            input.onchange = {
                val file = input.files?.item(0)
                if (file != null) {
                    val prefix = "${kind.name}:"
                    files.keys.filter { it.startsWith(prefix) }.forEach { files.remove(it) }
                    val reference = "$prefix${next++}"
                    files[reference] = file
                    currentResult(reference)
                } else currentResult(null)
                input.value = ""
                Unit
            }
            document.body!!.appendChild(input)
            onDispose { input.onchange = null; input.remove() }
        }
        return { input.click() }
    }

    override suspend fun describe(reference: String): SelectedMediaInfo {
        val file = files[reference] ?: error("Selected browser file is no longer available; select it again")
        return SelectedMediaInfo(file.name, file.size.toLong())
    }

    override suspend fun create(image: String, video: String, target: ProtocolSelector): CoreResult<CreatedPhoto> {
        val photo = files[image] ?: error("Select the image again")
        val movie = files[video] ?: error("Select the video again")
        if (photo.size.toLong() + movie.size.toLong() > 32L * 1024 * 1024) return CoreResult.Failure(CoreError(
            IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Combined browser inputs exceed 32 MiB"))
        suspend fun bytes(file: File): ByteArray {
            val buffer = file.asDynamic().arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await()
            val values = Int8Array(buffer)
            return ByteArray(values.length) { values.asDynamic()[it].unsafeCast<Byte>() }
        }
        val ctx = Context(Limits(32uL * 1024u * 1024u, 48uL * 1024u * 1024u))
        val imageSource = MemoryBinarySource(Bytes(bytes(photo)), SourceId("browser-image"))
        val videoSource = MemoryBinarySource(Bytes(bytes(movie)), SourceId("browser-video"))
        val output = MemoryOutputTransaction(ctx, "browser-create")
        var created: OperationResult? = null
        try {
            val policy = if (target.protocol == ProtocolIds.Oplus) MutationPolicy(loss = LossPolicy.AllowListed,
                allowedLosses = listOf(MetadataLoss.OplusCameraUserComment, MetadataLoss.OplusAsciiCameraUserComment)) else MutationPolicy()
            return when (val result = DefaultLivePhotoCore().create(CreateRequest(imageSource, videoSource, target,
                policy = policy, output = output, context = ctx))) {
                is CoreResult.Failure -> result
                is CoreResult.Success -> {
                    created = result.value
                    val asset = result.value.output.assets.single()
                    val blob = Blob(arrayOf(output.committedAssets().getValue(asset.id).toByteArray()), BlobPropertyBag(type = asset.mime))
                    val url = window.asDynamic().URL.createObjectURL(blob).unsafeCast<String>()
                    try {
                        val link = (document.createElement("a") as HTMLAnchorElement).apply {
                            href = url
                            download = "LivePhoto-${target.protocol.value}.jpg"
                        }
                        document.body!!.appendChild(link)
                        try { link.click() } finally { link.remove() }
                    } finally { window.setTimeout({ window.asDynamic().URL.revokeObjectURL(url) }, 60_000) }
                    val operation = result.value.copy(output = MediaOutput(
                        listOf(asset.copy(readableSource = null, accessReference = "browser-download:${asset.id.value}")), result.value.output.receipt))
                    CoreResult.Success(CreatedPhoto(operation))
                }
            }
        } finally {
            created?.output?.assets?.forEach { it.readableSource?.close() }
            imageSource.close(); videoSource.close()
        }
    }

    override fun formatFailure(error: Exception, imageName: String?, videoName: String?): String =
        "生成失败\n浏览器文件读取或下载失败：${error.message?.take(2000) ?: "未知错误"}\n请重新选择文件；原片不会被覆盖。"
}
