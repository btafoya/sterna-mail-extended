package app.sterna.core.data.storage

import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.mail.ImapMailService
import app.sterna.core.data.mail.MailboxUidValidityStore
import app.sterna.core.data.mail.PendingExpungeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

/** The store of parts (#157), on a real directory: what each purge takes, and what it leaves. */
class AttachmentStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private val store by lazy { AttachmentStore(temp.newFolder("attachment-store")) }

    private fun id(account: String, folder: String, uid: Long) = ImapMailService.emailId(account, folder, uid)

    private fun put(account: String, emailId: String, part: String = "part:2") =
        store.write(account, emailId, part, "bytes of $emailId".toByteArray())

    @Test fun `a part reads back as written, under its account, message and part only`() {
        put(A, "m1")

        assertArrayEquals("bytes of m1".toByteArray(), store.read(A, "m1", "part:2"))
        assertNull(store.read(A, "m1", "part:3"))
        assertNull(store.read(A, "m2", "part:2"))
        assertNull("two accounts of one server share message ids (#31)", store.read(B, "m1", "part:2"))
    }

    @Test fun `clearing one account's store leaves every other account's parts`() {
        put(A, "m1")
        put(A, "m2")
        put(B, "m1")

        store.clearAccount(A)

        assertEquals(emptyList<String>(), store.emailIds(A))
        assertEquals(listOf("m1"), store.emailIds(B))
        assertArrayEquals("bytes of m1".toByteArray(), store.read(B, "m1", "part:2"))
        assertEquals(listOf(B), store.accountIds())
    }

    @Test fun `clearing the whole store takes every account`() {
        put(A, "m1")
        put(B, "m1")

        store.clearAll()

        assertEquals(emptyList<String>(), store.accountIds())
    }

    @Test fun `a renumbering empties the folder's parts, this account's only, and no folder named alike`() {
        put(A, id(A, "INBOX", 1))
        put(A, id(A, "INBOX", 2), "part:1.2")
        store.markSkipped(A, id(A, "INBOX", 3), "part:2")
        put(A, id(A, "INBOX:Old", 4))
        put(A, id(A, "Archive", 1))
        put(B, id(B, "INBOX", 1))

        runBlocking {
            MailboxUidValidityStore(
                permissive<MailboxUidValidityDao>(), permissive<EmailBodyDao>(), permissive<PurgeSnapshotDao>(),
                permissive<EmailFtsDao>(), PendingExpungeStore.None, store,
            ).invalidate(A, "INBOX", 7L)
        }

        assertEquals(
            "a part kept under the new numbering would be served as another message's",
            listOf(id(A, "Archive", 1), id(A, "INBOX:Old", 4)).sorted(),
            store.emailIds(A).sorted(),
        )
        assertFalse(store.isSkipped(A, id(A, "INBOX", 3), "part:2"))
        assertEquals(listOf(id(B, "INBOX", 1)), store.emailIds(B))
    }

    @Test fun `the orphan sweep takes the messages with no list row left, sparing the ones held`() {
        put(A, "listed")
        put(A, "gone")
        put(A, "held")
        store.markSettled(A, "gone-settled")
        put(B, "gone")

        runBlocking { store.deleteOrphans(A, spared = listOf("held")) { ids -> ids.filter { it == "listed" } } }

        assertEquals(listOf("held", "listed"), store.emailIds(A).sorted())
        assertEquals(listOf("gone"), store.emailIds(B))
    }

    @Test fun `an empty part is neither stored nor served, and no staging file is left behind`() {
        store.write(A, "m1", "part:2", ByteArray(0))

        assertFalse(store.has(A, "m1", "part:2"))
        assertNull(store.read(A, "m1", "part:2"))

        put(A, "m2")
        val left = temp.root.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()
        assertEquals(emptyList<java.io.File>(), left)
    }

    @Test fun `a message is settled and a part skipped only when marked`() {
        assertFalse(store.isSettled(A, "m1"))
        store.markSkipped(A, "m1", "part:2")
        assertTrue(store.isSkipped(A, "m1", "part:2"))
        assertFalse(store.isSkipped(A, "m1", "part:3"))
        assertFalse(store.has(A, "m1", "part:2"))
        store.markSettled(A, "m1")
        assertTrue(store.isSettled(A, "m1"))
        assertEquals("a marked message is known by its id", listOf("m1"), store.emailIds(A))
    }

    private inline fun <reified T : Any> permissive(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> Unit } as T

    private companion object {
        const val A = "acc"
        const val B = "acc2"
    }
}
