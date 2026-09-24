package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the composer may offer to keep a draft (#35). A draft is uploaded
 */
class DraftSaveAllowedTest {

    // --- Encrypted: never ------------------------------------------------------------------------

    @Test fun anEncryptedMessageMayNotBeKeptAsADraft() {
        assertFalse(draftSaveAllowed(PgpMode.ENCRYPT))
    }

    @Test fun aLockClosedByHandForbidsTheDraftLikeTheAccountDefault() {
        // The lock cycles off → sign → encrypt; two taps from plain is a hand-set encrypt, and it
        // must forbid the draft exactly as an encrypt-by-default account does. Same field, same
        // answer: the dialog cannot end up more permissive than the toolbar depending on how the
        // message came to be encrypted.
        val handSet = nextPgpMode(nextPgpMode(PgpMode.OFF))
        assertFalse(draftSaveAllowed(handSet))
    }

    @Test fun anEncryptedUnsignedMessageMayNotBeKeptAsADraftEither() {
        // The mode that encrypts without signing hands the server exactly the same plaintext when
        // it is saved as a draft. The rule is asked as "does this mode encrypt?" and not
        // "is this the encrypting mode", which is what makes this answer come out right.
        assertFalse(
            "a draft of an unsigned encrypted message uploads its plaintext to the mail server — " +
                "the very thing the mode exists to withhold.",
            draftSaveAllowed(PgpMode.ENCRYPT_UNSIGNED),
        )
    }

    // --- Everything else: allowed ----------------------------------------------------------------

    @Test fun aPlainMessageMayBeKeptAsADraft() {
        assertTrue(draftSaveAllowed(PgpMode.OFF))
    }

    @Test fun aSignedMessageMayBeKeptAsADraft() {
        // Sign is deliberately not caught: a signed message is readable in transit and at rest by
        // design, so its draft leaks nothing the send would not. Catching it here would be a
        // regression, not extra safety.
        assertTrue(draftSaveAllowed(PgpMode.SIGN))
    }

    @Test fun unlockingAnEncryptedMessageAllowsTheDraftAgain() {
        // Third tap on the lock: back to plain, and the draft is offered again, including for a
        // draft reopened for editing, whose mode is whatever the composer currently shows.
        assertTrue(draftSaveAllowed(nextPgpMode(PgpMode.ENCRYPT)))
    }

    // --- The cycle the hand-set path relies on ---------------------------------------------------

    @Test fun theLockCyclesOffSignEncryptOff() {
        assertTrue(nextPgpMode(PgpMode.OFF) == PgpMode.SIGN)
        assertTrue(nextPgpMode(PgpMode.SIGN) == PgpMode.ENCRYPT)
        assertTrue(nextPgpMode(PgpMode.ENCRYPT) == PgpMode.OFF)
    }

    @Test fun theCycleNeverWalksIntoTheUnsignedMode() {
        // Three stops, still. ENCRYPT_UNSIGNED is reached only by choosing it in the long-press
        // menu: a short tap must not put anyone into sending unsigned mail without asking. The
        // three assertions above are the same rule read forwards.
        assertTrue(
            "the short tap now walks into the unsigned mode. Two taps from plain would start " +
                "sending mail that proves nothing about who sent it, chosen by nobody.",
            PgpMode.entries.none { nextPgpMode(it) == PgpMode.ENCRYPT_UNSIGNED },
        )
    }

    @Test fun aTapAlwaysGetsOutOfTheUnsignedMode() {
        // And out means off, exactly as it does from encrypt: the padlock is the way back to plain.
        assertTrue(nextPgpMode(PgpMode.ENCRYPT_UNSIGNED) == PgpMode.OFF)
        assertTrue(draftSaveAllowed(nextPgpMode(PgpMode.ENCRYPT_UNSIGNED)))
    }

    // --- The two rules are independent -----------------------------------------------------------

    @Test fun contentAloneDoesNotMakeAnEncryptedDraftSavable() {
        // #69 says the compose holds something worth saving; #35 says it may not be saved anyway.
        // Both must hold for a save, so a full message under a closed lock is still refused.
        assertTrue(draftHasContent("alex@example.org", "", "", "Lunch", "see you at noon", false))
        assertFalse(draftSaveAllowed(PgpMode.ENCRYPT))
    }

    // --- Scheduling is stricter than a draft (audit A3) --------------------------------------------
    //
    // A scheduled send is fired by a headless worker with no PGP provider, so scheduling refuses
    // what a draft happily keeps: a signed message would go out unsigned at the due time.
    // Attachments are not an argument of the rule any more: the scheduled row keeps its own copy.

    @Test fun aPlainMessageMayBeScheduled() {
        assertTrue(scheduleSendAllowed(PgpMode.OFF))
    }

    @Test fun aSignedMessageMayBeSavedAsADraftButNotScheduled() {
        // The exact A3 split: sign is fine for a draft (readable at rest anyway) but a scheduled
        // sign send would leave unsigned, the worker can't reach the key.
        assertTrue(draftSaveAllowed(PgpMode.SIGN))
        assertFalse(scheduleSendAllowed(PgpMode.SIGN))
    }

    @Test fun everyPgpModeButOffRefusesScheduling() {
        assertEquals(
            "only a plain message may be scheduled, whatever it carries",
            listOf(PgpMode.OFF),
            PgpMode.entries.filter { scheduleSendAllowed(it) },
        )
    }
}
