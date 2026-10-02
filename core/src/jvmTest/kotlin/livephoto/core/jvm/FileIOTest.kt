package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.google.GoogleFixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileIOTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
    private fun temporary(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("livephoto-test-")
        try { block(directory) } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun createReadbackSplitAndExtractUseRealFilesAndAtomicAssetSets() = temporary { directory -> runImmediate {
        val image = directory.resolve("image-with-wrong-extension.bin")
        val video = directory.resolve("video-with-wrong-extension.jpg")
        Files.write(image, GoogleFixtures.jpeg()); Files.write(video, GoogleFixtures.video().bytes)
        val core = DefaultLivePhotoCore()
        val transaction = DirectoryOutputTransaction(directory.resolve("created"), context)
        val created = value(core.create(CreateRequest(FileBinarySource(image), FileBinarySource(video), ProtocolSelector(ProtocolIds.GoogleV2), output = transaction, context = context)))
        val asset = created.output.assets.single()
        assertTrue(Files.isRegularFile(transaction.assetPath(asset.id)))
        val source = asset.readableSource!!
        assertEquals(Disposition.Live, value(core.detect(ReadRequest(SourceSet.Single(source), context))).disposition)
        val split = DirectoryOutputTransaction(directory.resolve("split"), context)
        val result = value(core.split(SplitRequest(SourceSet.Single(source), output = split, context = context)))
        assertEquals(2, result.output.assets.size)
        assertTrue(result.output.assets.all { Files.exists(split.assetPath(it.id)) })
        val extracted = result.output.assets.single { it.role == AssetRole.MotionVideo }
        assertContentEquals(GoogleFixtures.video().bytes, Files.readAllBytes(split.assetPath(extracted.id)))
        assertContentEquals(GoogleFixtures.jpeg(), Files.readAllBytes(image))
        source.close(); result.output.assets.forEach { it.readableSource?.close() }
    } }
    @Test fun existingDirectoryIsNeverReplacedEvenIfEmpty() = temporary { directory -> runImmediate {
        val root = Files.createDirectory(directory.resolve("existing"))
        val transaction = DirectoryOutputTransaction(root, context)
        assertIs<CoreResult.Failure>(transaction.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg")))
        value(transaction.abort())
        assertTrue(Files.isDirectory(root))
    } }
    @Test fun abortRemovesOnlyOwnedStagingAndNeverPublishes() = temporary { directory -> runImmediate {
        val transaction = DirectoryOutputTransaction(directory.resolve("abort"), context)
        val handle = value(transaction.create(OutputAssetSpec(AssetRole.PrimaryImage, "../../escape", "image/jpeg")))
        value(handle.sink.write(Bytes(byteArrayOf(1, 2, 3))))
        value(transaction.abort())
        assertFalse(Files.exists(transaction.root))
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
        assertFalse(Files.exists(directory.resolve("escape")))
    } }
    @Test fun sourceIdentityTracksModificationAndReadBounds() = temporary { directory -> runImmediate {
        val path = Files.write(directory.resolve("source"), byteArrayOf(1, 2))
        val source = FileBinarySource(path)
        val before = value(source.identity())
        assertEquals(Bytes(byteArrayOf(2)), value(source.readAt(1uL, 100u)))
        assertIs<CoreResult.Failure>(source.readAt(3uL, 1u))
        Files.write(path, byteArrayOf(3, 4, 5))
        assertNotEquals(before, value(source.identity()))
        source.close()
        assertIs<CoreResult.Failure>(source.size())
    } }
    @Test fun outputBudgetAndPreparedStatePreventAdditionalWrites() = temporary { directory -> runImmediate {
        val transaction = DirectoryOutputTransaction(directory.resolve("budget"), Context(Limits(4uL, 4uL)))
        val handle = value(transaction.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg")))
        value(handle.sink.write(Bytes(byteArrayOf(1, 2, 3, 4))))
        assertIs<CoreResult.Failure>(handle.sink.write(Bytes(byteArrayOf(5))))
        value(transaction.prepare())
        assertIs<CoreResult.Failure>(handle.sink.write(Bytes(byteArrayOf(5))))
        value(transaction.abort())
    } }
}
