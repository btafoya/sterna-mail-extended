package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SelectionSources.renumber] executed over snapshots held in memory, each built as the search
 * bar's is in `InboxViewModel`: a list of rows, and a [RemovedRows] stash for what an action set
 * aside. `RenumberedRowsTest` runs the replacement alone; this runs it where the view model
 * reaches it, through the sources, and with the stash a gesture fills before its wait.
 */
class SelectionSourcesRenumberTest {

    private class Snapshot(var shown: List<Email>?) : ShownResults {
        private val removed = RemovedRows()
        override val rows: List<Email>? get() = shown
        override fun drop(keys: Set<EmailKey>) {
            removed.drop(rows, keys)?.let { shown = it }
        }
        override fun restore(keys: Collection<EmailKey>) {
            removed.restore(rows, keys)?.let { shown = it }
        }
        override fun patch(keys: Set<EmailKey>, transform: (Email) -> Email) {
            patchedRows(rows, keys, transform)?.let { shown = it }
        }
        override fun renumber(landed: Map<EmailKey, Email>) {
            removed.renumber(landed)
            renumberedRows(rows, landed)?.let { shown = it }
        }
    }

    private fun email(id: String, subject: String = "s") =
        Email(id = id, accountId = "acc", mailboxId = "INBOX", subject = subject)

    private val before = email("a")
    private val stale = email(LEFT, subject = "drawn at the tap")
    private val after = email("b")
    private val home = email(HOME, subject = "from the cache")
    private val landed = mapOf(stale.emailKey() to home)

    @Test fun `the list's snapshot is renumbered through the sources`() {
        val list = Snapshot(listOf(before, stale, after))

        SelectionSources(list).renumber(landed)

        assertEquals(
            "the search bar's results are the snapshot the reported symptom was ticked on",
            listOf(before, home, after),
            list.shown,
        )
    }

    @Test fun `the advanced search's snapshot is renumbered with it while it is attached`() {
        val list = Snapshot(listOf(stale))
        val criteria = Snapshot(listOf(after, stale))
        val sources = SelectionSources(list)
        sources.attach(criteria)

        sources.renumber(landed)

        assertEquals(listOf(home), list.shown)
        assertEquals(listOf(after, home), criteria.shown)
    }

    @Test fun `a row set aside before the restore returned comes back under the id its message answers to`() {
        val list = Snapshot(listOf(before, stale, after))
        val sources = SelectionSources(list)
        // A swipe cuts the row out of the snapshot at the finger, ahead of its wait.
        sources.drop(setOf(stale.emailKey()))

        sources.renumber(landed)
        sources.restore(listOf(home.emailKey()))

        assertEquals(
            "the replacement only sees rows that are drawn: the stash has to follow too, or the " +
                "gesture's put-back draws the stale row for good",
            listOf(before, home, after),
            list.shown,
        )
    }

    @Test fun `and the key it was set aside under puts nothing back`() {
        val list = Snapshot(listOf(before, stale, after))
        val sources = SelectionSources(list)
        sources.drop(setOf(stale.emailKey()))

        sources.renumber(landed)
        sources.restore(listOf(stale.emailKey()))

        assertEquals(listOf(before, after), list.shown)
    }

    @Test fun `a stashed row whose new key is already drawn is not drawn a second time`() {
        val list = Snapshot(listOf(before, stale, after, home))
        val sources = SelectionSources(list)
        sources.drop(setOf(stale.emailKey()))

        sources.renumber(mapOf(stale.emailKey() to email(HOME, subject = "again")))
        sources.restore(listOf(home.emailKey()))

        assertEquals("a duplicate key in a lazy list throws (#92)", listOf(before, after, home), list.shown)
    }

    @Test fun `a stashed row no Undo renumbered comes back as it was stashed`() {
        val list = Snapshot(listOf(before, stale, after))
        val sources = SelectionSources(list)
        sources.drop(setOf(before.emailKey()))

        sources.renumber(landed)
        sources.restore(listOf(before.emailKey()))

        assertEquals(listOf(before, home, after), list.shown)
    }

    private companion object {
        const val LEFT = "imap:acc:INBOX:7"
        const val HOME = "imap:acc:INBOX:200"
    }
}
