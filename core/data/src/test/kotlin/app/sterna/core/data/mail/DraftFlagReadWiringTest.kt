package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THIS TEST READS SOURCE TEXT — the last resort, taken for the reason DraftAddressingWiringTest
 * names: `MailRepository` cannot be built in a JVM test. The decision it feeds is EXECUTED next
 * door, in DraftFlagIsCarriedTest; what only the text can show is that the repository asks the
 * server, on the message it was asked about, and hands every unknown to that decision.
 */
class DraftFlagReadWiringTest {

    private fun codeLines(body: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    @Test fun theRepositoryPutsTheQuestionToTheDecisionATestCanExecute() {
        assertEquals(
            "carriesDraftFlag must hand the question to draftFlagIsCarried — the pure function a " +
                "test executes — with the account's protocol, the JMAP keyword off the draft " +
                "itself, and the IMAP read as the lambda it only runs on IMAP. A predicate " +
                "written inline here is one nothing reaches.",
            listOf(
                "{",
                "return draftFlagIsCarried(credentials.protocol, draft.isDraft) { imapDraftFlag(credentials, draft, uidValidity) }",
                "}",
            ),
            codeLines(DaoQuerySource.mailFunctionBody("MailRepository", "carriesDraftFlag")),
        )
    }

    @Test fun theNumberingArgumentCarriesNoDefaultThatWouldReopenTheHole() {
        assertEquals(
            "⛔ `uidValidity` takes NO default. `= null` here compiles for every caller and hands " +
                "`onMailbox` the numbering the cache last recorded instead of the one the id was " +
                "frozen under, which is the whole point of passing it: the read stops being " +
                "opposed to a renumbering, and a uid that now names another message can license " +
                "a destroy. The body rule below cannot see this — it reads the body, not the " +
                "signature — so the ban lives here.",
            listOf("suspend fun carriesDraftFlag(credentials: AccountCredentials, draft: Email, uidValidity: Long?): Boolean {"),
            DaoQuerySource.mailSource("MailRepository").lines().map { it.trim() }
                .filter { it.contains("fun carriesDraftFlag(") },
        )
    }

    @Test fun theImapReadIsTheServersOwnFlagForTheUidThatWasAskedAbout() {
        assertEquals(
            "the IMAP side must be `fetchIsDraft` of the uid this id names, in the folder the " +
                "message that was just read sits in, and it must answer null — never false — when " +
                "either is missing: false is the licence to destroy, and an id with no uid is not " +
                "a message the server ever marked anything. And it is read UNDER THE NUMBERING THE ID WAS " +
                "FROZEN AGAINST (#99, #189): unopposed, a folder renumbered between the reopen " +
                "and this read makes the uid name a DIFFERENT message, whose \\Draft would license " +
                "the destruction of the received mail this branch exists to spare.",
            listOf(
                "{",
                "val uid = ImapMailService.uidOf(draft.id) ?: return null",
                "val mailboxId = draft.mailboxId ?: return null",
                "return imap.fetchIsDraft(credentials, mailboxId, uid, uidValidity)",
                "}",
            ),
            codeLines(DaoQuerySource.mailFunctionBody("MailRepository", "imapDraftFlag")),
        )
    }
}
