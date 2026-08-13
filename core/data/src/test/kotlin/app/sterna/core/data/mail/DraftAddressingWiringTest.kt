package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠ THIS TEST READS SOURCE TEXT — the last resort, exactly as [DestroyChecksTheFolderWiringTest]
 * does: `MailRepository` and `ImapMailService` need Room, a `Context` and a socket, so no JVM test
 * can run the wiring below. The DECISION it guards is executed by [DraftSaveTest]; this pins only
 * WHERE that decision is plugged in, and it pins the WHOLE LINE of each load-bearing call —
 * `contains` is blind to any mutation that lengthens a line, which is how three of this project's
 * rules were defeated before.
 *
 * What it exists for (#63): re-saving an IMAP draft destroys the server copy. The copy is the only
 * thing holding a Cc the composer never displayed, so the destroy is allowed only when the
 * replacement provably carries the addressing. Every single word below is one that, changed alone,
 * brings the measured loss back while every executable test stays green:
 * - `addressingIsCarried = addressingIsCarried` → `= true` re-authorises every destroy;
 * - `replacement = ccTrimmed.toSet()` → `+ bccTrimmed` lets an address typed into Bcc count as
 *   carried, though `OutgoingMime.build` writes no `Bcc:` header at all;
 * - `?: return null` → `?: return emptySet()` turns "I could not read it" into "there was none".
 */
class DraftAddressingWiringTest {

    private fun bodyOf(file: String, function: String): String =
        DaoQuerySource.mailFunctionBody(file, function)

    /** The code lines of [body] naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    @Test fun theSaveAsksWhetherTheAddressingSurvivedBeforeDestroyingAnything() {
        assertEquals(
            "saveDraft must put the question to draftAddressingIsCarried — the pure function a " +
                "test can execute — not to a predicate written inline where nothing reaches it",
            listOf("val addressingIsCarried = draftAddressingIsCarried("),
            codeLinesNaming(bodyOf("MailRepository", "saveDraft"), "draftAddressingIsCarried("),
        )
    }

    @Test fun onlyTheCcIsOfferedAsProofNeverTheBcc() {
        // THE line of the counter-expertise. OutgoingMime.build writes From/To/Cc and no `Bcc:`,
        // so an address typed into Bcc reaches no header of the appended draft: counting it would
        // let a user who re-types the lost Cc into Bcc destroy the copy that still held it.
        // The second line is the read-receipt guard's own `replacement`, pinned by
        // [DraftReceiptWiringTest]; it is listed here so this assertion keeps comparing WHOLE
        // lines against a closed list rather than being loosened to a search.
        assertEquals(
            "the replacement's proof is its Cc alone — a Bcc is never written into the appended " +
                "draft, so it can prove nothing (#63)",
            listOf("replacement = ccTrimmed.toSet(),", "replacement = requestReceipt,", "replacement = requestReceipt,"),
            codeLinesNaming(bodyOf("MailRepository", "saveDraft"), "replacement ="),
        )
    }

    @Test fun theOriginalsAddressingIsReadFromTheServerForTheReplacedDraft() {
        assertEquals(
            "the original's addressing must come from imapDraftAddressing, for the id being " +
                "replaced and this account — the cache has no cc column to answer with",
            listOf("originalAddressing = { id -> imapDraftAddressing(credentials, id) },"),
            codeLinesNaming(bodyOf("MailRepository", "saveDraft"), "originalAddressing ="),
        )
    }

    @Test fun theVerdictIsTheOneJustComputedAndTheJmapBranchSaysWhyItIsExempt() {
        // Three lines, in this order: the read, the IMAP branch handing its verdict on, and the
        // JMAP branch's literal `true` (its reopen goes through Email/get, so the composer's
        // fields ARE the original's). `= true` on the IMAP one is the whole defect, restored.
        assertEquals(
            "the IMAP save must hand draftReplacementIsFaithful the verdict it just read; only " +
                "the JMAP branch, which reopens through Email/get, may pass a literal true",
            listOf(
                "val addressingIsCarried = draftAddressingIsCarried(",
                "addressingIsCarried = addressingIsCarried,",
                "addressingIsCarried = true,",
            ),
            codeLinesNaming(bodyOf("MailRepository", "saveDraft"), "addressingIsCarried"),
        )
    }

    @Test fun anAddressingThatCannotBeReadReturnsUnknownAndNotAnEmptySet() {
        // `?: return emptySet()` here is a purged cache row, or an id whose UID is gone, passing
        // itself off as "this draft had no copied parties" — and the original is destroyed.
        val body = bodyOf("MailRepository", "imapDraftAddressing")
        assertEquals(
            "every bail-out of imapDraftAddressing must answer null (unknown, keep the " +
                "original) — never an empty set, which reads as 'nothing to lose'",
            listOf(
                "val cached = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull() ?: return null",
                "val uid = ImapMailService.uidOf(emailId) ?: return null",
            ),
            codeLinesNaming(body, "?: return"),
        )
        assertEquals(
            "the addressing itself is the server's envelope, for the folder the cached row names",
            listOf("return imap.ccAndBccOf(credentials, cached.mailboxId, uid)"),
            codeLinesNaming(body, "ccAndBccOf("),
        )
    }

    @Test fun anUnreadableEnvelopeEntryIsNotSilentlyDroppedFromTheAddressing() {
        // A server can hand back a recipient with an empty address. `mapNotNull { it.email }`
        // would drop it, and if it was the only Cc the set is empty — "nothing to lose".
        val body = bodyOf("ImapMailService", "ccAndBccOf")
        assertEquals(
            "the envelope read must go through copiedAddressesOrNull, which answers null when " +
                "an entry is present but yields no address",
            listOf("copiedAddressesOrNull(message.cc + message.bcc)"),
            codeLinesNaming(body, "copiedAddressesOrNull("),
        )
        assertEquals(
            "a message that is not there is unknown too",
            listOf("val message = session.fetchByUid(uid) ?: return@onMailbox null"),
            codeLinesNaming(body, "fetchByUid("),
        )
        assertEquals(
            "no filtering shape may thin this set out — that is exactly the loss",
            emptyList<String>(),
            listOf("mapNotNull", "filterNotNull", "filterNot", "orEmpty()")
                .filter { codeLinesNaming(body, it).isNotEmpty() },
        )
    }
}
