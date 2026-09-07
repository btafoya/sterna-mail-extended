package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.RowNumbering
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [numberingStampsOfRows] EXECUTED — the decision that manufactures the stamp THE TWO #99 GUARDS
 * OPPOSE (the held-back destroy, and the move between accounts), and which nothing ran until this
 * test.
 *
 * Not every stamp in the app: `MailRepository.recordedUidValidity` makes a SECOND one, at
 * FOLDER level, opposed just as late (`MailBySenderViewModel.askDelete` → `PendingDelete.numbering`
 * → `repo.deleteAll(credentials, ids, pending.numbering)`, pinned by
 * `MoveNumberingAtTheGestureTest`). Covering this function covers the ROW stamp, nothing else.
 *
 * What was uncovered, and what each mutation costs the user:
 *
 * - `credentials.protocol != MailProtocol.IMAP` → `==`: an empty map on every IMAP account. Every
 *   stamp then reads as absent, an absent stamp PASSES by construction at both guards, and the
 *   bulk delete destroys the line that was swapped under the tick while the cross-account move
 *   sends it to the other account — with no Undo there to take it back.
 * - `it.id to it.uidValidity` → `it.id to null`: the same damage, on 100% of IMAP rows, with the
 *   protocol test still reading exactly as it does today.
 *
 * Neither is visible from source text, which is why the decision was lifted out of
 * `MailRepository` (Room + `Context` + an `LruCache` in a property initialiser: not instantiable
 * in a JVM test). The remaining half — that the repository still DELEGATES here, with the account
 * id and the chunk — cannot be executed and is pinned as whole lines by
 * [NumberingStampsSourceTest].
 */
class NumberingStampsTest {

    /** Records every id list the database read was handed, so "never called" is observable. */
    private class Rows(private val stamps: Map<String, Long?>) {
        val calls = mutableListOf<List<String>>()

        suspend fun read(ids: List<String>): List<RowNumbering> {
            calls += ids
            // Like the DAO: an id with no cached row simply does not come back.
            return ids.filter { it in stamps }.map { RowNumbering(it, stamps[it]) }
        }
    }

    private fun credentials(protocol: MailProtocol, id: String = "acc") =
        AccountCredentials(server = "https://mail.example.test", username = "u", password = "p", id = id, protocol = protocol)

    @Test fun `a JMAP account is stamped by nothing and never reaches the database`() = runBlocking {
        val rows = Rows(mapOf("jmap:acc:1" to 42L))

        val stamps = numberingStampsOfRows(
            credentials(MailProtocol.JMAP),
            listOf("jmap:acc:1", "jmap:acc:2"),
        ) { rows.read(it) }

        assertEquals(emptyMap<String, Long?>(), stamps)
        // The return alone would still pass if the guard moved AFTER the read: on JMAP there is
        // nothing to number, so the read must not happen at all.
        assertEquals("a JMAP selection read the emails table for stamps it cannot have", 0, rows.calls.size)
    }

    @Test fun `an IMAP account is stamped with the numbering of each of its rows`() = runBlocking {
        val rows = Rows(mapOf("imap:acc:INBOX:7" to 42L, "imap:acc:INBOX:8" to 42L, "imap:acc:INBOX:9" to 77L))

        val stamps = numberingStampsOfRows(
            credentials(MailProtocol.IMAP),
            listOf("imap:acc:INBOX:7", "imap:acc:INBOX:8", "imap:acc:INBOX:9"),
        ) { rows.read(it) }

        assertEquals(
            mapOf("imap:acc:INBOX:7" to 42L, "imap:acc:INBOX:8" to 42L, "imap:acc:INBOX:9" to 77L),
            stamps,
        )
        assertEquals(1, rows.calls.size)
    }

    @Test fun `a row cached without a numbering keeps its key, with a null value`() = runBlocking {
        // Rows written before schema v25 have no uidValidity. PRESENT-and-null, never dropped:
        // the guard reads it as "nothing to oppose", and the two shapes must stay distinguishable.
        val rows = Rows(mapOf("imap:acc:INBOX:7" to 42L, "imap:acc:INBOX:8" to null))

        val stamps = numberingStampsOfRows(
            credentials(MailProtocol.IMAP),
            listOf("imap:acc:INBOX:7", "imap:acc:INBOX:8"),
        ) { rows.read(it) }

        assertEquals(mapOf("imap:acc:INBOX:7" to 42L, "imap:acc:INBOX:8" to null), stamps)
        assertTrue("the null-stamped row must still be a key of the answer", "imap:acc:INBOX:8" in stamps)
    }

    @Test fun `an id with no cached row is absent, not null-valued`() = runBlocking {
        val rows = Rows(mapOf("imap:acc:INBOX:7" to 42L))

        val stamps = numberingStampsOfRows(
            credentials(MailProtocol.IMAP),
            listOf("imap:acc:INBOX:7", "imap:acc:INBOX:evicted"),
        ) { rows.read(it) }

        assertEquals(mapOf("imap:acc:INBOX:7" to 42L), stamps)
        assertTrue("an evicted row must not be invented as a key", "imap:acc:INBOX:evicted" !in stamps)
    }

    @Test fun `an empty selection never touches the database`() = runBlocking {
        val rows = Rows(emptyMap())

        val stamps = numberingStampsOfRows(credentials(MailProtocol.IMAP), emptyList()) { rows.read(it) }

        assertEquals(emptyMap<String, Long?>(), stamps)
        assertEquals("an empty selection issued an IN () against the emails table", 0, rows.calls.size)
    }

    /**
     * The whole point of the chunk, at the size that breaks: a select-all of a Trash. Below
     * Android 12 SQLite refuses past 999 bound variables, and this read is outside any
     * `runCatching` — unbounded, the delete does not fail, it crashes, and nothing on screen says
     * why.
     */
    @Test fun `a selection of more than 999 ids is read in bounded chunks, whole`() = runBlocking {
        val ids = (1..1201).map { "imap:acc:INBOX:$it" }
        val rows = Rows(ids.associateWith { id -> id.substringAfterLast(':').toLong() })

        val stamps = numberingStampsOfRows(credentials(MailProtocol.IMAP), ids) { rows.read(it) }

        assertTrue(
            "a chunk of ${rows.calls.maxOf { it.size }} ids binds past SQLite's 999 limit: the " +
                "delete crashes instead of failing",
            rows.calls.all { it.size <= 999 },
        )
        assertEquals(listOf(200, 200, 200, 200, 200, 200, 1), rows.calls.map { it.size })
        // Nothing lost, nothing read twice: the stamps must cover the selection exactly.
        assertEquals(ids, rows.calls.flatten())
        assertEquals(ids.size, stamps.size)
        assertEquals(1L, stamps["imap:acc:INBOX:1"])
        assertEquals(1201L, stamps["imap:acc:INBOX:1201"])
    }

    /**
     * A READ THAT THROWS MUST TAKE THE WHOLE GESTURE DOWN WITH IT, and the reason is the exact
     * opposite of what a defensive reflex suggests.
     *
     * `emailDao.numberingOfRows` is a Room `@Query`: it throws on I/O, on a locked database, and
     * on the very bound-variable refusal the chunk exists to avoid. Swallowing that
     * (`runCatching { read(chunk) }.getOrDefault(emptyList())`) compiles, reads as prudence, and
     * answers an EMPTY map — which is not "no stamps", it is "no stamps to oppose": an absent
     * stamp PASSES by construction at both #99 guards. The crash, which the user sees and reports,
     * would become a silent bypass: the line swapped under the tick destroyed, or sent to the
     * other account with no Undo to take it back.
     *
     * This runs what the KDoc of [numberingStampsOfRows] promises — the read sits OUTSIDE any
     * `runCatching`.
     */
    @Test fun `a read that fails is not swallowed into an empty map`() {
        val boom = object {
            var calls = 0
            suspend fun read(ids: List<String>): List<RowNumbering> {
                calls++
                throw IOException("database locked while reading ${ids.size} ids")
            }
        }

        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                numberingStampsOfRows(
                    credentials(MailProtocol.IMAP),
                    listOf("imap:acc:INBOX:7", "imap:acc:INBOX:8"),
                ) { boom.read(it) }
            }
        }

        assertEquals("database locked while reading 2 ids", thrown.message)
        assertEquals("the read must really have been attempted", 1, boom.calls)
    }
}
