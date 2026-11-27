package livephoto.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentReadLeasesTest {
    @Test fun aliasesOwnIndependentSelectionsAndReleaseReadOnlyAfterLastOwner() {
        val flags = FakePermissions()
        val leases = DocumentReadLeases(flags)
        val create = leases.select(IMAGE, mayPersist = true)
        val tools = leases.select(IMAGE, mayPersist = true)
        assertNotEquals(create, tools)
        assertEquals(IMAGE, leases.resolve(create))
        leases.release(create)
        leases.release(create)
        assertEquals(IMAGE, leases.resolve(tools))
        assertTrue(flags.released.isEmpty())
        leases.release(tools)
        assertEquals(listOf(IMAGE), flags.released)
        assertNull(leases.resolve(tools))
    }

    @Test fun recreatedRegistryRestoresSelectionAndCanReleaseItsOwnGrant() {
        val flags = FakePermissions()
        val before = DocumentReadLeases(flags)
        val selected = before.select(IMAGE, mayPersist = true)
        val restored = DocumentReadLeases(flags, before.snapshot())
        assertEquals(IMAGE, restored.resolve(selected))
        assertEquals(listOf(IMAGE), flags.retained) // No re-request during restoration.
        restored.close()
        restored.close()
        assertEquals(listOf(IMAGE), flags.released)
    }

    @Test fun temporaryOrProviderRefusedGrantStillResolvesButIsNotReleasedByUs() {
        val flags = FakePermissions(retainAllowed = false)
        val leases = DocumentReadLeases(flags)
        val temporary = leases.select(IMAGE, mayPersist = false)
        val rejected = leases.select(VIDEO, mayPersist = true)
        assertEquals(IMAGE, leases.resolve(temporary))
        assertEquals(VIDEO, leases.resolve(rejected))
        assertEquals(listOf(VIDEO), flags.retained)
        assertTrue(leases.snapshot().retainedReads.isEmpty())
        leases.close()
        assertTrue(flags.released.isEmpty())
    }

    @Test fun repeatSelectionTakesFreshGrantWhenPreviouslyTemporary() {
        val flags = FakePermissions()
        val leases = DocumentReadLeases(flags)
        val temporary = leases.select(IMAGE, mayPersist = false)
        val replacement = leases.select(IMAGE, mayPersist = true)
        leases.release(temporary)
        assertTrue(flags.released.isEmpty())
        assertEquals(setOf(IMAGE), leases.snapshot().retainedReads)
        leases.release(replacement)
        assertEquals(listOf(IMAGE), flags.released)
    }

    private class FakePermissions(private val retainAllowed: Boolean = true) : DocumentReadPermissions {
        val retained = mutableListOf<String>()
        val released = mutableListOf<String>()
        override fun retain(uri: String): Boolean { retained += uri; return retainAllowed }
        override fun release(uri: String) { released += uri }
    }

    private companion object {
        const val IMAGE = "content://test.documents/image"
        const val VIDEO = "content://test.documents/video"
    }
}
