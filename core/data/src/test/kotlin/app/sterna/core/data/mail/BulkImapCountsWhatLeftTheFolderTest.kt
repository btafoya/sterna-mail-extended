package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.account.StoredAccount
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
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The IMAP bulk movers, RUN against a scripted server, with the drawer counters recorded: what a
 * bulk action asks the counters for is read off an execution, never off the source text.
 *
 * Two claims, and no reading of `MailRepository.kt` can stand in for either. A selection whose rows
 * a batch ticket holds had its source folder decounted at the tap ([MailRepository.beginRemovalAll]),
 * so the ack owes that folder nothing; decounted twice, the drawer's badge drops by twenty for ten
 * lines and only a full sync repairs it. And a selection the action moves nowhere (archived into
 * the Archive it is already in, deleted from the Trash, moved into its own folder) has to be given
 * back what the tap took, because no nudge credits a folder a message never left.
 *
 * IMAP folder rows carry no server unread count, so `mailboxDao.adjustCounts` is not called at all
 * here (`isImapAccount`): the account store's inbox-meta unread is the only counter that moves, and
 * it is where all of this is observed.
 */
class BulkImapCountsWhatLeftTheFolderTest {

    /** The cached rows, keyed by account and id (#31). */
    private val cached = linkedMapOf<String, EmailEntity>()

    /** Every per-folder delta handed to the drawer's folder rows. Must stay empty on IMAP. */
    private val folderCounts = mutableListOf<Triple<String, Int, Int>>()

    private val store = object : RecordingAccountStorage() {
        // What `isImapAccount` reads: an account store answering null would count an IMAP folder
        // row's unread, which the drawer never shows, and hide where the real badge moves.
        override fun account(id: String): StoredAccount? =
            StoredAccount(id = id, server = "scripted", username = "tester", protocol = MailProtocol.IMAP)
    }

    private fun row(id: String, mailbox: String, seen: Boolean = false) = EmailEntity(
        id = id,
        accountId = ACCOUNT,
        mailboxId = mailbox,
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

    /** Two cached messages of [mailbox], as the ids an IMAP account really carries. */
    private fun cache(mailbox: String): List<String> {
        val ids = UIDS.map { ImapMailService.emailId(ACCOUNT, mailbox, it) }
        ids.forEach { cached["$ACCOUNT/$it"] = row(it, mailbox) }
        return ids
    }

    @Suppress("UNCHECKED_CAST")
    private fun emailDao(): EmailDao = Proxy.newProxyInstance(
        EmailDao::class.java.classLoader,
        arrayOf(EmailDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "emailsByIds" -> {
                val accountId = args[0] as String
                (args[1] as List<String>).mapNotNull { cached["$accountId/$it"] }
            }
            "evictFromCacheKeepingIndex", "deleteByIds" -> {
                val accountId = args[0] as String
                (args[1] as List<String>).forEach { cached.remove("$accountId/$it") }
                Unit
            }
            "upsertAll" -> {
                (args[0] as List<EmailEntity>).forEach { cached["${it.accountId}/${it.id}"] = it }
                Unit
            }
            // The three a \Seen store reads and writes. A row a ticket holds is not in the cache,
            // so `seenOf` answers null and the folder's unread is left alone by that write.
            "seenOf" -> cached["${args[0] as String}/${args[1] as String}"]?.seen
            "mailboxOf" -> cached["${args[0] as String}/${args[1] as String}"]?.mailboxId
            "setSeen" -> Unit
            else -> error("EmailDao.${method.name} was called by a path that must not touch it.")
        }
    } as EmailDao

    private fun mailboxDao(): MailboxDao = Proxy.newProxyInstance(
        MailboxDao::class.java.classLoader,
        arrayOf(MailboxDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "adjustCounts" -> {
                folderCounts += Triple(args[1] as String, args[2] as Int, args[3] as Int)
                Unit
            }
            "roleForId" -> ROLES[args[1] as String]
            else -> error("MailboxDao.${method.name} was not expected here.")
        }
    } as MailboxDao

    private fun purgeSnapshotDao(): PurgeSnapshotDao = Proxy.newProxyInstance(
        PurgeSnapshotDao::class.java.classLoader,
        arrayOf(PurgeSnapshotDao::class.java),
    ) { _, method, _ ->
        if (method.name != "unlistEmails") error("PurgeSnapshotDao.${method.name} was not expected here.")
        Unit
    } as PurgeSnapshotDao

    private fun repository(settings: SettingsRepository? = null): MailRepository = MailRepository(
        client = JmapClient(),
        emailDao = emailDao(),
        emailFtsDao = unusableDao<EmailFtsDao>(),
        emailBodyDao = unusableDao<EmailBodyDao>(),
        mailboxDao = mailboxDao(),
        imap = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher()),
        scheduledSendDao = unusableDao<ScheduledSendDao>(),
        snoozedDao = unusableDao<SnoozedDao>(),
        recentContactDao = unusableDao<RecentContactDao>(),
        accountStore = store,
        outboxDao = unusableDao<OutboxDao>(),
        purgeSnapshotDao = purgeSnapshotDao(),
        localDraftDao = unusableDao<LocalDraftDao>(),
        serverContactDao = unusableDao<app.sterna.core.data.db.ServerContactDao>(),
        mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
        outboxFilesDir = File("outbox-never-read"),
        localDraftFilesDir = File("drafts-never-read"),
        settings = settings,
    )

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "tester",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    /** The unread deltas the account store was handed, as (mailboxId, delta), in order. */
    private fun unreadDeltas(): List<Pair<String, Int>> =
        store.calls.filter { it.first == "adjustInboxUnread" }
            .map { it.second["mailboxId"] as String to it.second["delta"] as Int }

    private fun forget() {
        store.calls.clear()
        folderCounts.clear()
    }

    private fun assertNoFolderRowWasCounted() = assertEquals(
        "an IMAP folder row carries a hard 0 for unread, so nudging a delta onto it manufactures " +
            "a badge out of nothing: `isImapAccount` is what keeps these paths off it",
        emptyList<Triple<String, Int, Int>>(),
        folderCounts,
    )

    // ---- what the ack may decount ---------------------------------------------------------

    @Test fun `with no ticket out, the bulk destroy takes its rows off the folder itself`() {
        // The inverse witness of the test below: without it, "never decount" satisfies it and the
        // folder's badge would simply stop moving on a bulk destroy.
        ScriptedImapServer(scripted()).use { server ->
            val ids = cache(TRASH)

            val result = runBlocking { repository().destroyAll(credentials(server), ids, null, NUMBERING) }

            assertEquals(ids.toSet(), result.succeeded)
            assertEquals(
                "the destroy really went out: a count read off a path that stopped before the " +
                    "wire says nothing at all",
                listOf("UID STORE 7:8 +FLAGS (\\Deleted)"),
                server.issued("UID STORE"),
            )
            assertEquals(listOf("UID EXPUNGE 7:8"), server.issued("UID EXPUNGE"))
            assertEquals(listOf(TRASH to -2), unreadDeltas())
            assertNoFolderRowWasCounted()
        }
    }

    @Test fun `rows a ticket already decounted are not decounted again by the IMAP destroy`() {
        ScriptedImapServer(scripted()).use { server ->
            val ids = cache(TRASH)
            val repository = repository()
            val ticket = runBlocking { repository.beginRemovalAll(ACCOUNT, ids) }!!
            assertEquals("the tap took the two unread off the folder", listOf(TRASH to -2), unreadDeltas())
            forget()

            val result = runBlocking { repository.destroyAll(credentials(server), ticket.emailIds, null, NUMBERING) }

            assertEquals(ids.toSet(), result.succeeded)
            assertEquals(
                "the rows are out of the cache, and the destroy still worked off the ticket",
                listOf("UID EXPUNGE 7:8"),
                server.issued("UID EXPUNGE"),
            )
            assertEquals(
                "the folder lost them at the tap and may not lose them again: counted twice, the " +
                    "badge drops by twenty for ten destroyed lines",
                emptyList<Pair<String, Int>>(),
                unreadDeltas(),
            )
            assertNoFolderRowWasCounted()
        }
    }

    @Test fun `a bulk move credits the destination without decounting a source the tap emptied`() {
        ScriptedImapServer(scripted()).use { server ->
            val ids = cache(INBOX)
            val repository = repository()
            val ticket = runBlocking { repository.beginRemovalAll(ACCOUNT, ids) }!!
            assertEquals(listOf(INBOX to -2), unreadDeltas())
            forget()

            val result = runBlocking {
                repository.moveAllToMailbox(credentials(server), ticket.emailIds, ARCHIVE, mapOf(INBOX to NUMBERING))
            }

            assertEquals(ids.toSet(), result.succeeded)
            assertEquals(listOf("UID MOVE 7:8 \"Archive\""), server.issued("UID MOVE"))
            assertEquals(
                "only the folder the messages land in owes anything: the one they left was " +
                    "decounted at the tap",
                listOf(ARCHIVE to 2),
                unreadDeltas(),
            )
            assertNoFolderRowWasCounted()
        }
    }

    // ---- what an action that moved nothing owes back ---------------------------------------

    @Test fun `archiving a selection already in the Archive gives back what the tap took`() {
        ScriptedImapServer(scripted()).use { server ->
            val ids = cache(ARCHIVE)
            val repository = repository()
            val ticket = runBlocking { repository.beginRemovalAll(ACCOUNT, ids) }!!
            assertEquals(listOf(ARCHIVE to -2), unreadDeltas())
            forget()

            val result = runBlocking { repository.archiveAll(credentials(server), ticket.emailIds) }

            assertEquals(ids.toSet(), result.succeeded)
            assertEquals("nothing moved, so nothing may be moved", emptyList<String>(), server.issued("UID MOVE"))
            assertEquals(
                "the two messages never left the Archive, and nothing else ever credits a folder " +
                    "a message did not leave: its badge would sit two short until a whole re-read",
                listOf(ARCHIVE to 2),
                unreadDeltas(),
            )
            assertNoFolderRowWasCounted()
        }
    }

    @Test fun `moving a selection into the folder it is already in gives back what the tap took`() {
        ScriptedImapServer(scripted()).use { server ->
            val ids = cache(ARCHIVE)
            val repository = repository()
            val ticket = runBlocking { repository.beginRemovalAll(ACCOUNT, ids) }!!
            forget()

            val result = runBlocking { repository.moveAllToMailbox(credentials(server), ticket.emailIds, ARCHIVE) }

            assertEquals(ids.toSet(), result.succeeded)
            assertEquals(emptyList<String>(), server.issued("UID MOVE"))
            assertEquals(listOf(ARCHIVE to 2), unreadDeltas())
            assertNoFolderRowWasCounted()
        }
    }

    @Test fun `deleting a selection already in the Trash gives back what the tap took`() {
        ScriptedImapServer(scripted()).use { server ->
            val ids = cache(TRASH)
            val repository = repository()
            val ticket = runBlocking { repository.beginRemovalAll(ACCOUNT, ids) }!!
            forget()

            runBlocking { repository.deleteAll(credentials(server), ticket.emailIds) }

            assertEquals(emptyList<String>(), server.issued("UID MOVE"))
            assertEquals(
                "the messages are still in the Trash on the server, in neither set of the result " +
                    "and out of the cache: the badge is all that is left to be right",
                listOf(TRASH to 2),
                unreadDeltas(),
            )
            assertNoFolderRowWasCounted()
        }
    }

    @Test fun `a credited folder is not credited a second time when the ticket is closed`() {
        ScriptedImapServer(scripted()).use { server ->
            val ids = cache(ARCHIVE)
            val repository = repository()
            val ticket = runBlocking { repository.beginRemovalAll(ACCOUNT, ids) }!!
            runBlocking { repository.archiveAll(credentials(server), ticket.emailIds) }
            forget()

            runBlocking { repository.endRemovalAll(ticket, restore = ticket.emailIds) }

            assertEquals(
                "the flight ended where the folder was credited: a caller closing its ticket " +
                    "afterwards, asking for everything back, may not add the same two again",
                emptyList<Pair<String, Int>>(),
                unreadDeltas(),
            )
        }
    }

    // ---- the opt-in mark-read, on a selection the cache no longer answers for ---------------

    @Test fun `mark-read-on-archive fires on a selection a ticket holds`() {
        ScriptedImapServer(scripted()).use { server ->
            val ids = cache(ARCHIVE)
            val repository = repository(settingsWith("markReadOnArchive"))
            val ticket = runBlocking { repository.beginRemovalAll(ACCOUNT, ids) }!!

            runBlocking { repository.archiveAll(credentials(server), ticket.emailIds) }

            assertEquals(
                "the cache stops answering for a row a ticket holds, and that read is the whole " +
                    "of what decides whether the option fires: fed the cache alone it finds no " +
                    "unread message and quietly stops working on every bulk action",
                listOf("UID STORE 7 +FLAGS (\\Seen)", "UID STORE 8 +FLAGS (\\Seen)"),
                server.issued("UID STORE"),
            )
        }
    }

    @Test fun `mark-read-on-delete fires on a selection a ticket holds`() {
        ScriptedImapServer(scripted()).use { server ->
            val ids = cache(TRASH)
            val repository = repository(settingsWith("markReadOnDelete"))
            val ticket = runBlocking { repository.beginRemovalAll(ACCOUNT, ids) }!!

            runBlocking { repository.deleteAll(credentials(server), ticket.emailIds) }

            assertEquals(
                listOf("UID STORE 7 +FLAGS (\\Seen)", "UID STORE 8 +FLAGS (\\Seen)"),
                server.issued("UID STORE"),
            )
        }
    }

    @Test fun `mark-read-on-move fires on a selection a ticket holds`() {
        ScriptedImapServer(scripted()).use { server ->
            val ids = cache(INBOX)
            val repository = repository(settingsWith("markReadOnMove"))
            val ticket = runBlocking { repository.beginRemovalAll(ACCOUNT, ids) }!!

            runBlocking {
                repository.moveAllToMailbox(credentials(server), ticket.emailIds, ARCHIVE, mapOf(INBOX to NUMBERING))
            }

            assertEquals(
                "and the \\Seen store goes out BEFORE the move, or the move takes the UID with it",
                listOf("UID STORE 7 +FLAGS (\\Seen)", "UID STORE 8 +FLAGS (\\Seen)"),
                server.issued("UID STORE"),
            )
            assertEquals(listOf("UID MOVE 7:8 \"Archive\""), server.issued("UID MOVE"))
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /** A server holding UIDs 7 and 8, stating [NUMBERING], listing the three folders by their
     *  RFC 6154 attributes, and answering a UID MOVE with a covering COPYUID (the only thing that
     *  proves to the mover that a message left its folder). */
    private fun scripted(): (String, String) -> String {
        return { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
                line.startsWith("LIST") || line.startsWith("LSUB") ->
                    "* LIST (\\HasNoChildren) \".\" \"$INBOX\"\r\n" +
                        "* LIST (\\HasNoChildren \\Archive) \".\" \"$ARCHIVE\"\r\n" +
                        "* LIST (\\HasNoChildren \\Trash) \".\" \"$TRASH\"\r\n$tag OK listed\r\n"
                line.startsWith("SELECT") ->
                    "* 2 EXISTS\r\n* OK [UIDVALIDITY $NUMBERING] ok\r\n" +
                        "* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("UID MOVE") -> "$tag OK [COPYUID $NUMBERING 7:8 100:101] done\r\n"
                else -> "$tag OK done\r\n"
            }
        }
    }

    /**
     * A [SettingsRepository] with one toggle on. The class takes a Context and reads a DataStore,
     * and there is no Robolectric in this module: it is allocated without running its constructor
     * and the one flow the path under test reads is planted in its field. Left null, as every other
     * harness leaves it, each of these paths returns before it reads anything and proves nothing.
     */
    private fun settingsWith(toggle: String): SettingsRepository {
        val settings = allocate(SettingsRepository::class.java)
        SettingsRepository::class.java.getDeclaredField(toggle)
            .apply { isAccessible = true }
            .set(settings, flowOf(true))
        return settings
    }

    private companion object {
        const val ACCOUNT = "acc1"
        const val INBOX = "INBOX"
        const val ARCHIVE = "Archive"
        const val TRASH = "Trash"
        const val NUMBERING = 42L
        val UIDS = listOf(7L, 8L)
        val ROLES = mapOf(INBOX to "inbox", ARCHIVE to "archive", TRASH to "trash")

        /** An instance built without its constructor, for a class this module cannot construct. */
        @Suppress("UNCHECKED_CAST")
        fun <T> allocate(type: Class<T>): T {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocateInstance = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocateInstance.invoke(unsafe, type) as T
        }

        /** An [OAuthTokenRefresher] no test here can build and none of these paths ever reaches. */
        fun unusedTokenRefresher(): OAuthTokenRefresher = allocate(OAuthTokenRefresher::class.java)
    }
}
