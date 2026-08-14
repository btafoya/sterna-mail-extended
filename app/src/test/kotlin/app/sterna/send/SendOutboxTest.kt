package app.sterna.send

import app.sterna.core.data.mail.MailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The undo-send contract the "Annuler" affordance relies on: undoing must both run the drop action
 * (so nothing stays parked in the Outbox) and hand the exact draft back via [SendOutbox.restored]
 * (so compose can reopen it intact). Unconfined runs the undo action's launch synchronously.
 */
class SendOutboxTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val outbox = SendOutbox(scope)

    @After fun tearDown() = job.cancel()

    private fun draft(subject: String = "hi") = SendOutbox.ComposeDraft(
        to = "a@b.c", cc = "", bcc = "", subject = subject, body = "body",
        fromAccountId = "acc", fromIdentityEmail = "me@b.c",
        attachments = emptyList(), inReplyTo = emptyList(), references = emptyList(),
        // This message was edited from a saved draft, and it carries the numbering that draft's id
        // was read under (#99). Stated rather than defaulted: the field has no default precisely so
        // that a construction site cannot leave the freeze behind without the compiler saying so.
        draftEmailId = "d1", draftUidValidity = 42L,
        draftBodyIsLossy = false,
    )

    @Test fun `hold surfaces a pending affordance and no restored draft yet`() {
        outbox.hold("Message sent", draft()) {}
        assertTrue(outbox.pending.value != null)
        assertNull(outbox.restored.value)
    }

    @Test fun `undo clears pending, runs the drop action, and hands the draft back`() {
        val held = draft("undo me")
        var dropped = false
        outbox.hold("Waiting to send when connected", held) { dropped = true }

        outbox.undo()

        assertNull("the affordance is gone once undone", outbox.pending.value)
        assertTrue("the queued row's drop action runs", dropped)
        assertSame("the exact draft is handed back to reopen compose", held, outbox.restored.value)
    }

    @Test fun `consumeRestored clears the handed-back draft`() {
        outbox.hold("Message sent", draft()) {}
        outbox.undo()
        assertTrue(outbox.restored.value != null)

        outbox.consumeRestored()

        assertNull(outbox.restored.value)
    }

    @Test fun `undo with nothing held is a no-op`() {
        outbox.undo()
        assertNull(outbox.pending.value)
        assertNull(outbox.restored.value)
    }

    @Test fun `reopen stages a draft directly for editing a queued item`() {
        val d = draft("edit queued")
        outbox.reopen(d)
        assertSame(d, outbox.restored.value)
    }

    // ---- the fidelity verdict of an edited draft travels WITH its id ---------------------------

    /**
     * The undo round trip must hand the verdict back exactly as it went in, both senses: a lossy
     * send undone and re-sent must still refuse the destroy, and a clean one must still buy it.
     * (Never seen red — [SendOutbox] holds the draft whole — it pins the contract against a future
     * hold/restore that rebuilds the draft field by field and forgets this one.)
     */
    @Test fun `undoing a send brings the fidelity verdict back unchanged`() {
        for (lossy in listOf(true, false)) {
            val held = draft("verdict").copy(draftEmailId = "d1", draftBodyIsLossy = lossy)
            outbox.hold("Sending…", held) {}
            outbox.undo()
            assertEquals(lossy, outbox.restored.value!!.draftBodyIsLossy)
            outbox.consumeRestored()
        }
    }

    /**
     * ⭐ A message reopened from a PERSISTED outbox row reopens as lossy: the row has no column
     * for the verdict, so the verdict did not survive — and an unknown must forbid the destroy,
     * never authorise it. The id itself still travels (re-sending still replaces the draft when
     * a later save can prove itself); it is only the destroy that the unknown refuses.
     */
    @Test fun `a message reopened from a persisted outbox row comes back with an unknown, lossy, verdict`() {
        val reopened = composeDraftOf(
            MailRepository.OutboxDraft(
                to = "bob@example.org", cc = "", bcc = "",
                subject = "Six o'clock", body = "see you there",
                fromAccountId = "accA", fromEmail = "alex@masto.top",
                attachments = emptyList(), inReplyTo = emptyList(), references = emptyList(),
                pgpMode = null, draftEmailId = "d1", draftUidValidity = null,
                outboxId = 42, requestReceipt = false,
            ),
        )
        assertTrue(
            "no persisted row carries the verdict, so a reopened one is unknown — and unknown is lossy",
            reopened.draftBodyIsLossy,
        )
        assertEquals("the id still travels; only the destroy is refused", "d1", reopened.draftEmailId)
    }
}
