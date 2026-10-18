package app.sterna.core.data.mail

import app.sterna.core.imap.ImapFolderHits
import app.sterna.core.imap.ImapIndexClaims
import app.sterna.core.imap.ImapIndexVerdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #255: what a folder's verdict becomes on the index side, executed. The ids are written out
 * literally, never rebuilt with `ImapMailService.emailId`, so a test cannot share a defect with
 * the code it checks.
 */
class IndexVerdictBecomesIndexIdsTest {

    private val folders = listOf(
        ImapFolderHits(
            "INBOX",
            emptyList(),
            uidValidity = 42L,
            verdict = ImapIndexVerdict(gone = setOf(5L), flags = mapOf(6L to listOf("\\Seen"), 7L to listOf("\\Flagged"))),
        ),
        ImapFolderHits(
            "Archive",
            emptyList(),
            uidValidity = 42L,
            verdict = ImapIndexVerdict(gone = setOf(5L), flags = mapOf(8L to listOf("\\seen", "\\FLAGGED"), 9L to emptyList())),
        ),
        ImapFolderHits("Sent", emptyList(), uidValidity = 42L, verdict = null),
    )

    @Test fun `only what the probe found absent is gone, under the account and the folder of its hit`() {
        assertEquals(
            "a UID the probe found present is never purged, and the same UID in two folders is two ids",
            listOf("imap:acc:INBOX:5", "imap:acc:Archive:5"),
            indexGoneOf("acc", folders),
        )
        assertEquals(listOf("imap:other:INBOX:5", "imap:other:Archive:5"), indexGoneOf("other", folders))
    }

    @Test fun `the flags read are mapped flag by flag, whatever their case`() {
        assertEquals(
            listOf(
                IndexFlags("imap:acc:INBOX:6", seen = true, flagged = false),
                IndexFlags("imap:acc:INBOX:7", seen = false, flagged = true),
                IndexFlags("imap:acc:Archive:8", seen = true, flagged = true),
                IndexFlags("imap:acc:Archive:9", seen = false, flagged = false),
            ),
            indexFlagsOf("acc", folders),
        )
    }

    @Test fun `a folder without a recorded numbering above zero carries no claims`() = runTest {
        val recorded = mapOf("INBOX" to 42L, "Archive" to 0L, "Junk" to -1L)
        val claims = claimsUnderRecordedNumbering(
            mapOf("INBOX" to setOf(5L), "Archive" to setOf(3L), "Junk" to setOf(4L), "Sent" to setOf(2L)),
        ) { path -> recorded[path] }
        assertEquals(mapOf("INBOX" to ImapIndexClaims(setOf(5L), 42L)), claims)
    }
}
