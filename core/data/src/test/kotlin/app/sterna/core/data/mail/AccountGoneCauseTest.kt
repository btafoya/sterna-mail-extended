package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EXECUTES the choice of what an [AccountGoneException] is allowed to claim, and pins the sentence
 * of BOTH call sites by value. The wiring — that the two catches call this instead of writing their
 * own sentence, and with which arguments — is held by the two source lints that already guard those
 * arms: [SignedOutAccountWiringTest] for `MailRepository.refreshAllInboxes`, and
 * `app.sterna.push.SignedOutAccountLeavesNoPushErrorTest` for `FetchAndNotify.run`. Neither class
 * can be built in a JVM test and `android.util.Log` is not mocked, so nothing here observes a
 * printed line.
 *
 * The expected strings are written out in full, never recomputed from the same `if`: a test that
 * rebuilds the rule to decide what to expect passes on an inverted rule too.
 *
 * The id under test is a REAL UUID, not a short stand-in. `AccountStore` ids are
 * `UUID.randomUUID().toString()`, and the obvious "don't dump a full id in logcat" edit
 * (`accountId.take(5)`) is invisible to a test whose id is five characters long — it would leave
 * the line unable to name the account it is about, which is the whole point of printing it.
 */
class AccountGoneCauseTest {

    /** A shipped-shape id: 36 characters, so any truncation of it shows. */
    private val id = "9f4d2c1e-7b3a-4e58-9c0d-1a2b3c4d5e6f"

    @Test fun `a sign-out is reported as a sign-out, in the caller's own words`() {
        assertEquals(
            "the ordinary case changed wording, or lost the id it exists to name. This is the " +
                "line #130 tuned down to Log.i on the push path, and the one refreshAllInboxes " +
                "has printed since #121 on the other; each keeps its own word for what was " +
                "running, and BOTH must carry the whole account id.",
            listOf(
                "9f4d2c1e-7b3a-4e58-9c0d-1a2b3c4d5e6f signed out mid-pass",
                "9f4d2c1e-7b3a-4e58-9c0d-1a2b3c4d5e6f signed out mid-walk",
            ),
            listOf(
                accountGoneCause(id, accountsUnreadable = false, passKind = "pass"),
                accountGoneCause(id, accountsUnreadable = false, passKind = "walk"),
            ),
        )
    }

    /**
     * The whole point of the volet: `accounts()` answers an unreadable blob with an empty list, so
     * the exception fires with nobody having signed anything out. The line must say the account
     * data could not be READ — and still name the account, whole.
     */
    @Test fun `an unreadable account list is not reported as a sign-out`() {
        assertEquals(
            "a storage failure is being logged as a sign-out (or the sentence no longer names the " +
                "read failure, or no longer names the account). The logcat a reporter sends us " +
                "then accuses a gesture the user never made, and the defect is hunted in the " +
                "wrong place.",
            "the stored account list could not be read, so 9f4d2c1e-7b3a-4e58-9c0d-1a2b3c4d5e6f " +
                "was not found in it (not a sign-out)",
            accountGoneCause(id, accountsUnreadable = true, passKind = "pass"),
        )
    }

    /**
     * A read failure is not about what was running: whatever the caller was doing, the blob was
     * unreadable. Pinned so that the two claims cannot silently swap roles.
     */
    @Test fun `the read failure reads the same whatever the pass was`() {
        assertEquals(
            "the unreadable-blob sentence now depends on the caller's word, so the two claims no " +
                "longer say what they were written to say.",
            accountGoneCause(id, accountsUnreadable = true, passKind = "pass"),
            accountGoneCause(id, accountsUnreadable = true, passKind = "walk"),
        )
    }
}
