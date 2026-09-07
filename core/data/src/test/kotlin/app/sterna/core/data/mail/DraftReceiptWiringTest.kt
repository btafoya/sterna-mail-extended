package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, exactly as [DraftAddressingWiringTest] does:
 * `MailRepository` needs Room, a `Context` and a socket, so no JVM test can run the wiring below.
 * The DECISIONS it guards are EXECUTED elsewhere — [DraftSaveTest] runs
 * `draftReceiptRequestIsCarried`, [ReadReceiptHeaderTest] runs the parser,
 * [ReadReceiptImapSourceTest] runs the IMAP read — and what nothing else can see is whether these
 * call sites still hand those decisions the right ARGUMENTS. So whole lines are compared, never a
 * `contains`: `contains` is blind to any mutation that lengthens a line.
 *
 * What it exists for (D2 of the read-receipt work): re-saving a draft destroys the server copy.
 * That copy is the only thing holding a `Disposition-Notification-To` header, because the reopened
 * composer shows the checkbox clear. Each line below, changed alone, brings that loss back while
 * every executable test stays green:
 * - `receiptRequestIsCarried = receiptIsCarried` → `= true` re-authorises every destroy;
 * - `replacement = requestReceipt` → a literal `false` makes every original look dropped, or a
 *   literal `true` makes every one look carried;
 * - `?: return null` → `?: return false` turns "I could not read it" into "it asked for nothing";
 * - the `refusesReceiptHeader` line, without which a server that rejects the property answers
 *   null for every message and every original is destroyed as one that asked for nothing.
 */
class DraftReceiptWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    // ---- the open: the header is read on BOTH protocols ---------------------------------------

    @Test fun theImapOpenFillsTheSameFieldTheJmapPropertyFills() {
        assertEquals(
            "openEmailImap must fill dispositionNotificationTo from the source it already holds, " +
                "or the reader sees the header on JMAP accounts only",
            listOf("dispositionNotificationTo = readReceiptHeaderOf(raw),"),
            codeLinesNaming(bodyOf("openEmailImap"), "dispositionNotificationTo"),
        )
    }

    // ---- the save: the decision, its arguments, and where it is plugged in ---------------------

    @Test fun bothBranchesAskWhetherTheReceiptRequestSurvived() {
        // Two calls, one per protocol. A single one — the IMAP branch returns early — leaves the
        // other protocol destroying originals on the old, unguarded rule.
        assertEquals(
            "uploadDraft must put the question to draftReceiptRequestIsCarried on BOTH branches",
            listOf(
                "val receiptIsCarried = draftReceiptRequestIsCarried(",
                "val receiptIsCarried = draftReceiptRequestIsCarried(",
            ),
            codeLinesNaming(bodyOf("uploadDraft"), "draftReceiptRequestIsCarried("),
        )
    }

    @Test fun theReplacementsAnswerIsTheComposersOwnCheckbox() {
        assertEquals(
            "what the replacement asks for is the value being saved — a literal here makes the " +
                "guard answer the same thing for every draft",
            listOf("replacement = requestReceipt,", "replacement = requestReceipt,"),
            codeLinesNaming(bodyOf("uploadDraft"), "replacement = requestReceipt"),
        )
    }

    @Test fun eachBranchReadsTheOriginalThroughItsOwnProtocol() {
        assertEquals(
            "the original's header must be read from the server, per protocol, for the id being " +
                "replaced",
            listOf(
                "originalRequested = { id -> imapDraftReceiptRequested(credentials, id) },",
                "originalRequested = { id -> jmapDraftReceiptRequested(ctx, id) },",
            ),
            codeLinesNaming(bodyOf("uploadDraft"), "originalRequested ="),
        )
    }

    @Test fun theVerdictHandedToTheGuardIsTheOneJustRead() {
        // `= true` on either line is the whole debt, restored — and both executable suites stay
        // green, because neither of them can see this call.
        assertEquals(
            "both branches must hand draftReplacementIsFaithful the verdict they just read; " +
                "neither may pass a literal",
            listOf(
                "receiptRequestIsCarried = receiptIsCarried,",
                "receiptRequestIsCarried = receiptIsCarried,",
            ),
            codeLinesNaming(bodyOf("uploadDraft"), "receiptRequestIsCarried"),
        )
    }

    // ---- the two readers: an unknown must never come back as a "no" ---------------------------

    /**
     * NOT a closed list of the bail-outs, and deliberately: an assertion that pins how MANY
     * `?: return` lines there are makes ADDING a missing guard fail the test, i.e. it makes the
     * fix more expensive than the defect. What is pinned is the rule every bail-out must obey.
     */
    @Test fun everyBailOutOfTheImapReadAnswersUnknownAndNeverANo() {
        val body = bodyOf("imapDraftReceiptRequested")
        val bailOuts = codeLinesNaming(body, "?: return")
        assertTrue("the read must have bail-outs at all: $body", bailOuts.isNotEmpty())
        assertEquals(
            "an unreadable original is UNKNOWN, which keeps it — `?: return false` reads as " +
                "'this draft asked for nothing' and authorises the destroy",
            emptyList<String>(),
            bailOuts.filterNot { it.endsWith("?: return null") },
        )
        assertEquals(
            "and nothing in this read may answer a bare false for any other reason either",
            emptyList<String>(),
            codeLinesNaming(body, "return false"),
        )
        assertEquals(
            "the answer is the SERVER's source, handed to the decision a test can execute",
            listOf(
                "val raw = imap.fetchSource(credentials, cached.mailboxId, uid)",
                "return receiptRequestedInSource(raw)",
            ),
            codeLinesNaming(body, "raw"),
        )
    }

    @Test fun theJmapReadTreatsARefusingServerAsUnknown() {
        val body = bodyOf("jmapDraftReceiptRequested")
        // Whole lines, arguments included: a prefix match is blind to every mutation that
        // lengthens the line — reading some other account, or some other id.
        assertEquals(
            "the original is fetched for the id being replaced, on this account's session",
            listOf("val email = client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)"),
            codeLinesNaming(body, "client.getEmail("),
        )
        assertEquals(
            "a server that rejects the property answers null for EVERY message; that verdict " +
                "must reach the decision, or the null is read as 'asked for nothing'",
            listOf("serverRefusesProperty = client.refusesReceiptHeader(ctx.session),"),
            codeLinesNaming(body, "refusesReceiptHeader"),
        )
        assertEquals(
            "and the answer itself is the fetched message's own header",
            listOf("header = email.dispositionNotificationTo,"),
            codeLinesNaming(body, "dispositionNotificationTo"),
        )
    }
}
