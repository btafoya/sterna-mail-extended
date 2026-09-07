package app.sterna.ui.connect

import app.sterna.core.data.mail.NotifyRead
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The decision an ADD takes about the new inbox's notification baseline, EXECUTED —
 * [baselineForAddedInbox] with its arguments pinned, not read as text.
 *
 * The defect it closes: creating an account wrote no baseline, so the first background pass of a
 * new account was also the first sight of its inbox, and a first sight seeds silently. The first
 * message arriving after an add was announced by nobody, ever.
 *
 * What is NOT covered here: that the add actually CALLS this (nothing in this module can build a
 * `ConnectViewModel`, and `NewMailNotifier` wants SharedPreferences). The call site is held by the
 * source lint in [ConnectPrimingWiringTest].
 */
class AddedInboxBaselineTest {

    /**
     * The test that kills `read.emails.map { it.id }`.
     *
     * `emails` is hydrated and therefore capped (`NOTIFY_CANDIDATE_MAX`); `baselineIds` is bounded
     * by the age floor alone, so it is the LONGER of the two — which is why the read here holds two
     * hydrated rows for five ids. Seeding from `emails` would return `[b, d]` and forget `a`, `c`
     * and `e`; `NewMailNotifier.seed` REPLACES the set, so those three would announce as new mail
     * on a later pass and be dragged out of the Archive server-side by `unarchiveThreadsOnReply`.
     */
    @Test fun `a new inbox remembers every id the bounded read listed, not only the hydrated ones`() {
        val read = NotifyRead(
            emails = listOf(Email(id = "b"), Email(id = "d")),
            baselineIds = listOf("a", "b", "c", "d", "e"),
        )
        assertEquals(
            "the baseline of a freshly added inbox must be the read's whole id list — seeding the " +
                "hydrated rows instead forgets what the cap shed, and a forgotten id announces as " +
                "new mail later",
            listOf("a", "b", "c", "d", "e"),
            baselineForAddedInbox(read, hasBaseline = false),
        )
        // IDENTITY, not just equality, and it is the assertion that survives a small fixture.
        // Equality on five ids stays green under `read.baselineIds.take(NOTIFY_CANDIDATE_MAX)` —
        // a cap of 500 is invisible below 500 — and that mutation is precisely the "baseline
        // quietly bounded" defect this function exists to refuse. Handing back the read's own list
        // untouched is the property; any take/map/filter/sort breaks it at any size.
        assertSame(
            "the decision must hand back the read's own id list, untouched: anything that copies " +
                "it through take/map/filter can bound the baseline, and a bounded baseline forgets " +
                "ids that then announce as new mail and leave the Archive server-side",
            read.baselineIds,
            baselineForAddedInbox(read, hasBaseline = false),
        )
    }

    /**
     * A re-authentication, or the re-add of an account already set up here, finds a LIVE baseline.
     * Overwriting it would swallow every arrival it had not announced yet — the reported defect,
     * one screen further along.
     */
    @Test fun `an add that finds a baseline already there writes nothing`() {
        val read = NotifyRead(
            emails = listOf(Email(id = "b"), Email(id = "d")),
            baselineIds = listOf("a", "b", "c", "d", "e"),
        )
        assertNull(
            "an add must never replace a baseline that already exists: what that baseline is " +
                "still holding has not been announced yet",
            baselineForAddedInbox(read, hasBaseline = true),
        )
    }

    /**
     * An empty read writes NOTHING, and this is the assertion that keeps the fix from being
     * worse than the defect.
     *
     * Empty reads like "a new account whose inbox is empty", and sometimes it is. On IMAP it is
     * also what a page that could not say anything looks like: a SELECT that never stated a count
     * leaves `exists` at 0 — "which reads exactly like an empty folder"
     * (`ImapMailService.pageVouchesForEmpty`) — and `refreshImap` returns a normal `MailboxMeta`
     * over it rather than throwing. Nothing that reaches this function can tell them apart.
     *
     * Seeded empty, the folder is marked KNOWN while remembering nothing: `hasBaseline` flips true,
     * the next background pass leaves the silent path, diffs against nothing, posts a banner per
     * thread for everything inside the age floor, and `unarchiveThreadsOnReply` pulls those threads
     * back out of the Archive SERVER-SIDE. Skipping costs one unannounced message in a genuinely
     * empty new inbox — what yesterday's build did anyway. Mail moved on someone's server against a
     * silence: the silence wins.
     */
    @Test fun `an empty read seeds nothing, because empty may be a page that could not answer`() {
        assertNull(
            "an empty id list must not be written as a baseline: on IMAP it is indistinguishable " +
                "from a page that could not state what the folder holds, and marking the folder " +
                "known while remembering nothing makes the next pass announce the whole age floor " +
                "and unarchive it server-side",
            baselineForAddedInbox(NotifyRead(emptyList(), emptyList()), hasBaseline = false),
        )
    }
}
