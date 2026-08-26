package app.sterna.ui.compose

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An addressing entry that carries a NAME and NO address must never become a recipient of a new
 * message.
 *
 * The IMAP `ENVELOPE` parser carries RFC 5322 group syntax with no member — `Cc:
 * undisclosed-recipients:;` — as one entry `EmailAddress(name = "undisclosed-recipients", email =
 * "")`, so a reopened draft shows the header the server holds instead of a blank field. That entry
 * is a header, not a mailbox: replying or replying-all to such a mail must address the real
 * mailboxes only, and a `Reply-To` made of nothing but that entry is no `Reply-To` at all — the
 * `From` takes over, rather than opening a reply with no recipient.
 *
 * Every expectation below is the WHOLE field, as a literal: an entry leaking in would have to leak
 * in silently for these to stay green. Each case is doubled by the same original WITHOUT the entry,
 * asserting the identical result — that pair is what proves the entry is what got dropped, and not
 * that the case never reached the guard.
 */
class GroupEntryIsNeverARecipientTest {

    /** What the parser produces for a group naming nobody. */
    private val token = EmailAddress(name = "undisclosed-recipients", email = "")

    private val mine = setOf("me@example.com")

    /** The same shape the other reply tests use, so the expectations read the same. */
    private val original = Email(
        id = "m1",
        subject = "Project Phoenix",
        from = listOf(EmailAddress(name = "Alice", email = "alice@example.com")),
        to = listOf(
            EmailAddress(email = "me@example.com"),
            EmailAddress(name = "Bob", email = "bob@example.com"),
        ),
        cc = listOf(EmailAddress(email = "carol@example.com")),
    )

    // 1 — the entry sits in the Cc beside a real address.

    @Test fun replyAllDropsTheGroupEntryFromTheCc() {
        val o = original.copy(cc = listOf(EmailAddress(email = "carol@example.com"), token))
        assertEquals("alice@example.com, bob@example.com, carol@example.com", replyAllRecipients(o, mine))
    }

    @Test fun replyAllWithoutTheGroupEntryInTheCcIsTheSameField() {
        val o = original.copy(cc = listOf(EmailAddress(email = "carol@example.com")))
        assertEquals("alice@example.com, bob@example.com, carol@example.com", replyAllRecipients(o, mine))
    }

    // 2 — the entry is the whole To, and there is no Cc: only the sender is left to answer.

    @Test fun replyAllToAMailWhoseToIsOnlyTheGroupEntryAnswersTheSenderAlone() {
        val o = original.copy(to = listOf(token), cc = emptyList())
        assertEquals("alice@example.com", replyAllRecipients(o, mine))
    }

    @Test fun replyAllToAMailWithNoToAndNoCcAnswersTheSenderAlone() {
        val o = original.copy(to = emptyList(), cc = emptyList())
        assertEquals("alice@example.com", replyAllRecipients(o, mine))
    }

    // 3 — the entry is the whole Reply-To. Counting it as a Reply-To would displace the From and
    // leave the reply with NO recipient at all, which is the harm this pair pins.

    @Test fun replyToMadeOnlyOfTheGroupEntryFallsBackToTheSender() {
        val o = original.copy(replyTo = listOf(token))
        assertEquals(listOf("alice@example.com"), replyRecipients(o))
        assertEquals("alice@example.com", replyRecipient(o))
    }

    @Test fun replyWithNoReplyToAtAllAddressesTheSenderTheSameWay() {
        val o = original.copy(replyTo = emptyList())
        assertEquals(listOf("alice@example.com"), replyRecipients(o))
        assertEquals("alice@example.com", replyRecipient(o))
    }

    // 4 — the same Reply-To, on a reply-all with nothing else to carry.

    @Test fun replyAllWithAReplyToOfOnlyTheGroupEntryAnswersTheSenderAlone() {
        val o = original.copy(replyTo = listOf(token), to = listOf(EmailAddress(email = "me@example.com")), cc = emptyList())
        assertEquals("alice@example.com", replyAllRecipients(o, mine))
    }

    @Test fun replyAllWithNoReplyToAndNothingButSelfAnswersTheSenderAlone() {
        val o = original.copy(replyTo = emptyList(), to = listOf(EmailAddress(email = "me@example.com")), cc = emptyList())
        assertEquals("alice@example.com", replyAllRecipients(o, mine))
    }

    // 5 — the entry must not be mistaken for "which of my addresses got this", nor hide the real
    // answer behind a null the caller would read as "unknown".

    @Test fun receivingAddressIgnoresAGroupEntryInTheCc() {
        val o = original.copy(cc = listOf(token))
        assertEquals("me@example.com", receivingAddress(o, mine))
    }

    @Test fun receivingAddressWithoutTheGroupEntryIsTheSameAddress() {
        val o = original.copy(cc = emptyList())
        assertEquals("me@example.com", receivingAddress(o, mine))
    }

    @Test fun receivingAddressIsUnknownWhenTheGroupEntryIsAllThereIs() {
        // Nothing of mine is named: the entry must not stand in for an address, and the answer is
        // the same "unknown" a Bcc delivery gives — not the token, not an empty string.
        val o = original.copy(to = listOf(token), cc = emptyList())
        assertEquals(null, receivingAddress(o, mine))
    }
}
