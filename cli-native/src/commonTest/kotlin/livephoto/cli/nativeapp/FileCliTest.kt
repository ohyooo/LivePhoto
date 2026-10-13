package livephoto.cli.nativeapp

import livephoto.cli.shared.*
import livephoto.core.*
import livephoto.adapters.files.localFileSystem
import okio.*
import okio.Path.Companion.toPath
import kotlin.coroutines.*
import kotlin.random.Random
import kotlin.test.*

private fun <T> immediate(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result) { "Test unexpectedly suspended" }.getOrThrow()
}

private class Platform(root: String) : FileCliPlatform({ root }) {
    override fun environment(name: String): String? = null
    override fun nanoTime(): Long = 0L
    override fun error(message: String) {}
    override fun exceptionType(error: Throwable): String = "Exception"
    override fun exceptionFrames(error: Throwable): List<DiagnosticFrame> = emptyList()
}

class FileCliTest {
    @Test fun referenceCreateUsesFullCliAndNestedUnicodeParent(): Unit = immediate {
        val fs = localFileSystem
        fun fixture(name: String): String = listOf("reference/$name", "../reference/$name")
            .first { fs.exists(it.toPath()) }
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "livephoto-cli-test-${Random.nextLong().toULong()}"
        fs.createDirectory(root, mustCreate = true)
        try {
            val parent = root / "verify native 路径"
            fs.createDirectory(parent, mustCreate = true)
            val workspace = parent / "private workspace 输入"
            fs.createDirectory(workspace, mustCreate = true)
            var json = ""
            val exit = CliEngine(Platform(workspace.toString())).run(listOf("create",
                "--image", fixture("video.jpg"), "--video", fixture("video.mp4"), "--target", "oplus.olive",
                "--profile", "jpeg-no-tail", "--output-dir", (parent / "created live").toString())) { json = it }
            assertEquals(0, exit, json)
            assertTrue(json.contains("\"state\":\"Committed\""), json)
            assertTrue(fs.list(workspace).isEmpty())
        } finally {
            check(root.name.startsWith("livephoto-cli-test-"))
            fs.deleteRecursively(root, mustExist = true) // Only this exclusively reserved test sandbox.
        }
    }
    @Test fun missingWorkspaceReturnsStructuredFailureWithoutCreatingOutput(): Unit = immediate {
        var json = ""
        assertEquals(3, CliEngine(Platform("")).run(listOf("detect", "--input", "private-path")) { json = it })
        assertTrue(json.contains("INVALID_ARGUMENT"))
        assertFalse(json.contains("private-path"))
    }

    @Test fun realInputsShareBudgetAndCleanupLeavesOriginalUntouched(): Unit = immediate {
        val fs = localFileSystem
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "livephoto-cli-test-${Random.nextLong().toULong()}"
        fs.createDirectory(root, mustCreate = true)
        val input = root / "输入 file.bin"
        try {
            fs.write(input) { write(byteArrayOf(1, 2)) }
            var json = ""
            val engine = CliEngine(Platform(root.toString()))
            assertEquals(3, engine.run(listOf("create", "--image", input.toString(), "--video", input.toString(),
                "--target", "oplus.olive", "--output-dir", (root / "output").toString(), "--max-bytes", "3")) { json = it })
            assertTrue(json.contains("RESOURCE_LIMIT_EXCEEDED"))
            assertEquals(listOf(input), fs.list(root))
            assertContentEquals(byteArrayOf(1, 2), fs.read(input) { readByteArray() })
            assertEquals(0, engine.run(listOf("--version")) {})
            assertEquals(listOf(input), fs.list(root))
        } finally {
            fs.delete(input)
            fs.delete(root, mustExist = true)
        }
    }

    @Test fun frozenSourceSessionClosesBeforeReuseWithDifferentBudget(): Unit = immediate {
        val fs = localFileSystem
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "livephoto-cli-test-${Random.nextLong().toULong()}"
        fs.createDirectory(root, mustCreate = true)
        val input = root / "input"
        val platform = Platform(root.toString())
        try {
            fs.write(input) { writeByte(1) }
            val source = platform.openSource(input.toString(), Context(Limits(3uL, 3uL)))
            assertIs<CoreResult.Success<Unit>>(platform.finish())
            assertIs<CoreResult.Failure>(source.size())
            val second = platform.openSource(input.toString(), Context(Limits(2uL, 2uL)))
            assertEquals(1uL, assertIs<CoreResult.Success<ULong>>(second.size()).value)
            assertIs<CoreResult.Success<Unit>>(platform.finish())
            assertEquals(listOf(input), fs.list(root))
        } finally {
            platform.finish(); fs.delete(input); fs.delete(root, mustExist = true)
        }
    }
}
