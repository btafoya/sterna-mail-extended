package app.sterna.ui.compose

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.model.JmapAccount
import app.sterna.core.jmap.model.JmapSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentsTotalTest {

    @Test fun `nothing is said under two attachments while within the limit`() {
        assertEquals(null, attachmentsTotal(emptyList(), null))
        assertEquals(null, attachmentsTotal(listOf(5_000L), null))
        assertEquals(null, attachmentsTotal(listOf(5_000L), 5_000L))
    }

    @Test fun `a single attachment over a known limit is said, as over`() {
        assertEquals(AttachmentsTotal(5_000L, 4_999L, over = true), attachmentsTotal(listOf(5_000L), 4_999L))
    }

    @Test fun `two attachments or more give their sum, with no limit when none is known`() {
        assertEquals(AttachmentsTotal(sum = 3_000L, limit = null, over = false), attachmentsTotal(listOf(1_000L, 2_000L), null))
        assertEquals(AttachmentsTotal(sum = 6L, limit = null, over = false), attachmentsTotal(listOf(1L, 2L, 3L), null))
    }

    @Test fun `the total is over only when the sum exceeds the limit`() {
        assertEquals(AttachmentsTotal(3_000L, 3_000L, over = false), attachmentsTotal(listOf(1_000L, 2_000L), 3_000L))
        assertEquals(AttachmentsTotal(3_001L, 3_000L, over = true), attachmentsTotal(listOf(1_001L, 2_000L), 3_000L))
    }

    @Test fun `an unknown size counts for nothing, and a total of nothing is not said`() {
        assertEquals(AttachmentsTotal(2_000L, null, over = false), attachmentsTotal(listOf(0L, 2_000L, -1L), null))
        assertEquals(null, attachmentsTotal(listOf(0L, -1L), 10L))
    }

    // ---- sendingAttachmentsLimit: only the session already in memory, only the sender's login ----

    private val login = AccountCredentials(server = "mail.example.com", username = "alex", password = "x", id = "acc-1")

    private val session = JmapSession(
        apiUrl = "https://mail.example.com/jmap/api/",
        capabilities = mapOf(
            Jmap.CORE_CAPABILITY to Json.parseToJsonElement("""{"maxSizeUpload": 1}""").jsonObject,
        ),
        accounts = mapOf(
            "own" to JmapAccount(
                name = "alex",
                accountCapabilities = mapOf(
                    Jmap.MAIL_CAPABILITY to Json.parseToJsonElement("""{"maxSizeAttachmentsPerEmail": 50000}""").jsonObject,
                ),
            ),
            "shared" to JmapAccount(
                name = "team",
                accountCapabilities = mapOf(
                    Jmap.MAIL_CAPABILITY to Json.parseToJsonElement("""{"maxSizeAttachmentsPerEmail": 999}""").jsonObject,
                ),
            ),
        ),
        primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "own"),
    )

    private fun cached(credentials: AccountCredentials, accountId: String = "own") = MailRepository.JmapAccountSession(
        credentials = credentials,
        session = session,
        accountId = accountId,
        auth = BasicAuth("alex", "x"),
        rolesToMailboxId = emptyMap(),
    )

    @Test fun `the sender's cached session gives the limit of the account the Email is created under`() {
        assertEquals(50_000L, sendingAttachmentsLimit(cached(login), login))
        // A linked sub-account is cached under the same login, and its Email is still created
        // under the login's account, as the send does it.
        val sub = login.copy(id = "acc-2", jmapAccountId = "shared")
        assertEquals(50_000L, sendingAttachmentsLimit(cached(sub, accountId = "shared"), login))
        assertEquals(50_000L, sendingAttachmentsLimit(cached(login), sub))
    }

    @Test fun `no cached session, another login's session, or an IMAP sender give no limit`() {
        assertEquals(null, sendingAttachmentsLimit(null, login))
        assertEquals(null, sendingAttachmentsLimit(cached(login), null))
        assertEquals(null, sendingAttachmentsLimit(cached(login), login.copy(username = "jordan")))
        assertEquals(null, sendingAttachmentsLimit(cached(login), login.copy(server = "other.example.com")))
        assertEquals(null, sendingAttachmentsLimit(cached(login), login.copy(protocol = MailProtocol.IMAP)))
    }
}
