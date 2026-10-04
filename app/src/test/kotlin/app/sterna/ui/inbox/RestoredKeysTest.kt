package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a selection gesture acts on when an Undo brought one of its messages home under another id,
 * executed. `InboxViewModel` cannot be built on the JVM, so the pieces it strings together run here
 * as it strings them: the wait ([RestoresInFlight]), what the restore reports ([restoredLanding],
 * [RestoredKeys.record]), and the resolution ([RestoredKeys.rowsFor], [resolveSelectionTargets]).
 * The cache is a map the restore writes and the gesture reads. That the view model calls them in
 * this order is pinned as text by `RestoredKeysWiringLintTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoredKeysTest {

    private val cache = linkedMapOf<EmailKey, Email>()
    private val restores = RestoresInFlight()
    private val restoredKeys = RestoredKeys()

    private fun email(id: String, account: String? = ACCOUNT, subject: String = "s") =
        Email(id = id, accountId = account, mailboxId = "INBOX", subject = subject)

    private fun key(id: String, account: String? = ACCOUNT) = EmailKey(account, id)

    private fun cached(keys: Collection<EmailKey>): List<Email> = keys.mapNotNull { cache[it] }

    /** The restore's report, as `renumberRestored` makes it: the new rows are read from the cache. */
    private fun land(restored: MailRepository.Restored, account: String? = ACCOUNT, imap: Boolean = true) {
        val rows = cached(restored.renumbered.values.map { EmailKey(ACCOUNT, it) })
        restoredKeys.record(restoredLanding(account, restored, rows, imap))
    }

    private suspend fun rowToWrite(row: Email): RowToWrite = restoredKeys.rowToWrite(row) { cached(it) }

    /** A selection gesture past its wait, as `bulkBatched` resolves it. */
    private suspend fun resolve(keys: Set<EmailKey>, displayed: List<Email>?): SelectionTargets =
        resolveSelectionTargets(
            keys = keys,
            cached = cached(keys),
            displayed = displayed,
            restored = restoredKeys.rowsFor(keys) { cached(it) },
        )

    @Test fun `a row ticked while its restore was out is acted on under the id it came home with`() = runTest {
        val server = CompletableDeferred<Unit>()
        val restore = launch {
            server.await()
            cache[key(HOME)] = email(HOME, subject = "from the cache")
            land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME)))
        }
        restores.hold(listOf(key(LEFT)), restore)
        // Ticked and confirmed before the restore returned: the gesture holds the key of the tap.
        val ticked = setOf(key(LEFT))
        var resolved: SelectionTargets? = null
        val gesture = launch {
            restores.settled(ticked)
            // The snapshot still draws the row the Undo put back at the tap, under the id it left.
            resolved = resolve(ticked, displayed = listOf(email(LEFT, subject = "drawn at the tap")))
        }
        runCurrent()
        assertEquals("held while the restore is out", null, resolved)

        server.complete(Unit)
        gesture.join()

        assertEquals(
            "the move back renumbered the message, so the id of the tap names a uid its folder no " +
                "longer carries and a UID MOVE on it moves nothing: the gesture must go out under " +
                "the id the restore reported",
            listOf(HOME),
            resolved?.targets?.map { it.email.id },
        )
        assertEquals(
            "and on the cached row, whose folder can be believed, not on the one drawn at the tap",
            listOf(true),
            resolved?.targets?.map { it.folderTrusted },
        )
        assertEquals(emptySet<EmailKey>(), resolved?.unresolved)
    }

    @Test fun `a message that came home under an id nothing can name is not written, and counts as failed`() = runTest {
        land(MailRepository.Restored(unrestored = emptySet(), unplaced = setOf(LEFT)))

        val resolved = resolve(setOf(key(LEFT)), displayed = listOf(email(LEFT)))

        assertEquals(
            "the drawn row is no fallback here: it carries the id the message has left, and a " +
                "move sent on it is a move on a guessed number",
            emptyList<String>(),
            resolved.targets.map { it.email.id },
        )
        assertEquals(setOf(key(LEFT)), resolved.unresolved)
        assertEquals(
            "and it is said on screen, through the count every bulk path already feeds",
            BulkOutcome.TOTAL,
            bulkOutcome(attempted = 1, failed = resolved.unresolved.size),
        )
    }

    @Test fun `a new id with no row in the cache is unplaced too`() = runTest {
        // The restore paired the ids, and the cache answers nothing for the new one.
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME)))

        val resolved = resolve(setOf(key(LEFT)), displayed = listOf(email(LEFT)))

        assertEquals(emptyList<String>(), resolved.targets.map { it.email.id })
        assertEquals(setOf(key(LEFT)), resolved.unresolved)
    }

    @Test fun `a row that left the cache after it came home is unplaced, not acted on from the screen`() = runTest {
        cache[key(HOME)] = email(HOME)
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME)))
        cache.remove(key(HOME))

        val resolved = resolve(setOf(key(LEFT)), displayed = listOf(email(LEFT)))

        assertEquals(emptyList<String>(), resolved.targets.map { it.email.id })
        assertEquals(setOf(key(LEFT)), resolved.unresolved)
    }

    @Test fun `a message restored twice is acted on under the last id`() = runTest {
        cache[key(HOME)] = email(HOME)
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME)))
        cache.remove(key(HOME))
        cache[key(HOME_AGAIN)] = email(HOME_AGAIN)
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(HOME to HOME_AGAIN)))

        val resolved = resolve(setOf(key(LEFT)), displayed = null)

        assertEquals(listOf(HOME_AGAIN), resolved.targets.map { it.email.id })
        assertEquals(emptySet<EmailKey>(), resolved.unresolved)
    }

    @Test fun `a cached row under the ticked key wins over what an Undo recorded for that key`() = runTest {
        cache[key(HOME)] = email(HOME)
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME)))
        // A folder renumbered since hands the old uid to another message, and it is in the cache.
        cache[key(LEFT)] = email(LEFT, subject = "another message")

        val resolved = resolve(setOf(key(LEFT)), displayed = null)

        assertEquals(
            "the inverse witness: translating every recorded key sends this gesture to the " +
                "message the Undo restored, which nobody ticked",
            listOf("another message"),
            resolved.targets.map { it.email.subject },
        )
    }

    @Test fun `a key no Undo renumbered resolves as before, and costs no read`() = runTest {
        cache[key(HOME)] = email(HOME)
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME)))
        val other = setOf(key("imap:acc:INBOX:9"), key(LEFT, account = "other"))

        val rows = restoredKeys.rowsFor(other) { error("no key here was renumbered, nothing is to be read") }

        assertEquals(
            "neither another message nor the same id in another account (#31) is renumbered",
            RestoredRows.NONE,
            rows,
        )
        val resolved = resolveSelectionTargets(other, cached = emptyList(), displayed = listOf(email("imap:acc:INBOX:9")), restored = rows)
        assertEquals(listOf(false), resolved.targets.map { it.folderTrusted })
        assertEquals(setOf(key(LEFT, account = "other")), resolved.unresolved)
    }

    @Test fun `an IMAP message the Undo failed to bring home is not written under the id its row is redrawn with`() = runTest {
        // Undo offline: the move back was refused, the message is in the bin under the bin's uid.
        land(MailRepository.Restored(unrestored = setOf(LEFT)))

        val resolved = resolve(setOf(key(LEFT)), displayed = listOf(email(LEFT)))

        assertEquals(
            "the row is back on screen under the id it had in the folder it left: a UID MOVE on " +
                "it names a uid that folder no longer carries",
            emptyList<String>(),
            resolved.targets.map { it.email.id },
        )
        assertEquals(setOf(key(LEFT)), resolved.unresolved)
        assertEquals(RowToWrite.Unwritable, rowToWrite(email(LEFT)))
    }

    @Test fun `a JMAP message the Undo failed to bring home is still written under its id`() = runTest {
        land(MailRepository.Restored(unrestored = setOf("m1")), imap = false)

        val resolved = resolve(setOf(key("m1")), displayed = listOf(email("m1")))

        assertEquals(
            "the inverse witness: a JMAP id does not change with the folder, the gesture must go",
            listOf("m1"),
            resolved.targets.map { it.email.id },
        )
        assertEquals(RowToWrite.AsHanded, rowToWrite(email("m1")))
    }

    @Test fun `a gesture handed a row acts on the row its message came home under`() = runTest {
        cache[key(HOME)] = email(HOME, subject = "from the cache")
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME)))

        val now = rowToWrite(email(LEFT, subject = "swiped while the restore was out"))

        assertEquals(
            "a swipe keeps the row it was handed across its wait: written as handed, the delete " +
                "goes out on a uid the folder no longer carries, the server moves nothing and " +
                "the screen says deleted",
            RowToWrite.Landed(email(HOME, subject = "from the cache")),
            now,
        )
    }

    @Test fun `a gesture handed a row nothing can place writes nothing`() = runTest {
        land(MailRepository.Restored(unrestored = emptySet(), unplaced = setOf(LEFT)))
        assertEquals(RowToWrite.Unwritable, rowToWrite(email(LEFT)))
    }

    @Test fun `a gesture handed a row whose new row left the cache writes nothing`() = runTest {
        cache[key(HOME)] = email(HOME)
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME)))
        cache.remove(key(HOME))

        assertEquals(RowToWrite.Unwritable, rowToWrite(email(LEFT)))
    }

    @Test fun `a row no Undo renumbered is written as handed, and costs no read`() = runTest {
        cache[key(HOME)] = email(HOME)
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME)))

        val now = restoredKeys.rowToWrite(email("imap:acc:INBOX:9")) { error("this row was not renumbered, nothing is to be read") }

        assertEquals(RowToWrite.AsHanded, now)
        assertEquals("nor the row that came home, handed in its turn", RowToWrite.AsHanded, rowToWrite(email(HOME)))
    }

    @Test fun `a row the cache holds under the handed key is written as handed`() = runTest {
        cache[key(HOME)] = email(HOME)
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME)))
        cache[key(LEFT)] = email(LEFT, subject = "another message")

        assertEquals(RowToWrite.AsHanded, rowToWrite(email(LEFT, subject = "another message")))
    }

    @Test fun `once forgotten, a key resolves as if no Undo had named it`() = runTest {
        cache[key(HOME)] = email(HOME)
        land(MailRepository.Restored(unrestored = emptySet(), renumbered = mapOf(LEFT to HOME), unplaced = setOf("imap:acc:INBOX:9")))

        restoredKeys.forget()

        assertEquals(
            "the snapshot the records spoke for is gone: kept, they would turn a key the server " +
                "hands out again into the message an old Undo restored",
            RestoredRows.NONE,
            restoredKeys.rowsFor(setOf(key(LEFT), key("imap:acc:INBOX:9"))) { error("nothing is recorded, nothing is to be read") },
        )
        assertEquals(RowToWrite.AsHanded, rowToWrite(email(LEFT)))
    }

    @Test fun `a JMAP restore renumbers nothing, and its rows resolve as before`() = runTest {
        land(MailRepository.Restored(unrestored = emptySet()), imap = false)

        val resolved = resolve(setOf(key("m1")), displayed = listOf(email("m1")))

        assertEquals(listOf("m1"), resolved.targets.map { it.email.id })
        assertEquals(emptySet<EmailKey>(), resolved.unresolved)
    }

    @Test fun `the landing is keyed as the Undo's entries are, and names the key the cached row carries`() {
        // An entry may carry no account while the cached row always carries one.
        val row = email(HOME)

        val landing = restoredLanding(
            accountId = null,
            restored = MailRepository.Restored(
                unrestored = setOf("imap:acc:INBOX:5"),
                renumbered = mapOf(LEFT to HOME, "imap:acc:INBOX:8" to "imap:acc:INBOX:201"),
                unplaced = setOf("imap:acc:INBOX:9"),
            ),
            cached = listOf(row),
            imap = false,
        )

        assertEquals(mapOf(EmailKey(null, LEFT) to row), landing.rows)
        assertEquals(
            "the new key is the cached row's own, the one the list will draw and the tick will hold",
            mapOf(EmailKey(null, LEFT) to row.emailKey()),
            landing.renumbered,
        )
        assertEquals(
            "unplaced: what the repository could not place, and the pair whose row the cache does " +
                "not hold. A message that never came home is neither: its id did not change",
            setOf(EmailKey(null, "imap:acc:INBOX:9"), EmailKey(null, "imap:acc:INBOX:8")),
            landing.unplaced,
        )
    }

    private companion object {
        const val ACCOUNT = "acc"
        const val LEFT = "imap:acc:INBOX:7"
        const val HOME = "imap:acc:INBOX:200"
        const val HOME_AGAIN = "imap:acc:INBOX:300"
    }
}
