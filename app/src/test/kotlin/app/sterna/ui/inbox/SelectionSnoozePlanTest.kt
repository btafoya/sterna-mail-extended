package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [planSelectionSnooze] — who can be snoozed at all, executed rather than re-derived.
 *
 * A snooze is not a server write: it writes a row in the local `snoozed` table and arms a worker.
 * `SnoozeWorker` then resolves the message back through the local `emails` table — and a row that
 * only ever existed on screen (a search hit with no cached row, `folderTrusted = false`) is not in
 * it. The reminder is written, the worker fires, nothing is found, nothing rings, and the `snoozed`
 * row is deleted on the way, so the message also vanishes from "Snoozed" (bench, 2026-08-08).
 *
 * Hence: such a row is refused and left completely untouched, for the caller to count as an attempt
 * AND as a failure, so the screen says so. The expectations below are written out by hand — a test
 * that re-applies the rule to decide what it expects would stay green with the rule inverted.
 */
class SelectionSnoozePlanTest {

    /**
     * A fully filled row — every optional field of [Email] the partition could be tempted to read
     * carries a value. A row this rich is what a search hit actually looks like, and a bare
     * `Email(id, accountId, mailboxId, subject)` is what lets a predicate on ANY other field pass a
     * whole suite unseen: it is false everywhere, so nothing moves. See [sparse] for the other half
     * of that guard.
     */
    private fun target(id: String, trusted: Boolean, account: String? = "accA", mailbox: String? = "mbA") =
        SelectionTarget(
            Email(
                id = id,
                accountId = account,
                blobId = "blob-$id",
                mailboxId = mailbox,
                mailboxIds = mapOf((mailbox ?: "mbA") to true),
                threadId = "t-$id",
                subject = "s",
                preview = "p",
                receivedAt = "2026-08-08T10:00:00Z",
                from = listOf(EmailAddress(name = "A", email = "a@example.test")),
                hasAttachment = true,
                keywords = mapOf("\$seen" to true),
            ),
            folderTrusted = trusted,
        )

    /**
     * The same row stripped to nothing but its ids. Paired with [target] in the mixed case below,
     * it closes the guard from both sides: a predicate that snoozes on "has content" lets the rich
     * REFUSED row through, and one that refuses on "has no content" holds the bare SNOOZED row
     * back. Only `folderTrusted` splits these six rows the way the assertions demand.
     */
    private fun sparse(id: String, trusted: Boolean) =
        SelectionTarget(Email(id = id), folderTrusted = trusted)

    /**
     * The reported case, interleaved: the cached rows are snoozed, the rows drawn by the search are
     * refused, each part in the order it was selected. Both parts are pinned by COMPOSITION — the
     * ids AND the trust flag — because two parts of the right sizes can still hold the wrong rows.
     *
     * `m2` is a FULLY filled untrusted row and `m3` a BARE trusted one, on purpose: the flag is
     * then the only property of these four rows that predicts the answer, so a partition widened
     * with any other field of [Email] lands one of them on the wrong side and reddens here.
     */
    @Test
    fun `a mixed selection snoozes the cached rows and refuses the rest`() {
        val plan = planSelectionSnooze(
            listOf(
                target("m1", trusted = true),
                target("m2", trusted = false),
                sparse("m3", trusted = true),
                target("m4", trusted = false, mailbox = ""),
            ),
        )
        assertEquals(listOf("m1", "m3"), plan.snooze.map { it.email.id })
        assertEquals(listOf(true, true), plan.snooze.map { it.folderTrusted })
        assertEquals(listOf("m2", "m4"), plan.refused.map { it.email.id })
        assertEquals(listOf(false, false), plan.refused.map { it.folderTrusted })
    }

    /** Nothing may be refused when every row has a local one: this is the everyday selection. */
    @Test
    fun `a fully cached selection is snoozed whole`() {
        val plan = planSelectionSnooze(
            listOf(target("m1", trusted = true), target("m2", trusted = true, account = "accB")),
        )
        assertEquals(listOf("m1", "m2"), plan.snooze.map { it.email.id })
        assertEquals(emptyList<String>(), plan.refused.map { it.email.id })
    }

    /** And the reported selection in full: nothing is written at all, everything comes back refused. */
    @Test
    fun `a selection with no cached row at all snoozes nothing`() {
        val plan = planSelectionSnooze(
            listOf(target("m1", trusted = false), target("m2", trusted = false, account = "accB")),
        )
        assertEquals(emptyList<String>(), plan.snooze.map { it.email.id })
        assertEquals(listOf("m1", "m2"), plan.refused.map { it.email.id })
    }

    @Test
    fun `an empty selection plans nothing on either side`() {
        val plan = planSelectionSnooze(emptyList())
        assertEquals(emptyList<SelectionTarget>(), plan.snooze)
        assertEquals(emptyList<SelectionTarget>(), plan.refused)
    }

    /**
     * The same id in both trust states — the two rows a merge of the cache and the search snapshot
     * can produce for one message. Played in BOTH orders: a decision that lets the first row seen
     * settle the message would give the right answer in one order by accident.
     */
    @Test
    fun `the same id trusted and untrusted lands in both parts, in either order`() {
        val trustedFirst = planSelectionSnooze(
            listOf(target("m1", trusted = true), target("m1", trusted = false)),
        )
        assertEquals(listOf("m1"), trustedFirst.snooze.map { it.email.id })
        assertEquals(listOf("m1"), trustedFirst.refused.map { it.email.id })
        assertEquals(listOf(true), trustedFirst.snooze.map { it.folderTrusted })
        assertEquals(listOf(false), trustedFirst.refused.map { it.folderTrusted })

        val untrustedFirst = planSelectionSnooze(
            listOf(target("m1", trusted = false), target("m1", trusted = true)),
        )
        assertEquals(listOf("m1"), untrustedFirst.snooze.map { it.email.id })
        assertEquals(listOf("m1"), untrustedFirst.refused.map { it.email.id })
        assertEquals(listOf(true), untrustedFirst.snooze.map { it.folderTrusted })
        assertEquals(listOf(false), untrustedFirst.refused.map { it.folderTrusted })
    }

    /** No row may be dropped between the two parts: what goes in comes out, once, somewhere. */
    @Test
    fun `every target ends up in exactly one part`() {
        val targets = listOf(
            target("m1", trusted = true),
            target("m2", trusted = false),
            target("m3", trusted = true),
            target("m4", trusted = false),
            target("m5", trusted = true),
        )
        val plan = planSelectionSnooze(targets)
        assertEquals(
            listOf("m1", "m3", "m5", "m2", "m4"),
            (plan.snooze + plan.refused).map { it.email.id },
        )
        assertEquals(targets.size, plan.snooze.size + plan.refused.size)
    }
}
