package app.sterna.core.data.mail

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A folder's mod-sequence beside its numbering, through the shipped store and the shipped
 * statements on in-memory SQLite: kept while the numbering stands, gone with it, never shared.
 */
class ModSeqStoreSqlTest {

    private val cache = JdbcMailCache()
    private val store = cache.store()

    @After fun tearDown() = cache.close()

    @Test fun `a folder seen for the first time has a numbering and no mod-sequence`(): Unit = runBlocking {
        store.record("accA", "INBOX", 9L)

        assertEquals(mapOf("accA/INBOX" to (9L to null)), cache.numbering())
        assertNull(store.recordedModSeq("accA", "INBOX"))
    }

    @Test fun `a mod-sequence is kept under the numbering it was read under`(): Unit = runBlocking {
        store.record("accA", "INBOX", 9L)

        store.recordModSeq("accA", "INBOX", ImapModSeqMark(9L, 812L))

        assertEquals(812L, store.recordedModSeq("accA", "INBOX"))
    }

    @Test fun `recording the same numbering again keeps the mod-sequence`(): Unit = runBlocking {
        store.record("accA", "INBOX", 9L)
        store.recordModSeq("accA", "INBOX", ImapModSeqMark(9L, 812L))

        store.record("accA", "INBOX", 9L)

        assertEquals(
            "the record was written back whole, without its mod-sequence",
            mapOf("accA/INBOX" to (9L to 812L)),
            cache.numbering(),
        )
    }

    @Test fun `a renumbering drops the mod-sequence and records the new numbering`(): Unit = runBlocking {
        store.record("accA", "INBOX", 9L)
        store.recordModSeq("accA", "INBOX", ImapModSeqMark(9L, 812L))

        store.invalidate("accA", "INBOX", 10L)

        assertEquals(mapOf("accA/INBOX" to (10L to null)), cache.numbering())
        assertNull(store.recordedModSeq("accA", "INBOX"))
    }

    @Test fun `a mod-sequence read under a numbering the folder no longer has is not kept`(): Unit = runBlocking {
        store.record("accA", "INBOX", 10L)

        store.recordModSeq("accA", "INBOX", ImapModSeqMark(9L, 812L))

        assertNull(store.recordedModSeq("accA", "INBOX"))
    }

    @Test fun `a folder with no recorded numbering keeps no mod-sequence`(): Unit = runBlocking {
        store.recordModSeq("accA", "INBOX", ImapModSeqMark(9L, 812L))

        assertEquals(emptyMap<String, Pair<Long, Long?>>(), cache.numbering())
    }

    @Test fun `two accounts and two folders never read each other's mod-sequence`(): Unit = runBlocking {
        listOf("accA" to "INBOX", "accB" to "INBOX", "accA" to "Archive").forEach { (account, folder) -> store.record(account, folder, 9L) }

        store.recordModSeq("accA", "INBOX", ImapModSeqMark(9L, 100L))
        store.recordModSeq("accB", "INBOX", ImapModSeqMark(9L, 500L))

        assertEquals(100L, store.recordedModSeq("accA", "INBOX"))
        assertEquals(500L, store.recordedModSeq("accB", "INBOX"))
        assertNull(store.recordedModSeq("accA", "Archive"))
    }

    @Test fun `a removed account's mod-sequence goes with its numbering`(): Unit = runBlocking {
        store.record("accA", "INBOX", 9L)
        store.record("accB", "INBOX", 9L)
        store.recordModSeq("accA", "INBOX", ImapModSeqMark(9L, 100L))
        store.recordModSeq("accB", "INBOX", ImapModSeqMark(9L, 500L))

        cache.uidValidityDao.deleteForAccount("accA")

        assertEquals(mapOf("accB/INBOX" to (9L to 500L)), cache.numbering())
        assertNull(store.recordedModSeq("accA", "INBOX"))
    }
}
