package app.sterna.core.data.mail

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
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * [MailRepository] can be built and entered on a plain JVM test, which is the whole point of
 * [app.sterna.core.data.account.AccountStorage]: before it, the account store parameter forced a
 * real one, and building one reads shared preferences through a Context that the stub android.jar
 * answers with "not mocked".
 *
 * The eleven DAOs are proxies that throw on any call, so a path that quietly grew a database read
 * fails here instead of passing on an empty answer.
 */
class MailRepositoryBuildsOnTheJvmTest {

    /** A store whose current account is known, so a caller naming another one is visible. */
    private class StorageOn(private val current: String) : RecordingAccountStorage() {
        override fun currentId(): String? {
            super.currentId()
            return current
        }
    }

    private val store = RecordingAccountStorage()

    /** A mailbox DAO that answers one role and writes down the pair it was asked about. */
    private fun mailboxDaoAnswering(role: String, asked: MutableList<Pair<String, String>>): MailboxDao =
        Proxy.newProxyInstance(
            MailboxDao::class.java.classLoader,
            arrayOf(MailboxDao::class.java),
        ) { _, method, args ->
            if (method.name != "roleForId") error("MailboxDao.${method.name} was not expected here.")
            asked += (args[0] as String) to (args[1] as String)
            role
        } as MailboxDao

    private fun repository(
        accountStore: RecordingAccountStorage = store,
        mailboxDao: MailboxDao = unusableDao<MailboxDao>(),
    ): MailRepository {
        val tokenRefresher = OAuthTokenRefresher(OAuthClient(), accountStore)
        return MailRepository(
            client = JmapClient(),
            emailDao = unusableDao<EmailDao>(),
            emailFtsDao = unusableDao<EmailFtsDao>(),
            emailBodyDao = unusableDao<EmailBodyDao>(),
            mailboxDao = mailboxDao,
            imap = ImapMailService(ImapClient(), SmtpClient(), tokenRefresher),
            scheduledSendDao = unusableDao<ScheduledSendDao>(),
            snoozedDao = unusableDao<SnoozedDao>(),
            recentContactDao = unusableDao<RecentContactDao>(),
            accountStore = accountStore,
            outboxDao = unusableDao<OutboxDao>(),
            purgeSnapshotDao = unusableDao<PurgeSnapshotDao>(),
            localDraftDao = unusableDao<LocalDraftDao>(),
            mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
            outboxFilesDir = File("outbox-never-read"),
            localDraftFilesDir = File("drafts-never-read"),
        )
    }

    /**
     * Asking for a mailbox role with no account named resolves the account through the store and
     * gives up there, so the answer is null and the mailbox DAO is never consulted.
     */
    @Test
    fun theRepositoryIsBuiltAndEnteredThroughTheAccountSeam() {
        val repository = repository()

        val role = runBlocking { repository.mailboxRole(accountId = null, mailboxId = "mailbox-1") }

        assertNull(role)
        assertEquals(listOf("currentId"), store.calls.map { it.first })
    }

    /**
     * The other branch, and the one the app actually takes: a notification and the message screen
     * both name the account the message belongs to. That account must reach the lookup untouched,
     * and the store must not be consulted at all. Resolving the current account first would answer
     * with a role read under the wrong account, which on two accounts of one server is a role that
     * exists and is the wrong one, so the message loses its Not-spam and Delete-for-ever actions.
     */
    @Test
    fun anAccountNamedByTheCallerReachesTheLookupAndNeverTheStore() {
        val storage = StorageOn("account-a")
        val asked = mutableListOf<Pair<String, String>>()
        val repository = repository(accountStore = storage, mailboxDao = mailboxDaoAnswering("junk", asked))

        val role = runBlocking { repository.mailboxRole(accountId = "account-b", mailboxId = "mailbox-1") }

        assertEquals("junk", role)
        assertEquals(listOf("account-b" to "mailbox-1"), asked)
        assertEquals(emptyList<String>(), storage.calls.map { it.first })
    }
}
