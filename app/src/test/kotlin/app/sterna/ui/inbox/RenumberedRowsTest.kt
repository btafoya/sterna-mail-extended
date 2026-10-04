package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an Undo's renumbering does to a frozen list of results and to the ticks, executed:
 * [renumberedRows] and [SelectionTicks.renumbered].
 */
class RenumberedRowsTest {

    private fun email(id: String, subject: String = "s") =
        Email(id = id, accountId = "acc", mailboxId = "INBOX", subject = subject)

    private fun key(id: String) = EmailKey("acc", id)

    private val before = email("a")
    private val stale = email(LEFT, subject = "drawn at the tap")
    private val after = email("b")
    private val home = email(HOME, subject = "from the cache")

    @Test fun `the restored row is replaced at its place by the one the cache holds`() {
        val rows = renumberedRows(listOf(before, stale, after), mapOf(key(LEFT) to home))

        assertEquals(
            "nothing refreshes a snapshot: left as drawn, the row keeps the id its message has " +
                "left, and that id is what the next tick and the next gesture are made of",
            listOf(before, home, after),
            rows,
        )
    }

    @Test fun `a row already drawn under the new key is not drawn twice`() {
        val rows = renumberedRows(listOf(before, stale, home, after), mapOf(key(LEFT) to email(HOME, subject = "again")))

        assertEquals(
            "a duplicate key in a lazy list throws (#92): the stale row leaves, the drawn one stays",
            listOf(before, home, after),
            rows,
        )
        assertEquals(
            "no key twice",
            rows?.map { it.emailKey() }?.distinct(),
            rows?.map { it.emailKey() },
        )
    }

    @Test fun `two rows landing on one key leave one row`() {
        val other = email("imap:acc:INBOX:8")

        val rows = renumberedRows(listOf(stale, other), mapOf(key(LEFT) to home, other.emailKey() to home))

        assertEquals(listOf(home), rows)
    }

    @Test fun `a list that draws none of the renumbered rows is left alone`() {
        assertEquals(null, renumberedRows(listOf(before, after), mapOf(key(LEFT) to home)))
        assertEquals(null, renumberedRows(null, mapOf(key(LEFT) to home)))
        assertEquals(null, renumberedRows(listOf(before, stale), emptyMap()))
    }

    @Test fun `a tick on a renumbered row follows its message, by hand or carried as it was`() {
        val ticks = SelectionTicks(keys = setOf(key("a"), key(LEFT), key("imap:acc:INBOX:8")), carried = setOf(key(LEFT)))

        val next = ticks.renumbered(mapOf(key(LEFT) to key(HOME)))

        assertEquals(
            "left under the old key the tick is on a row no list draws any more: it shows in the " +
                "count, on no row, and travels with the next gesture",
            SelectionTicks(keys = setOf(key("a"), key(HOME), key("imap:acc:INBOX:8")), carried = setOf(key(HOME))),
            next,
        )
    }

    @Test fun `ticks an Undo did not renumber are the same ticks`() {
        val ticks = SelectionTicks(keys = setOf(key("a"), key("b")), carried = setOf(key("b")))

        assertEquals(ticks, ticks.renumbered(mapOf(key(LEFT) to key(HOME))))
        assertEquals(SelectionTicks(emptySet()), SelectionTicks(emptySet()).renumbered(mapOf(key(LEFT) to key(HOME))))
    }

    private companion object {
        const val LEFT = "imap:acc:INBOX:7"
        const val HOME = "imap:acc:INBOX:200"
    }
}
