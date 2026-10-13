package livephoto.adapters.files
import kotlin.test.*

class LocalHandleTest {
    @Test fun zeroResizeAndExplicitOffsetReadsDoNotDependOnSharedCursor() = sandbox { box ->
        val file = box.file("handle", byteArrayOf(1, 2, 3))
        box.fs.openReadWrite(file, mustExist = true).use { handle ->
            handle.resize(0)
            assertEquals(0L, handle.size())
            handle.write(0, byteArrayOf(5, 6, 7), 0, 3)
            handle.flush()
            val last = ByteArray(1)
            val first = ByteArray(1)
            assertEquals(1, handle.read(2, last, 0, 1))
            assertEquals(1, handle.read(0, first, 0, 1))
            assertContentEquals(byteArrayOf(7), last)
            assertContentEquals(byteArrayOf(5), first)
            handle.resize(1)
            assertEquals(1L, handle.size())
            assertEquals(-1, handle.read(1, last, 0, 1))
        }
        assertContentEquals(byteArrayOf(5), box.contents(file))
    }
    @Test fun exclusiveCreationAndRequiredExistingHandlesNeverOverwrite() = sandbox { box ->
        val file = box.file("existing", byteArrayOf(8))
        assertFails { box.fs.openReadWrite(file, mustCreate = true).close() }
        assertFails { box.fs.openReadWrite(box.root / "missing", mustExist = true).close() }
        assertContentEquals(byteArrayOf(8), box.contents(file))
        assertFalse(box.fs.exists(box.root / "missing"))
    }
}
