package app.sterna.core.data.mail

import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailEntity
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
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The swipe drops its line when the finger leaves, so the row is out of the cache while the server
 * is still being asked. The repository is the only thing holding it: nothing else has a copy, and
 * the write that has to be survivable is the one made with no network at all, so putting the row
 * back may not fetch anything.
 *
 * Executed, not read: the repository is built on the JVM with recording DAOs
 * ([MailRepositoryBuildsOnTheJvmTest] is why that is possible).
 */
class SwipeHoldsTheRowItEvictedTest {

    private val unreadRow = EmailEntity(
        id = "m1",
        accountId = "acc",
        mailboxId = "inbox",
        threadId = "t1",
        subject = "Subject",
        preview = "Preview",
        receivedAt = "2026-09-01T10:00:00Z",
        fromName = "Alex",
        fromEmail = "alex@example.test",
        seen = false,
        flagged = false,
        hasAttachment = false,
        sortKey = 1_756_720_800_000,
    )

    /** The same message, read. The unread half of the count nudge must answer 0 for it, in both
     *  directions: an unread credited on the way back is a badge the reader can never clear. */
    private val readRow = unreadRow.copy(id = "m2", seen = true)

    /** What the fake database was asked to do, in order, as `method(arguments)`. */
    private val calls = mutableListOf<String>()

    /** The cached rows, keyed by id: the eviction takes one out and a put-back writes it in. */
    private val cached = linkedMapOf(unreadRow.id to unreadRow, readRow.id to readRow)

    private val store = RecordingAccountStorage()

    /** The drawer's per-folder counters, as the deltas the repository asked for, in order. */
    private val folderCounts = mutableListOf<Triple<String, Int, Int>>()

    private fun emailDao(): EmailDao = Proxy.newProxyInstance(
        EmailDao::class.java.classLoader,
        arrayOf(EmailDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            // (accountId, ids, continuation): the account-scoped overload, the only one used here.
            "emailsByIds" -> {
                @Suppress("UNCHECKED_CAST")
                val ids = args[1] as List<String>
                calls += "emailsByIds($ids)"
                ids.mapNotNull { cached[it] }
            }
            "evictFromCacheKeepingIndex" -> {
                @Suppress("UNCHECKED_CAST")
                val ids = args[1] as List<String>
                calls += "evictFromCacheKeepingIndex($ids)"
                ids.forEach { cached.remove(it) }
                Unit
            }
            "upsertAll" -> {
                @Suppress("UNCHECKED_CAST")
                val rows = args[0] as List<EmailEntity>
                calls += "upsertAll(${rows.map { it.id }})"
                rows.forEach { cached[it.id] = it }
                Unit
            }
            else -> error("EmailDao.${method.name} was called by a path that must not touch it.")
        }
    } as EmailDao

    private fun mailboxDao(): MailboxDao = Proxy.newProxyInstance(
        MailboxDao::class.java.classLoader,
        arrayOf(MailboxDao::class.java),
    ) { _, method, args ->
        if (method.name != "adjustCounts") error("MailboxDao.${method.name} was not expected here.")
        folderCounts += Triple(args[1] as String, args[2] as Int, args[3] as Int)
        Unit
    } as MailboxDao

    private fun repository(): MailRepository {
        val tokenRefresher = OAuthTokenRefresher(OAuthClient(), store)
        return MailRepository(
            client = JmapClient(),
            emailDao = emailDao(),
            emailFtsDao = unusableDao<EmailFtsDao>(),
            emailBodyDao = unusableDao<EmailBodyDao>(),
            mailboxDao = mailboxDao(),
            imap = ImapMailService(ImapClient(), SmtpClient(), tokenRefresher),
            scheduledSendDao = unusableDao<ScheduledSendDao>(),
            snoozedDao = unusableDao<SnoozedDao>(),
            recentContactDao = unusableDao<RecentContactDao>(),
            accountStore = store,
            outboxDao = unusableDao<OutboxDao>(),
            purgeSnapshotDao = unusableDao<PurgeSnapshotDao>(),
            localDraftDao = unusableDao<LocalDraftDao>(),
            serverContactDao = unusableDao<app.sterna.core.data.db.ServerContactDao>(),
            mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
            outboxFilesDir = File("outbox-never-read"),
            localDraftFilesDir = File("drafts-never-read"),
        )
    }

    /** The unread deltas the account store was handed, as (mailboxId, delta). */
    private fun inboxUnreadDeltas(): List<Pair<String, Int>> =
        store.calls.filter { it.first == "adjustInboxUnread" }
            .map { it.second["mailboxId"] as String to it.second["delta"] as Int }

    @Test fun `the row leaves the cache at the finger, and its folder is decounted there`() {
        val repository = repository()

        val flight = runBlocking { repository.beginRemoval("acc", "m1") }

        assertEquals(MailRepository.InFlightRemoval("acc", "m1"), flight)
        assertEquals(
            "the row must be read whole BEFORE it is evicted — the cache is its only copy, and " +
                "nothing else can rebuild it for a put-back",
            listOf("emailsByIds([m1])", "evictFromCacheKeepingIndex([m1])"),
            calls,
        )
        assertEquals(
            "the line is gone from the list; the folder it left must lose the unread it was carrying",
            listOf("inbox" to -1),
            inboxUnreadDeltas(),
        )
        assertEquals(listOf(Triple("inbox", -1, -1)), folderCounts)
    }

    @Test fun `a refused write puts the very same row back, and credits its folder again`() {
        val repository = repository()

        val flight = runBlocking { repository.beginRemoval("acc", "m1") }!!
        calls.clear()
        runBlocking { repository.endRemoval(flight, restore = true) }

        assertEquals(
            "the held row goes back into the cache as it was, and with no network at all: the " +
                "write that is refused is usually the one made offline, so a re-fetch would be a " +
                "second thing to fail",
            listOf("upsertAll([m1])"),
            calls,
        )
        assertEquals(unreadRow, cached["m1"])
        assertEquals(
            "and the badge comes back with the row: a count that only the next sync repairs is a " +
                "drawer lying about a message the user can see",
            listOf("inbox" to -1, "inbox" to 1),
            inboxUnreadDeltas(),
        )
        assertEquals(listOf(Triple("inbox", -1, -1), Triple("inbox", 1, 1)), folderCounts)
    }

    @Test fun `a write that landed writes nothing back`() {
        val repository = repository()

        val flight = runBlocking { repository.beginRemoval("acc", "m1") }!!
        calls.clear()
        runBlocking { repository.endRemoval(flight, restore = false) }

        assertEquals("the message moved, the row stays gone", emptyList<String>(), calls)
        assertNull(cached["m1"])
        assertEquals(listOf("inbox" to -1), inboxUnreadDeltas())
    }

    @Test fun `a row the repository no longer holds cannot come back`() {
        // What `pruneServerGone` leaves behind: the server answered `notFound`, the way back was
        // taken away, and the gesture then failed for some other reason and asked for a put-back.
        val repository = repository()

        runBlocking { repository.endRemoval(MailRepository.InFlightRemoval("acc", "m1"), restore = true) }

        assertEquals(
            "nothing held means nothing to put back: a message the server declared gone must not " +
                "be resurrected by a later refusal in the same gesture",
            emptyList<String>(),
            calls,
        )
        assertEquals(emptyList<Pair<String, Int>>(), inboxUnreadDeltas())
    }

    @Test fun `a message already read moves no unread count, in either direction`() {
        val repository = repository()

        val flight = runBlocking { repository.beginRemoval("acc", "m2") }!!
        runBlocking { repository.endRemoval(flight, restore = true) }

        assertEquals(
            "the row was read: its folder loses one total and no unread, and gets both back. A " +
                "put-back that credits an unread anyway raises a badge for a message that carries " +
                "none, and nothing but a full re-sync ever takes it down again.",
            listOf("inbox" to 0, "inbox" to 0),
            inboxUnreadDeltas(),
        )
        assertEquals(listOf(Triple("inbox", -1, 0), Triple("inbox", 1, 0)), folderCounts)
        assertEquals(readRow, cached["m2"])
    }

    @Test fun `nothing cached is nothing evicted`() {
        val repository = repository()

        val flight = runBlocking { repository.beginRemoval("acc", "absent") }

        assertNull("there is no row to hold, so there is no flight to end", flight)
        assertEquals(listOf("emailsByIds([absent])"), calls)
        assertEquals(
            "and no folder may be decounted for a row that was never on screen",
            emptyList<Pair<String, Int>>(),
            inboxUnreadDeltas(),
        )
    }
}
