package app.sterna.ui.inbox

import app.sterna.core.data.account.MoveEvent
import app.sterna.core.data.account.MoveHistory
import app.sterna.core.data.mail.CrossAccountMove
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The pickers' Suggestions section, executed: which folders it proposes and in which order, when it
 * is computed and held, what it shows once something is typed, and which moves leave an event.
 */
class MoveSuggestionsTest {

    private val now = 1_000L * DAY

    private fun folder(id: String, role: String? = null) = Mailbox(id = id, name = id, role = role)

    private val inbox = folder("INBOX", "inbox")
    private val receipts = folder("Receipts")
    private val travel = folder("Travel")
    private val work = folder("Work")
    private val family = folder("Family")
    private val targets = listOf(inbox, receipts, travel, work, family)

    private fun event(id: String, daysAgo: Double, vararg senders: String) =
        MoveEvent(id, now - (daysAgo * DAY).toLong(), senders.toSet())

    // ── the choice ─────────────────────────────────────────────────────────────────────────────

    @Test fun `no event, no suggestion`() {
        assertEquals(emptyList<String>(), moveSuggestions(emptyList(), targets, "a@example.org", now))
    }

    @Test fun `a folder absent from the offered list is never proposed`() {
        val events = listOf(event("Hidden", 0.0), event("Hidden", 0.0), event("Hidden", 0.0), event("Travel", 30.0))
        assertEquals(listOf("Travel"), moveSuggestions(events, targets, null, now))
    }

    @Test fun `the folder on screen is never proposed, moveTargets having left it out`() {
        val mailboxes = listOf(inbox, receipts, travel)
        val offered = moveTargets(mailboxes, currentMailboxId = "Receipts")
        val events = listOf(event("Receipts", 0.0, "a@example.org"), event("Receipts", 0.0, "a@example.org"), event("Travel", 5.0))
        assertEquals(listOf("Travel"), moveSuggestions(events, offered, "a@example.org", now))
    }

    @Test fun `at most three rows`() {
        val events = listOf(event("Receipts", 0.0), event("Travel", 1.0), event("Work", 2.0), event("Family", 3.0), event("INBOX", 4.0))
        assertEquals(listOf("Receipts", "Travel", "Work"), moveSuggestions(events, targets, null, now))
    }

    @Test fun `at an equal count the more recent folder comes first`() {
        val events = listOf(event("Receipts", 20.0), event("Travel", 2.0))
        assertEquals(listOf("Travel", "Receipts"), moveSuggestions(events, targets, null, now))
    }

    @Test fun `at an equal date the more frequent folder comes first`() {
        val events = listOf(event("Receipts", 3.0), event("Travel", 3.0), event("Travel", 3.0))
        assertEquals(listOf("Travel", "Receipts"), moveSuggestions(events, targets, null, now))
    }

    @Test fun `a perfect tie keeps the order of the list below`() {
        val events = listOf(event("Work", 3.0), event("Travel", 3.0), event("Receipts", 3.0))
        assertEquals(listOf("Receipts", "Travel", "Work"), moveSuggestions(events, targets, null, now))
    }

    @Test fun `the score weighs a week-old move half of today's`() {
        // Two moves a week old weigh exactly one of today: the tie then falls to the list's order.
        val events = listOf(event("Work", 7.0), event("Work", 7.0), event("Travel", 0.0))
        assertEquals(listOf("Travel", "Work"), moveSuggestions(events, targets, null, now))
        val older = listOf(event("Work", 7.1), event("Work", 7.1), event("Travel", 0.0))
        assertEquals(listOf("Travel", "Work"), moveSuggestions(older, targets, null, now))
        val newer = listOf(event("Work", 6.9), event("Work", 6.9), event("Travel", 0.0))
        assertEquals(listOf("Work", "Travel"), moveSuggestions(newer, targets, null, now))
    }

    @Test fun `the sender's folder leads once two moves of that sender went there`() {
        val events = listOf(
            event("Receipts", 60.0, "shop@example.org"),
            event("Receipts", 50.0, "shop@example.org"),
            event("Travel", 0.0), event("Travel", 0.0), event("Travel", 0.0),
        )
        assertEquals(listOf("Receipts", "Travel"), moveSuggestions(events, targets, "Shop@Example.org", now))
    }

    @Test fun `a sender seen once has no line of its own`() {
        val events = listOf(
            event("Receipts", 60.0, "shop@example.org"),
            event("Travel", 0.0), event("Travel", 0.0),
            event("Work", 1.0),
        )
        assertEquals(listOf("Travel", "Work", "Receipts"), moveSuggestions(events, targets, "shop@example.org", now))
    }

    @Test fun `the sender's folder is the most frequent, then the most recent`() {
        val events = listOf(
            event("Work", 1.0, "a@example.org"), event("Work", 1.0, "a@example.org"),
            event("Family", 90.0, "a@example.org"), event("Family", 90.0, "a@example.org"), event("Family", 90.0, "a@example.org"),
        )
        assertEquals("Family", moveSuggestions(events, targets, "a@example.org", now).first())
        val tied = listOf(
            event("Work", 40.0, "a@example.org"), event("Work", 1.0, "a@example.org"),
            event("Family", 2.0, "a@example.org"), event("Family", 2.0, "a@example.org"),
        )
        assertEquals("Work", moveSuggestions(tied, targets, "a@example.org", now).first())
    }

    @Test fun `the sender's folder is not listed twice`() {
        val events = listOf(event("Receipts", 0.0, "a@example.org"), event("Receipts", 0.0, "a@example.org"))
        assertEquals(listOf("Receipts"), moveSuggestions(events, targets, "a@example.org", now))
    }

    @Test fun `a selection with two senders has no sender`() {
        assertNull(singleSender(listOf(mail("1", "a@example.org"), mail("2", "b@example.org")), expected = 2))
        assertEquals("a@example.org", singleSender(listOf(mail("1", "A@example.org"), mail("2", "a@example.org")), expected = 2))
        assertNull("a message not read may have another sender", singleSender(listOf(mail("1", "a@example.org")), expected = 2))
        assertNull(singleSender(emptyList(), expected = 0))
    }

    // ── the section on screen ──────────────────────────────────────────────────────────────────

    private val history = MoveHistory("A", listOf(event("Travel", 0.0)))

    @Test fun `a memory read for another account is refused whole`() {
        assertNull(freezeSuggestions(null, listed = "B", history = history, targets = targets, sender = null, now = now))
        assertNull(freezeSuggestions(null, listed = "A", history = MoveHistory.NONE, targets = targets, sender = null, now = now))
    }

    @Test fun `the section waits for the offered list`() {
        assertNull(freezeSuggestions(null, "A", history, emptyList(), null, now))
    }

    @Test fun `once computed the section does not move while the picker stays open`() {
        val first = freezeSuggestions(null, "A", history, targets, null, now)
        assertEquals(FrozenSuggestions("A", listOf("Travel")), first)
        val later = MoveHistory("A", history.events + event("Work", 0.0) + event("Work", 0.0))
        assertSame(first, freezeSuggestions(first, "A", later, targets, null, now))
    }

    @Test fun `another account chosen on the row gets its own suggestions`() {
        val first = freezeSuggestions(null, "A", history, targets, null, now)
        assertNull("B's memory is not read yet", freezeSuggestions(first, "B", history, targets, null, now))
        val b = MoveHistory("B", listOf(event("Work", 0.0)))
        assertEquals(FrozenSuggestions("B", listOf("Work")), freezeSuggestions(first, "B", b, targets, null, now))
    }

    private val rows = targets.map { FolderPickerRow(it, it.name, if (it == work) "Clients" else null) }

    @Test fun `the section goes as soon as something is typed`() {
        val frozen = FrozenSuggestions("A", listOf("Work", "Travel"))
        assertEquals(listOf("Work", "Travel"), suggestedRows(frozen, "A", rows, "").map { it.folder.id })
        assertEquals(emptyList<FolderPickerRow>(), suggestedRows(frozen, "A", rows, "W"))
    }

    @Test fun `a suggested row is painted like the row below, path included`() {
        val frozen = FrozenSuggestions("A", listOf("Work"))
        assertEquals(listOf(rows[3]), suggestedRows(frozen, "A", rows, ""))
        assertEquals("held for another account", emptyList<FolderPickerRow>(), suggestedRows(frozen, "B", rows, ""))
    }

    // ── what leaves an event ───────────────────────────────────────────────────────────────────

    private fun mail(id: String, from: String, account: String = "A") =
        Email(id = id, accountId = account, from = listOf(EmailAddress(email = from)))

    @Test fun `the reader's move leaves an event only once its write landed`() {
        val email = mail("1", "a@example.org")
        assertEquals(listOf(email), landedByTheSwipe(Result.success("Receipts"), email))
        assertEquals("refused, offline included", emptyList<Email>(), landedByTheSwipe(Result.failure(RuntimeException("offline")), email))
        assertEquals("moved nothing", emptyList<Email>(), landedByTheSwipe(Result.success(null), email))
        assertNull(pickedMoveEvent(landedByTheSwipe(Result.failure(RuntimeException()), email), "Receipts", now))
    }

    @Test fun `a move between accounts leaves an event only for what really moved`() {
        val moved = mail("1", "a@example.org")
        val copied = mail("2", "b@example.org")
        val failed = mail("3", "c@example.org")
        val emails = listOf(moved, copied, failed).associateBy { EmailKey(it.accountId, it.id) }
        val outcomes = mapOf(
            EmailKey("A", "1") to CrossAccountMove.Moved,
            EmailKey("A", "2") to CrossAccountMove.CopiedNotRemoved("bin refused"),
            EmailKey("A", "3") to CrossAccountMove.Failed("offline"),
        )
        assertEquals(listOf(moved), landedAcrossAccounts(outcomes, emails))
        val onlyCopied = mapOf(EmailKey("A", "2") to CrossAccountMove.CopiedNotRemoved("bin refused"))
        assertNull(pickedMoveEvent(landedAcrossAccounts(onlyCopied, emails), "Receipts", now))
    }

    @Test fun `one gesture is one event carrying every sender it moved`() {
        val landed = (1..50).map { mail("$it", if (it % 2 == 0) "Even@example.org" else "odd@example.org") }
        val event = pickedMoveEvent(landed, "Receipts", now)
        assertNotNull(event)
        assertEquals(MoveEvent("Receipts", now, setOf("odd@example.org", "even@example.org")), event)
    }

    @Test fun `the selection bar's Inbox button remembers nothing`() {
        assertNull(pickedInto(fromPicker = false, accountId = "A", mailboxId = "INBOX"))
        assertEquals(PickedMove("A", "INBOX"), pickedInto(fromPicker = true, accountId = "A", mailboxId = "INBOX"))
        assertNull(pickedInto(fromPicker = true, accountId = null, mailboxId = "INBOX"))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
