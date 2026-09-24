package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fileSelection], executed with the rule the selection bar really freezes
 * ([ListScope.movesWithConversation]). Every expected answer is written out by hand.
 */
class SelectionFilingTest {

    private fun email(id: String, mailbox: String, account: String = "accA") =
        Email(id = id, accountId = account, mailboxId = mailbox, subject = "s")

    private val received = email("m1", "INBOX")
    private val second = email("m2", "INBOX")
    private val reply = email("m3", "Sent")

    /** Viewing the inbox of `accA`, whose Sent is `Sent`: the chip counts the reply there too. */
    private val inboxRule: (Email) -> Boolean =
        ListScope(viewedMailboxIds = listOf("INBOX"), sentMailboxes = listOf("accA" to "Sent"))::movesWithConversation

    private fun keys(rows: List<Email>): List<EmailKey> = rows.map { it.emailKey() }

    /** Everything a folded row brought in: the whole conversation, carried. */
    private val folded: Set<EmailKey> = setOf(received, second, reply).mapTo(mutableSetOf()) { it.emailKey() }

    @Test fun `no rule takes every row`() {
        val filed = fileSelection(listOf(received, reply, second), moves = null, carried = folded) { it }
        assertEquals(listOf(EmailKey("accA", "m1"), EmailKey("accA", "m3"), EmailKey("accA", "m2")), keys(filed.leaving))
        assertEquals(emptyList<EmailKey>(), keys(filed.spared))
    }

    @Test fun `the reply filed in Sent is spared and the rest of the conversation leaves`() {
        val filed = fileSelection(listOf(received, reply, second), inboxRule, folded) { it }
        assertEquals(listOf(EmailKey("accA", "m1"), EmailKey("accA", "m2")), keys(filed.leaving))
        assertEquals(listOf(EmailKey("accA", "m3")), keys(filed.spared))
    }

    @Test fun `a selection the rule spares entirely leaves nothing`() {
        val filed = fileSelection(listOf(reply), inboxRule, folded) { it }
        assertEquals(emptyList<EmailKey>(), keys(filed.leaving))
        assertEquals(listOf(EmailKey("accA", "m3")), keys(filed.spared))
    }

    @Test fun `the rule is asked of the message each row stands for`() {
        val rows = listOf(SelectionTarget(received, folderTrusted = true), SelectionTarget(reply, folderTrusted = true))
        val filed = fileSelection(rows, inboxRule, folded) { it.email }
        assertEquals(listOf(received), filed.leaving.map { it.email })
        assertEquals(listOf(reply), filed.spared.map { it.email })
    }

    @Test fun `the reply ticked by hand leaves, its rule is for what a folded row carried`() {
        val filed = fileSelection(listOf(reply), inboxRule, carried = emptySet()) { it }
        assertEquals(listOf(EmailKey("accA", "m3")), keys(filed.leaving))
        assertEquals(emptyList<EmailKey>(), keys(filed.spared))
    }

    @Test fun `in one selection the carried reply stays and the reply ticked by hand leaves`() {
        val other = email("m4", "Sent")
        val carried = setOf(received.emailKey(), reply.emailKey())
        val filed = fileSelection(listOf(received, reply, other), inboxRule, carried) { it }
        assertEquals(listOf(EmailKey("accA", "m1"), EmailKey("accA", "m4")), keys(filed.leaving))
        assertEquals(listOf(EmailKey("accA", "m3")), keys(filed.spared))
    }

    @Test fun `no rule takes every row whatever brought it in`() {
        val filed = fileSelection(listOf(reply, received), moves = null, carried = setOf(reply.emailKey())) { it }
        assertEquals(listOf(EmailKey("accA", "m3"), EmailKey("accA", "m1")), keys(filed.leaving))
        assertEquals(emptyList<EmailKey>(), keys(filed.spared))
    }

    @Test fun `ticking a folded row carries its whole conversation, representative included`() {
        val ticks = SelectionTicks(emptySet()).toggledConversation(received.emailKey(), folded)
        assertEquals(folded, ticks.keys)
        assertEquals(folded, ticks.carried)
    }

    @Test fun `the reply ticked alone in an unfolded conversation is ticked by hand`() {
        val ticks = SelectionTicks(emptySet()).toggledByHand(reply.emailKey())
        assertEquals(setOf(EmailKey("accA", "m3")), ticks.keys)
        assertEquals(emptySet<EmailKey>(), ticks.carried)
    }

    @Test fun `unticking a carried key takes it out of both halves, and ticking it again is by hand`() {
        val folding = SelectionTicks(emptySet()).toggledConversation(received.emailKey(), folded)
        val off = folding.toggledByHand(reply.emailKey())
        assertEquals(setOf(EmailKey("accA", "m1"), EmailKey("accA", "m2")), off.keys)
        assertEquals(setOf(EmailKey("accA", "m1"), EmailKey("accA", "m2")), off.carried)
        val back = off.toggledByHand(reply.emailKey())
        assertEquals(folded, back.keys)
        assertEquals(setOf(EmailKey("accA", "m1"), EmailKey("accA", "m2")), back.carried)
    }

    @Test fun `a conversation ticked over a key the hand ticked leaves that key the hand's`() {
        val ticks = SelectionTicks(emptySet()).toggledByHand(reply.emailKey()).carrying(folded)
        assertEquals(folded, ticks.keys)
        assertEquals(setOf(EmailKey("accA", "m1"), EmailKey("accA", "m2")), ticks.carried)
    }

    @Test fun `unticking a folded row takes its conversation out of both halves`() {
        val ticks = SelectionTicks(setOf(EmailKey("accB", "x")))
            .toggledConversation(received.emailKey(), folded)
            .toggledConversation(received.emailKey(), folded)
        assertEquals(setOf(EmailKey("accB", "x")), ticks.keys)
        assertEquals(emptySet<EmailKey>(), ticks.carried)
    }

    @Test fun `keys handed back after a refusal keep the origin they had at the tap`() {
        val ticks = handedBack(setOf(reply.emailKey(), second.emailKey()), carried = setOf(reply.emailKey(), received.emailKey()))
        assertEquals(setOf(EmailKey("accA", "m3"), EmailKey("accA", "m2")), ticks.keys)
        assertEquals(setOf(EmailKey("accA", "m3")), ticks.carried)
    }

    @Test fun `archiving a folded row leaves its reply, archiving the reply ticked alone takes it`() {
        val byRow = SelectionTicks(emptySet()).toggledConversation(received.emailKey(), folded)
        val rowFiled = fileSelection(listOf(received, second, reply), inboxRule, byRow.carried) { it }
        assertEquals(listOf(EmailKey("accA", "m3")), keys(rowFiled.spared))
        val byHand = SelectionTicks(emptySet()).toggledByHand(reply.emailKey())
        val handFiled = fileSelection(listOf(reply), inboxRule, byHand.carried) { it }
        assertEquals(listOf(EmailKey("accA", "m3")), keys(handFiled.leaving))
    }
}
