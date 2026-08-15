package app.sterna.core.data.mail

import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a send destroys, and what leaving the composer gives back — both EXECUTED (#95).
 *
 * ⛔ Nothing here restates the rule to pick what it expects: every expectation is a literal, and
 * the arguments differ from case to case, so inverting either shipped decision goes red. The source
 * lints in the app module can only see that `ComposeViewModel` still ASKS these two; the answers
 * are proved here.
 *
 * Two defects, one file:
 *  - a send of a REOPENED LOCAL draft named nothing at all, so the server draft the row stood in
 *    for came back into Drafts with its pre-edit text the moment the row was consumed;
 *  - closing the screen during a send re-armed the upload of the row the send was already
 *    consuming, filing a copy of the sent message in Drafts.
 */
class SendDraftTargetTest {

    // -- the pair a send carries -----------------------------------------------------------------

    @Test fun `a draft the server issued is destroyed under the composer's own numbering`() {
        // Both pairs are filled and they DIFFER: only reading the composer's can pass.
        assertEquals(
            SendDraftTarget("imap:acc:Drafts:41", 900L),
            sendDraftTarget(
                editingDraftId = "imap:acc:Drafts:41",
                editingDraftUidValidity = 900L,
                replacedServerDraftId = "imap:acc:Drafts:7",
                replacedServerDraftUidValidity = 12L,
            ),
        )
    }

    @Test fun `a JMAP draft carries no numbering, and none is invented for it`() {
        assertEquals(
            SendDraftTarget("Mdeadbeef", null),
            sendDraftTarget(
                editingDraftId = "Mdeadbeef",
                editingDraftUidValidity = null,
                replacedServerDraftId = "imap:acc:Drafts:7",
                replacedServerDraftUidValidity = 12L,
            ),
        )
    }

    @Test fun `a reopened local draft sends the SERVER draft it stands in for, numbering included`() {
        // ⛔ The defect this closes. The composer holds the ROW's id, for which sendDraftEmailId
        // answers null — correctly — and before this nobody named the server draft any more: the
        // row was consumed by the send, the mask lifted, and the pre-edit text reappeared in Drafts
        // beside the message that had just gone out.
        assertEquals(
            SendDraftTarget("imap:acc:Drafts:41", 900L),
            sendDraftTarget(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d1",
                editingDraftUidValidity = null,
                replacedServerDraftId = "imap:acc:Drafts:41",
                replacedServerDraftUidValidity = 900L,
            ),
        )
    }

    @Test fun `the id and the numbering are never taken from two different places`() {
        // ⛔ The pair is one answer. A row's id beside the composer's numbering is the worst of
        // both: on IMAP a UID EXPUNGE opposed to the wrong UIDVALIDITY hits ANOTHER message of
        // Drafts. So 12L must NOT come out here, though the composer holds it.
        assertEquals(
            SendDraftTarget("imap:acc:Drafts:41", 900L),
            sendDraftTarget(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d2",
                editingDraftUidValidity = 12L,
                replacedServerDraftId = "imap:acc:Drafts:41",
                replacedServerDraftUidValidity = 900L,
            ),
        )
    }

    @Test fun `a local draft written from scratch replaces nothing, and destroys nothing`() {
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d3",
                editingDraftUidValidity = 12L,
                replacedServerDraftId = null,
                replacedServerDraftUidValidity = null,
            ),
        )
    }

    @Test fun `no draft at all is no destroy, and the numbering goes with the id`() {
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = null,
                editingDraftUidValidity = 900L,
                replacedServerDraftId = null,
                replacedServerDraftUidValidity = null,
            ),
        )
    }

    @Test fun `a replaced id this phone minted is refused too, in belt`() {
        // The row's `replacesEmailId` is written by localDraftTarget and should never hold a local
        // id — this is the belt on top of that braces, and it must take the numbering with it.
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d4",
                editingDraftUidValidity = 12L,
                replacedServerDraftId = LOCAL_DRAFT_ID_PREFIX + "d9",
                replacedServerDraftUidValidity = 900L,
            ),
        )
    }

    // -- the fidelity verdict, applied to the PAIR -----------------------------------------------

    @Test fun `a body this composer cannot reproduce withholds the id AND its numbering`() {
        // The scheduled table has no column for the verdict, so the id itself is the carrier: no id
        // means "keep the original, live with the duplicate" (#63). ⛔ The numbering goes with it.
        // A number left beside a null id destroys nothing today — it is the STATEMENT that is the
        // defect: half a pair on a row that waits for hours is the shape the next reader copies.
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = "imap:acc:Drafts:41",
                editingDraftUidValidity = 900L,
                replacedServerDraftId = null,
                replacedServerDraftUidValidity = null,
            ).unlessBodyIsLossy(true),
        )
    }

    @Test fun `a clean verdict leaves the pair whole, both members`() {
        assertEquals(
            SendDraftTarget("imap:acc:Drafts:41", 900L),
            sendDraftTarget(
                editingDraftId = "imap:acc:Drafts:41",
                editingDraftUidValidity = 900L,
                replacedServerDraftId = null,
                replacedServerDraftUidValidity = null,
            ).unlessBodyIsLossy(false),
        )
    }

    @Test fun `the verdict also takes away a server draft named off the local row`() {
        // The pair the ROW carries is withheld exactly as the composer's own is: a draft this phone
        // never read whole keeps its server copy whichever way that copy is named.
        assertEquals(
            SendDraftTarget(null, null),
            sendDraftTarget(
                editingDraftId = LOCAL_DRAFT_ID_PREFIX + "d8",
                editingDraftUidValidity = null,
                replacedServerDraftId = "imap:acc:Drafts:41",
                replacedServerDraftUidValidity = 900L,
            ).unlessBodyIsLossy(true),
        )
    }

    // -- what closing the screen gives back ------------------------------------------------------

    @Test fun `a send in flight gives NOTHING back, neither the row nor the queue`() {
        // ⛔ The defect this closes. The send owns the message and consumes both itself; releasing
        // the local row from under it puts it back to PENDING, the upload worker takes it, and a
        // copy of the message that has just gone out is filed in the server's Drafts — which the
        // startup re-arm then keeps putting back.
        assertEquals(
            AbandonPlan(null, null),
            abandonPlan(
                sending = true,
                emptying = false,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d1",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `a composer with no queued row still gives its own draft back`() {
        // ⛔ The other half, and the reason the guard cannot simply be moved to the bottom: every
        // reopened local draft has a null outbox id, and a release written under the outbox `?:
        // return` would never run — the row would stay EDITING for ever, invisible to the upload
        // worker and to the startup re-arm alike.
        assertEquals(
            AbandonPlan(LOCAL_DRAFT_ID_PREFIX + "d5", null),
            abandonPlan(
                sending = false,
                emptying = false,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d5",
                outboxId = null,
            ),
        )
    }

    @Test fun `both are given back when both are held and nothing is in flight`() {
        assertEquals(
            AbandonPlan(LOCAL_DRAFT_ID_PREFIX + "d6", 42L),
            abandonPlan(
                sending = false,
                emptying = false,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d6",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `a queued row with no local draft behind it is given back on its own`() {
        assertEquals(
            AbandonPlan(null, 7L),
            abandonPlan(sending = false, emptying = false, localDraftId = null, outboxId = 7L),
        )
    }

    @Test fun `a send in flight keeps the local row even when there is no queued row`() {
        assertEquals(
            AbandonPlan(null, null),
            abandonPlan(sending = true, emptying = false, localDraftId = LOCAL_DRAFT_ID_PREFIX + "d7", outboxId = null),
        )
    }

    @Test fun `emptying the draft gives the phone's own row back to nobody`() {
        // ⛔ The close that accompanies a DELETION, and it must hand the row to no one. Given back,
        // it goes PENDING and its upload is re-armed at the row's own instant — for a draft saved
        // with no network, NOW — so the text the user has just emptied races the delete to the
        // server and is filed in Drafts while she reads that the deletion failed.
        //
        // ⛔ And it is decided HERE rather than by blanking the composer's field before the call,
        // which is where it lived: one field cannot carry both "which row I hold" and "give nothing
        // back this once". Blanked, a submit() that FAILED left the row EDITING with nobody left to
        // release it — never uploaded again, and not a word on screen.
        assertEquals(
            AbandonPlan(null, 42L),
            abandonPlan(
                sending = false,
                emptying = true,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d9",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `a send in flight beats an empty save, and gives back nothing at all`() {
        // ⛔ THE ORDER OF THE TWO GUARDS, and nothing else pins it: swap the two `return`s and this
        // is the only case that changes. `sending` must be read FIRST. Answered as an emptying
        // instead, the queued outbox row goes back to the QUEUE — the very row the send in flight is
        // consuming — and the same message is delivered twice (#70).
        assertEquals(
            AbandonPlan(null, null),
            abandonPlan(
                sending = true,
                emptying = true,
                localDraftId = LOCAL_DRAFT_ID_PREFIX + "d8",
                outboxId = 42L,
            ),
        )
    }

    @Test fun `emptying still puts the queued row back in the queue`() {
        // The two are independent, and only the local row is withheld: nothing is being saved, so a
        // message reopened from the outbox is not being moved anywhere and goes back to the queue
        // rather than evaporating on an empty save (#70).
        assertEquals(
            AbandonPlan(null, 7L),
            abandonPlan(sending = false, emptying = true, localDraftId = null, outboxId = 7L),
        )
    }
}
