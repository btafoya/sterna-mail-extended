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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ten lines archived together leave at the tap, like one line swiped: the repository holds their
 * rows while the server is still being asked, and puts back only the ones whose write was refused.
 *
 * The cost is why this is not [MailRepository.beginRemoval] in a loop: the marker store commits to
 * disk synchronously, so a loop would make the tap wait on one disk write per message. That is
 * measured here, by counting the writes the store was asked for.
 *
 * Executed, not read: the repository is built on the JVM with recording DAOs and a recording
 * marker store ([MailRepositoryBuildsOnTheJvmTest] is why that is possible).
 */
class BulkRemovalHoldsTheRowsItEvictedTest {

    /** What the fake database and the fake marker file were asked to do, in order. */
    private val calls = mutableListOf<String>()

    /** The cached rows, keyed by account and id: message ids collide between two accounts of one
     *  server (#31), and this test asks what a ticket of one account does to the other's row. */
    private val cached = linkedMapOf<String, EmailEntity>()

    private val store = RecordingAccountStorage()

    /** The drawer's per-folder counters, as the deltas the repository asked for, in order. */
    private val folderCounts = mutableListOf<Triple<String, Int, Int>>()

    private fun row(id: String, accountId: String = "acc", seen: Boolean = false) = EmailEntity(
        id = id,
        accountId = accountId,
        mailboxId = "inbox",
        threadId = "t-$id",
        subject = "Subject $id",
        preview = "Preview",
        receivedAt = "2026-09-01T10:00:00Z",
        fromName = "Alex",
        fromEmail = "alex@example.test",
        seen = seen,
        flagged = false,
        hasAttachment = false,
        sortKey = 1_756_720_800_000,
    )

    private fun cache(vararg rows: EmailEntity) = rows.forEach { cached["${it.accountId}/${it.id}"] = it }

    /** The marker file, whole-batch writes and all, recording what it was handed. */
    private inner class RecordingMarkers : FlightMarkerStore {
        val disk = linkedMapOf<String, FlightMarker>()
        val writes = mutableListOf<List<FlightMarker>>()
        var writeFails = false

        override fun write(markers: List<FlightMarker>) {
            calls += "writeMarkers(${markers.map { it.emailId }})"
            writes += markers
            // The exception the shipped store raises on a `commit()` that returned false
            // (`flightMarkersCommitted`): a store that logged and returned would let the eviction
            // below go out with no marker behind it.
            if (writeFails) throw FlightMarkersRefused("swipe markers not written: disk full")
            markers.forEach { disk[flightMarkerKey(it.accountId, it.emailId)] = it }
        }

        override fun erase(accountId: String, emailIds: List<String>) {
            calls += "eraseMarkers($emailIds)"
            emailIds.forEach { disk.remove(flightMarkerKey(accountId, it)) }
        }

        override fun all(): List<FlightMarker> = disk.values.toList()
    }

    private val markers = RecordingMarkers()

    /** Set to throw out of the n-th eviction call of a run, the disk giving way mid-batch. */
    private var evictionFailsOnCall: Int? = null
    private var evictions = 0

    @Suppress("UNCHECKED_CAST")
    private fun emailDao(): EmailDao = Proxy.newProxyInstance(
        EmailDao::class.java.classLoader,
        arrayOf(EmailDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "emailsByIds" -> {
                val accountId = args[0] as String
                val ids = args[1] as List<String>
                calls += "emailsByIds(${ids.size} ids)"
                // Answered in an order the caller did not ask for, because the real query may:
                // `emailsByIds` has no ORDER BY. A fake that echoed the asked order would let a
                // ticket built from the database's answer look exactly like a correct one.
                ids.mapNotNull { cached["$accountId/$it"] }.reversed()
            }
            "evictFromCacheKeepingIndex" -> {
                val accountId = args[0] as String
                val ids = args[1] as List<String>
                calls += "evictFromCacheKeepingIndex(${ids.size} ids)"
                evictions++
                check(evictions != evictionFailsOnCall) { "the cache write gave way" }
                ids.forEach { cached.remove("$accountId/$it") }
                Unit
            }
            "upsertAll" -> {
                val rows = args[0] as List<EmailEntity>
                calls += "upsertAll(${rows.size} rows)"
                rows.forEach { cached["${it.accountId}/${it.id}"] = it }
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

    private fun repository(): MailRepository = MailRepository(
        client = JmapClient(),
        emailDao = emailDao(),
        emailFtsDao = unusableDao<EmailFtsDao>(),
        emailBodyDao = unusableDao<EmailBodyDao>(),
        mailboxDao = mailboxDao(),
        imap = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)),
        scheduledSendDao = unusableDao<ScheduledSendDao>(),
        snoozedDao = unusableDao<SnoozedDao>(),
        recentContactDao = unusableDao<RecentContactDao>(),
        accountStore = store,
        outboxDao = unusableDao<OutboxDao>(),
        purgeSnapshotDao = unusableDao<PurgeSnapshotDao>(),
        localDraftDao = unusableDao<LocalDraftDao>(),
        mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
        outboxFilesDir = File("outbox-never-read"),
        localDraftFilesDir = File("drafts-never-read"),
        flightMarkerStore = markers,
    )

    /** The unread deltas the account store was handed, as (mailboxId, delta). */
    private fun inboxUnreadDeltas(): List<Pair<String, Int>> =
        store.calls.filter { it.first == "adjustInboxUnread" }
            .map { it.second["mailboxId"] as String to it.second["delta"] as Int }

    private fun ids(count: Int): List<String> = (1..count).map { "m$it" }

    @Test fun `the batch marker is on disk before a single row leaves the cache`() {
        cache(row("m1"), row("m2"), row("m3"))

        val ticket = runBlocking { repository().beginRemovalAll("acc", listOf("m1", "m2", "m3")) }

        assertEquals(MailRepository.InFlightBatch("acc", listOf("m1", "m2", "m3")), ticket)
        assertEquals(
            "the markers must be on disk BEFORE the eviction: killed in between, the process " +
                "leaves the rows out of the cache while the server still files the messages, and " +
                "the next sync comes down the delta branch, which never brings them back",
            listOf(
                "emailsByIds(3 ids)",
                "writeMarkers([m1, m2, m3])",
                "evictFromCacheKeepingIndex(3 ids)",
            ),
            calls,
        )
        assertEquals(
            listOf(FlightMarker("acc", "m1", "inbox"), FlightMarker("acc", "m2", "inbox"), FlightMarker("acc", "m3", "inbox")),
            markers.all(),
        )
        assertEquals("the folder loses the three unread at the tap", listOf("inbox" to -3), inboxUnreadDeltas())
        assertEquals(listOf(Triple("inbox", -3, -3)), folderCounts)
    }

    @Test fun `a selection costs one marker write, not one per message`() {
        val selection = ids(40)
        cache(*selection.map { row(it) }.toTypedArray())

        runBlocking { repository().beginRemovalAll("acc", selection) }

        assertEquals(
            "one write for the whole batch: the store commits synchronously, so one write per " +
                "message is forty blocking disk writes under the finger, which is the whole " +
                "reason this is not beginRemoval in a loop",
            1,
            markers.writes.size,
        )
        assertEquals(selection, markers.writes.single().map { it.emailId })
        assertEquals(
            "and one count nudge for the batch, not one per row",
            listOf("inbox" to -40),
            inboxUnreadDeltas(),
        )
    }

    @Test fun `three rows come back and the other seven stay gone`() {
        val selection = ids(10)
        cache(*selection.map { row(it) }.toTypedArray())
        val repository = repository()
        val ticket = runBlocking { repository.beginRemovalAll("acc", selection) }!!
        calls.clear()
        store.calls.clear()
        folderCounts.clear()

        runBlocking { repository.endRemovalAll(ticket, restore = listOf("m2", "m5", "m9")) }

        assertEquals(
            "only the three the caller named are back in the list",
            listOf("m2", "m5", "m9"),
            cached.values.map { it.id },
        )
        assertEquals(
            "the three refused writes go back into the cache as they were, with no network at " +
                "all, and the seven that landed are not written back",
            listOf("upsertAll(3 rows)", "eraseMarkers([m1, m2, m3, m4, m5, m6, m7, m8, m9, m10])"),
            calls,
        )
        assertEquals(
            "and the badge comes back for those three alone",
            listOf("inbox" to 3),
            inboxUnreadDeltas(),
        )
        assertEquals(listOf(Triple("inbox", 3, 3)), folderCounts)
        assertEquals("no key and no marker outlives the batch", emptyList<FlightMarker>(), markers.all())
    }

    @Test fun `a put-back credits the totals of every row and the unread of the unread ones`() {
        cache(row("m1"), row("m2", seen = true))
        val repository = repository()
        val ticket = runBlocking { repository.beginRemovalAll("acc", listOf("m1", "m2")) }!!
        store.calls.clear()
        folderCounts.clear()

        runBlocking { repository.endRemovalAll(ticket, restore = listOf("m1", "m2")) }

        assertEquals(listOf("inbox" to 1), inboxUnreadDeltas())
        assertEquals(listOf(Triple("inbox", 2, 1)), folderCounts)
    }

    @Test fun `the ticket's ids are the caller's order, never the database's`() {
        cache(row("m1"), row("m2"), row("m3"))

        val ticket = runBlocking { repository().beginRemovalAll("acc", listOf("m3", "m1", "m2")) }!!

        assertEquals(
            "the ids of a ticket are handed straight back to a mover, and a selection reshuffled " +
                "by the database is a selection no one asked for",
            listOf("m3", "m1", "m2"),
            ticket.emailIds,
        )
        assertEquals(listOf("m3", "m1", "m2"), markers.writes.single().map { it.emailId })
    }

    @Test fun `a marker file that refuses the batch leaves the cache and the counts untouched`() {
        cache(row("m1"), row("m2"), row("m3"))
        markers.writeFails = true
        val repository = repository()

        val ticket = runBlocking { repository.beginRemovalAll("acc", listOf("m1", "m2", "m3")) }

        // No ticket, and no throw: the caller runs on a scope with no handler, and a full disk may
        // not take the process down. Without a ticket the action writes to the server with its rows
        // still in the list, which is what it did before this mechanism existed.
        assertNull("a refused marker file must refuse the flight, not fail the caller", ticket)
        assertEquals(
            "nothing was evicted, so nothing may be written back either",
            listOf("emailsByIds(3 ids)", "writeMarkers([m1, m2, m3])", "eraseMarkers([m1, m2, m3])"),
            calls,
        )
        assertEquals(listOf("m1", "m2", "m3"), cached.values.map { it.id })
        assertEquals(emptyList<Pair<String, Int>>(), inboxUnreadDeltas())
        assertEquals(emptyList<Triple<String, Int, Int>>(), folderCounts)
        assertNoKeyIsStillHeld(repository, "m1")
    }

    @Test fun `a refused marker file refuses a single swipe the same way`() {
        // The swipe runs on `viewModelScope`, which has no exception handler: the batch and the
        // single row must both answer a full disk with no flight rather than with a throw.
        cache(row("m1"))
        markers.writeFails = true
        val repository = repository()

        val flight = runBlocking { repository.beginRemoval("acc", "m1") }

        assertNull("a refused marker file must refuse the flight, not fail the caller", flight)
        assertEquals(listOf("m1"), cached.values.map { it.id })
        assertEquals(emptyList<Pair<String, Int>>(), inboxUnreadDeltas())
    }

    @Test fun `the markers of a forfeited batch are erased in one store write`() {
        // `pruneServerGone` forfeits the rows the server no longer holds. A stale selection comes
        // back as forty of them at once, and erasing them one at a time is forty synchronous disk
        // writes: exactly the cost `beginRemovalAll` exists to avoid on the way in.
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "forfeitInFlight")
        assertTrue(
            "forfeitInFlight must erase the whole batch in one store write. Body was:\n$body",
            "endAll(accountId, held)" in body,
        )
        assertEquals(
            "and it may not go back to erasing them one at a time. Body was:\n$body",
            emptyList<String>(),
            body.lines().filter { "end(accountId" in it },
        )
    }

    @Test fun `an eviction that gives way in the middle of the batch puts the whole batch back`() {
        // 250 rows: the eviction goes out in chunks of MAX_CHANGES, and this one fails on the
        // second chunk, with the first already out of the cache.
        val selection = ids(250)
        cache(*selection.map { row(it) }.toTypedArray())
        evictionFailsOnCall = 2
        val repository = repository()

        val thrown = runBlocking { runCatching { repository.beginRemovalAll("acc", selection) } }

        assertTrue("the refusal must reach the caller", thrown.isFailure)
        assertEquals(
            "all or nothing: the rows the first chunk had already taken out come back, and a " +
                "caller this call never returned to holds no ticket it could close",
            250,
            cached.size,
        )
        assertEquals(
            "the counts were never nudged, so nothing may be credited back",
            emptyList<Pair<String, Int>>(),
            inboxUnreadDeltas(),
        )
        assertEquals(emptyList<FlightMarker>(), markers.all())
        assertNoKeyIsStillHeld(repository, "m1")
    }

    @Test fun `nothing cached is nothing evicted, and no ticket`() {
        val ticket = runBlocking { repository().beginRemovalAll("acc", listOf("gone-1", "gone-2")) }

        assertNull("there are no rows to hold, so there is no ticket to close", ticket)
        assertEquals(listOf("emailsByIds(2 ids)"), calls)
        assertEquals(emptyList<Pair<String, Int>>(), inboxUnreadDeltas())
    }

    @Test fun `a ticket holds one account's rows, never its twin's`() {
        // Two accounts of one server share message ids (#31): a ticket keyed on the id alone would
        // take the other account's line off the screen too.
        cache(row("m1"), row("m1", accountId = "other"))
        val repository = repository()

        val ticket = runBlocking { repository.beginRemovalAll("acc", listOf("m1")) }!!

        assertEquals(MailRepository.InFlightBatch("acc", listOf("m1")), ticket)
        assertNotNull("the other account's row stays on screen", cached["other/m1"])
        assertNull(cached["acc/m1"])

        runBlocking { repository.endRemovalAll(ticket, restore = listOf("m1")) }

        assertEquals(row("m1"), cached["acc/m1"])
        assertEquals(row("m1", accountId = "other"), cached["other/m1"])
    }

    /** Nothing is held for [emailId] any more: a key left standing hides the message from every
     *  page read until the process dies, and only a put-back attempt can see one. */
    private fun assertNoKeyIsStillHeld(repository: MailRepository, emailId: String) {
        calls.clear()
        runBlocking { repository.endRemoval(MailRepository.InFlightRemoval("acc", emailId), restore = true) }
        assertEquals(listOf("eraseMarkers([$emailId])"), calls)
    }
}
