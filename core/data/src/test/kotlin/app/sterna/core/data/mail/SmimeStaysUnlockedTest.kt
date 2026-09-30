package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.ServerContactDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Executes the real repository, built as [MailRepositoryBuildsOnTheJvmTest] builds it, with no
 * OpenPGP engine and DAOs that throw: a detached S/MIME signature leaves a message unlocked, so it is
 * served and cached as ordinary mail; and an OpenPGP lock with no OpenPGP app is answered before any
 * database or network read.
 */
class SmimeStaysUnlockedTest {

    private val store = RecordingAccountStorage()

    private val repository = MailRepository(
        client = JmapClient(),
        emailDao = unusableDao<EmailDao>(),
        emailFtsDao = unusableDao<EmailFtsDao>(),
        emailBodyDao = unusableDao<EmailBodyDao>(),
        mailboxDao = unusableDao<MailboxDao>(),
        imap = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)),
        scheduledSendDao = unusableDao<ScheduledSendDao>(),
        snoozedDao = unusableDao<SnoozedDao>(),
        recentContactDao = unusableDao<RecentContactDao>(),
        accountStore = store,
        outboxDao = unusableDao<OutboxDao>(),
        purgeSnapshotDao = unusableDao<PurgeSnapshotDao>(),
        localDraftDao = unusableDao<LocalDraftDao>(),
        serverContactDao = unusableDao<ServerContactDao>(),
        mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
        outboxFilesDir = File("outbox-never-read"),
        localDraftFilesDir = File("drafts-never-read"),
    )

    private fun withPart(type: String, name: String) =
        Email(id = "1", attachments = listOf(EmailBodyPart(partId = "2", name = name, type = type)))

    @Test fun `a detached S-MIME signature, named or not, leaves the message unlocked`() {
        listOf(
            "application/pkcs7-signature" to "smime.p7s",
            "application/pkcs7-signature" to "pkcs7-signature",
            "application/x-pkcs7-signature" to "smime.p7s",
            "application/x-pkcs7-signature" to "x-pkcs7-signature",
        ).forEach { (type, name) ->
            assertNull("$type named $name", repository.cryptoKindOf(withPart(type, name)))
        }
        assertEquals(
            "the same call does lock an OpenPGP signature",
            CryptoKind.PGP_SIGNED,
            repository.cryptoKindOf(withPart("application/pgp-signature", "signature.asc")),
        )
    }

    @Test fun `an OpenPGP lock with no OpenPGP app is not available before anything is read`() {
        val credentials = AccountCredentials(
            server = "https://mail.example.test",
            username = "u",
            password = "p",
            id = "acc",
            protocol = MailProtocol.IMAP,
        )
        (listOf(CryptoKind.PGP_ENCRYPTED, CryptoKind.PGP_SIGNED, CryptoKind.PGP_INLINE) + null).forEach { kind ->
            val result = runBlocking {
                runCatching { repository.decryptMessage(credentials, "imap:acc:INBOX:7", lockedKind = kind) }
                    .getOrElse { PgpResult.Error("threw ${it.javaClass.simpleName}: ${it.message}") }
            }
            assertEquals("locked as $kind", PgpResult.NotAvailable, result)
        }
    }
}
