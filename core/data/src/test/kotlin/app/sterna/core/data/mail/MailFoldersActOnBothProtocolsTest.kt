package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.withRenamedSpecialFolder
import app.sterna.core.data.account.withSpecialFolder
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapFolder
import app.sterna.core.imap.ImapFolderWalk
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.model.JmapAccount
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * `MailFolders` RUN, on both protocols, against a recorded IMAP seam and a JMAP server that
 * answers. Creating, renaming and deleting a folder were held by source lints alone, which read the
 * text of a decision and cannot take either branch of it.
 *
 * What is pinned is what leaves the process: the path an IMAP create composes and the `parentId` a
 * JMAP create carries, the path a rename sends with the two re-keys it owes the store (#16, #185),
 * the order a delete destroys a subtree in and the list it hands its caller back, and which boolean
 * reaches the folder listing (#174). Every expected value is written out; nothing here recomputes
 * the rule that produced it.
 *
 * What this does NOT cover:
 * - The undo window's `hideMailboxesLocally`, one DAO call with no decision in it.
 * - Modified UTF-7. The seam is entered with Unicode paths and the encoding happens at the socket,
 *   below every stand-in here.
 * - What the cache does with the rows a refresh writes: `replaceAll` is recorded, never executed.
 */
class MailFoldersActOnBothProtocolsTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    /** One JMAP request the server received, as the method it called and the arguments it carried. */
    private val received = mutableListOf<Pair<String, JsonObject>>()

    /** What the JMAP server answers a `Mailbox/get` with, as the raw `list` of the response. */
    private var mailboxList = "[]"

    private val store = StoreAnswering()

    /** A store that can hand an account back, so `refreshFolderList` gets past its first line. */
    private class StoreAnswering : RecordingAccountStorage() {
        var known: AccountCredentials? = null

        /** The records a special-folder write reads its previous choice off. */
        var records: List<StoredAccount> = emptyList()

        /** The special-folder choices are read off the record, as `AccountStore` reads them, so
         *  a reading taken after a write to the record carries that write. The rest is looser
         *  than production, which also answers null on an id it does not hold and on a secret
         *  that will not decrypt. */
        override fun credentials(id: String): AccountCredentials? {
            super.credentials(id)
            val record = records.firstOrNull { it.id == id }
            return known?.takeIf { it.id == id }
                ?.let { if (record == null) it else it.copy(specialFolders = record.specialFolders) }
        }

        override fun accounts(): List<StoredAccount> {
            super.accounts()
            return records
        }

        /** Really applied, so a write that reads the previous choice too late reads its own. */
        override fun setSpecialFolder(id: String, role: String, folderId: String?) {
            super.setSpecialFolder(id, role, folderId)
            records = withSpecialFolder(records, id, role, folderId)
        }

        /** Really applied as well, so what a rename re-keys is there for whatever reads next. */
        override fun replaceSpecialFolder(id: String, oldId: String, newId: String, delimiter: String) {
            super.replaceSpecialFolder(id, oldId, newId, delimiter)
            records = withRenamedSpecialFolder(records, id, oldId, newId, delimiter)
        }
    }

    private class RecordingImap(
        store: RecordingAccountStorage,
        /** What `LIST` says the account holds at the start: paths and the server's real delimiter. */
        listing: List<ImapFolder> = emptyList(),
        /** The rows a folder listing brings back, which the cache is then handed whole. */
        private val listed: List<MailboxEntity> = emptyList(),
        /** The phone is offline: the listing throws where the socket would. */
        private val listingFails: Boolean = false,
        /** Decide each listed folder's role off the account object the listing was called with, as
         *  `ImapMailService` does, instead of handing [listed] over: which choices that object
         *  carries is then readable in what the cache is written. */
        private val rolesFromChoices: Boolean = false,
    ) : ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)) {
        /** What the server holds now: a rename moves the folder and its subtree here, as a server
         *  does, so no listing that follows one still carries the path left behind. */
        private var onServer = listing

        val created = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()

        /** The `onlySubscribed` of each listing that went out, the one value #174 turns on. */
        val subscriptionAsked = mutableListOf<Boolean>()

        override suspend fun createFolder(credentials: AccountCredentials, path: String) {
            created += path
        }

        override suspend fun renameFolder(credentials: AccountCredentials, oldPath: String, newPath: String) {
            renamed += oldPath to newPath
            onServer = onServer.map {
                when {
                    it.path == oldPath -> it.copy(path = newPath, name = newPath.substringAfterLast(it.delimiter))
                    it.path.startsWith(oldPath + it.delimiter) ->
                        it.copy(path = newPath + it.path.removePrefix(oldPath))
                    else -> it
                }
            }
        }

        override suspend fun deleteFolder(credentials: AccountCredentials, path: String) {
            deleted += path
        }

        override suspend fun listImapFolders(credentials: AccountCredentials): List<ImapFolder> = onServer

        override suspend fun loadFolder(
            credentials: AccountCredentials,
            requestedMailboxId: String?,
            limit: Int,
            onlySubscribed: Boolean,
            onPage: suspend (List<EmailEntity>) -> Unit,
            cachedPreviewsFor: (suspend (List<String>, Long?) -> Map<String, String>)?,
            delta: ImapFolderDelta?,
        ): ImapFolderLoad {
            subscriptionAsked += onlySubscribed
            if (listingFails) throw java.io.IOException("offline")
            val mailboxes = if (rolesFromChoices) {
                val chosen = honouredSpecialFolders(credentials.specialFolders, onServer.map { it.roleCandidate() })
                onServer.mapIndexed { index, folder -> imapMailboxEntity(credentials.id, folder, index, chosen) }
            } else {
                listed
            }
            return ImapFolderLoad(mailboxes, "INBOX", "Inbox", 0, "Alex", ImapFolderWalk(emptyList(), moved = false))
        }
    }

    /** The one edge `MailFolders` reaches the repository through. */
    private class RecordingHost(private val session: MailRepository.JmapAccountSession? = null) : MailFolderHost {
        var syncStateResets = 0

        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession =
            session ?: error("the JMAP session seam was reached by a path that should not need one")

        override fun resetSyncState() {
            syncStateResets++
        }
    }

    /** Folder-cache writes, recorded rather than executed. */
    private fun mailboxDaoRecording(
        replaced: MutableList<Pair<String, List<MailboxEntity>>>,
        hidden: MutableList<Pair<String, List<String>>>,
    ): MailboxDao =
        Proxy.newProxyInstance(MailboxDao::class.java.classLoader, arrayOf(MailboxDao::class.java)) { _, method, args ->
            when (method.name) {
                "replaceAll", "replaceAllKeepingUnread" -> {
                    @Suppress("UNCHECKED_CAST")
                    replaced += (args[0] as String) to (args[1] as List<MailboxEntity>)
                    Unit
                }
                "deleteByIds" -> {
                    @Suppress("UNCHECKED_CAST")
                    hidden += (args[0] as String) to (args[1] as List<String>)
                    Unit
                }
                else -> error("MailboxDao.${method.name} was not expected here.")
            }
        } as MailboxDao

    /** The folder table as a special-folder write sees it: [rows] going out, every role written
     *  down in the order the write issued it. [state] is the table itself, carrying those writes,
     *  so a reading that follows a write sees it, as the real one does. */
    private fun mailboxDaoRoles(
        rows: List<MailboxIdRole>,
        written: MutableList<Pair<String, String?>>,
        replaced: MutableList<Pair<String, List<MailboxEntity>>> = mutableListOf(),
        state: MutableList<MailboxIdRole> = rows.toMutableList(),
    ): MailboxDao =
        Proxy.newProxyInstance(MailboxDao::class.java.classLoader, arrayOf(MailboxDao::class.java)) { _, method, args ->
            fun write(id: String, role: String?) {
                written += id to role
                val at = state.indexOfFirst { it.id == id }
                if (at >= 0) state[at] = MailboxIdRole(id, role)
            }
            when (method.name) {
                "replaceAll", "replaceAllKeepingUnread" -> {
                    @Suppress("UNCHECKED_CAST")
                    replaced += (args[0] as String) to (args[1] as List<MailboxEntity>)
                    Unit
                }
                "rolesForAccount" -> state.toList()
                "applyRoles" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args[1] as List<MailboxIdRole>).forEach { write(it.id, it.role) }
                    Unit
                }
                "setRole" -> {
                    write(args[1] as String, args[2] as String?)
                    Unit
                }
                else -> error("MailboxDao.${method.name} was not expected here.")
            }
        } as MailboxDao

    /** The cached messages of a folder being deleted, emptied rather than really written. */
    private fun emailDaoRecording(emptied: MutableList<Pair<String, String>>): EmailDao =
        Proxy.newProxyInstance(EmailDao::class.java.classLoader, arrayOf(EmailDao::class.java)) { _, method, args ->
            when (method.name) {
                "replaceMailbox" -> {
                    emptied += (args[0] as String) to (args[1] as String)
                    Unit
                }
                else -> error("EmailDao.${method.name} was not expected here.")
            }
        } as EmailDao

    private fun folders(
        imap: ImapMailService,
        mailboxDao: MailboxDao = unusableDao(),
        emailDao: EmailDao = unusableDao(),
        host: MailFolderHost = RecordingHost(),
    ) = MailFolders(
        client = JmapClient(),
        imap = imap,
        mailboxDao = mailboxDao,
        emailDao = emailDao,
        accountStore = store,
        host = host,
    )

    private fun credentials(protocol: MailProtocol) = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "alex@example.test",
        password = "secret",
        id = ACCOUNT,
        protocol = protocol,
        authType = AuthType.BASIC,
    )

    private fun account(
        specialFolders: Map<String, String> = emptyMap(),
        protocol: MailProtocol = MailProtocol.JMAP,
    ) = StoredAccount(
        id = ACCOUNT,
        server = "imap.example.test",
        username = "alex@example.test",
        protocol = protocol,
        specialFolders = specialFolders,
    )

    private fun folder(path: String, delimiter: String, role: String? = null) =
        ImapFolder(name = path.substringAfterLast(delimiter), path = path, role = role, delimiter = delimiter)

    private fun row(id: String, subscribed: Boolean) = MailboxEntity(
        accountId = ACCOUNT,
        id = id,
        name = id,
        role = null,
        sortOrder = 0,
        totalEmails = 0,
        unreadEmails = 0,
        isSubscribed = subscribed,
    )

    private fun storeCalls(name: String): List<Map<String, Any?>> =
        store.calls.filter { it.first == name }.map { it.second }

    // ---- IMAP

    /**
     * A folder created under a parent goes out as ONE path, built with the delimiter the parent's
     * own id carries: IMAP nests by path and has no `parentId` field, so a create that sent the
     * leaf alone would make a top-level folder beside the parent the user asked to nest under.
     */
    @Test fun `an imap create composes the parent path with the delimiter that parent uses`() {
        val imap = RecordingImap(store)
        val folders = folders(imap, mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()))

        runBlocking { folders.createFolder(credentials(MailProtocol.IMAP), " Trips ", parentId = "Archive.2024") }

        assertEquals(listOf("Archive.2024.Trips"), imap.created)
    }

    /** And a folder created with no parent is its trimmed name, nothing prefixed to it. */
    @Test fun `an imap create without a parent sends the bare name`() {
        val imap = RecordingImap(store)
        val folders = folders(imap, mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()))

        runBlocking { folders.createFolder(credentials(MailProtocol.IMAP), " Trips ", parentId = null) }

        assertEquals(listOf("Trips"), imap.created)
    }

    /**
     * A rename sends the path rebuilt under the folder's own parent, and re-keys BOTH per-folder
     * registries with the delimiter the server stated: on IMAP the id IS the path, so a rename that
     * re-keyed neither loses the watch (#16) and the drawer's fold (#185) without a word.
     *
     * The folder is the Dovecot case where the server's answer and a guess read off the path
     * disagree: the hierarchy delimiter is `.` and the folder's own NAME holds a `/`. Guessing from
     * the path gives `/`, a parent of `INBOX.Client`, and a rename that moves the folder somewhere
     * the user never asked for, with both registries re-keyed onto that wrong path.
     */
    @Test fun `an imap rename moves the path and re-keys the watch flags and the folds`() {
        val imap = RecordingImap(store, listing = listOf(folder("INBOX.Client/Acme", ".")))
        val folders = folders(imap, mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()))

        val newId = runBlocking {
            folders.renameFolder(credentials(MailProtocol.IMAP), "INBOX.Client/Acme", " Acme Corp ")
        }

        assertEquals("INBOX.Acme Corp", newId)
        assertEquals(listOf("INBOX.Client/Acme" to "INBOX.Acme Corp"), imap.renamed)
        val rekey = mapOf(
            "id" to ACCOUNT,
            "oldId" to "INBOX.Client/Acme",
            "newId" to "INBOX.Acme Corp",
            "delimiter" to ".",
        )
        assertEquals(listOf(rekey), storeCalls("replaceWatchedFolder"))
        assertEquals(listOf(rekey), storeCalls("replaceCollapsedFolder"))
    }

    /**
     * A delete takes the subtree deepest first (a server refuses to destroy a parent that still has
     * children), and hands its caller back every path it destroyed: `FolderDeleteWorker` clears the
     * cache from that list, and a shortened one leaves rows for folders that are gone.
     *
     * `Archive.2024b` is here to be spared: it starts with the target's name and is not under it.
     */
    @Test fun `an imap delete destroys the subtree deepest first and answers the whole of it`() {
        val imap = RecordingImap(
            store,
            listing = listOf(
                folder("Archive", "."),
                folder("Archive.2024", "."),
                folder("Archive.2024b", "."),
                folder("Archive.2024.Trips", "."),
                folder("Archive.2024.Trips.Photos", "."),
            ),
        )
        val emptied = mutableListOf<Pair<String, String>>()
        val folders = folders(
            imap,
            mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()),
            emailDao = emailDaoRecording(emptied),
        )

        val destroyed = runBlocking { folders.deleteFolder(credentials(MailProtocol.IMAP), "Archive.2024") }

        val subtree = listOf("Archive.2024.Trips.Photos", "Archive.2024.Trips", "Archive.2024")
        assertEquals(subtree, imap.deleted)
        assertEquals(subtree, destroyed)
        assertEquals(subtree.map { ACCOUNT to it }, emptied)
        assertEquals(
            subtree.map { mapOf("id" to ACCOUNT, "folderId" to it, "watched" to false) },
            storeCalls("setFolderWatched"),
        )
    }

    /**
     * The caller's own value is the one the listing goes out under (#174), never a second reading
     * of the store: the store here says `false` while the toggle asks for `true`, which is exactly
     * the ordering that made the switch inert. And the cache is handed every row the server listed,
     * the unsubscribed one included: hiding is the drawer's job, deleting rows is not.
     */
    @Test fun `the value the caller states reaches the listing, not the one the store holds`() {
        val imap = RecordingImap(store, listed = listOf(row("Kept", subscribed = true), row("Hidden", subscribed = false)))
        val replaced = mutableListOf<Pair<String, List<MailboxEntity>>>()
        store.known = credentials(MailProtocol.IMAP)
        val folders = folders(imap, mailboxDao = mailboxDaoRecording(replaced, mutableListOf()))

        runBlocking { folders.refreshFolderList(ACCOUNT, onlySubscribed = true) }

        assertEquals(listOf(true), imap.subscriptionAsked)
        assertEquals(emptyList<Map<String, Any?>>(), storeCalls("showOnlySubscribedFolders"))
        assertEquals(listOf("Kept", "Hidden"), replaced.single().second.map { it.id })
    }

    /** The other door has no opinion of its own and asks the store, which said `false` here. */
    @Test fun `a create refreshes under the setting the store holds`() {
        val imap = RecordingImap(store)
        val folders = folders(imap, mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()))

        runBlocking { folders.createFolder(credentials(MailProtocol.IMAP), "Trips") }

        assertEquals(listOf(false), imap.subscriptionAsked)
        assertEquals(listOf(mapOf("id" to ACCOUNT)), storeCalls("showOnlySubscribedFolders"))
    }

    /**
     * A rename re-keys the special-folder choices with the other three registries (#227): on IMAP
     * the id a role is pinned to IS the folder's path, so a rename left the role pointing at a
     * path the server no longer carries, and the account fell back on the elected folder in
     * silence: the user's Archive would go on being the server's.
     */
    @Test fun `an imap rename re-keys the special folder choices too`() {
        val imap = RecordingImap(store, listing = listOf(folder("INBOX.Client/Acme", ".")))
        val folders = folders(imap, mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()))

        runBlocking { folders.renameFolder(credentials(MailProtocol.IMAP), "INBOX.Client/Acme", " Acme Corp ") }

        assertEquals(
            listOf(
                mapOf(
                    "id" to ACCOUNT,
                    "oldId" to "INBOX.Client/Acme",
                    "newId" to "INBOX.Acme Corp",
                    "delimiter" to ".",
                ),
            ),
            storeCalls("replaceSpecialFolder"),
        )
    }

    /**
     * And the refresh that follows that re-key asks the listing under the account as the re-key
     * left it, not as the caller handed it over: the role a cache row carries is decided from the
     * choices the account object carries, and one read before the re-key still names the path the
     * rename left behind. The server no longer lists that path, the choice cannot be honoured, and
     * the folder the listing elects on its own takes the role back in the cache, and the drawer then
     * paints the role on a folder the mail no longer goes to, until some later sync.
     *
     * `ProjectA.Sub` is designated for the junk, `ProjectA` is what gets renamed, and `Junk Mail`
     * is the folder the server's own listing elects to that role.
     */
    @Test fun `the refresh after an imap rename roles the designated folder under its new path`() {
        val replaced = mutableListOf<Pair<String, List<MailboxEntity>>>()
        val pinned = credentials(MailProtocol.IMAP).copy(specialFolders = mapOf("junk" to "ProjectA.Sub"))
        store.known = pinned
        store.records = listOf(account(mapOf("junk" to "ProjectA.Sub"), protocol = MailProtocol.IMAP))
        val imap = RecordingImap(
            store,
            listing = listOf(
                folder("INBOX", ".", role = "inbox"),
                folder("Junk Mail", ".", role = "junk"),
                folder("ProjectA", "."),
                folder("ProjectA.Sub", "."),
            ),
            rolesFromChoices = true,
        )
        val folders = folders(imap, mailboxDao = mailboxDaoRecording(replaced, mutableListOf()))

        runBlocking { folders.renameFolder(pinned, "ProjectA", " ProjectB ") }

        assertEquals(
            listOf("INBOX" to "inbox", "Junk Mail" to "~junk", "ProjectB" to null, "ProjectB.Sub" to "junk"),
            replaced.single().second.map { it.id to it.role },
        )
    }

    /**
     * A rename refreshes whether the record can be read back or not: nothing about the listing is
     * owed to an account having designated a folder, and a store answering nothing is a real state
     * (an account deleted under the gesture, a keystore that will not open).
     *
     * A boundary, green whichever object the refresh is given: it holds the fallback alone.
     */
    @Test fun `an imap rename refreshes even when the store hands no account back`() {
        val replaced = mutableListOf<Pair<String, List<MailboxEntity>>>()
        val imap = RecordingImap(
            store,
            listing = listOf(folder("INBOX.Client/Acme", ".")),
            listed = listOf(row("INBOX", subscribed = true)),
        )
        val folders = folders(imap, mailboxDao = mailboxDaoRecording(replaced, mutableListOf()))

        runBlocking { folders.renameFolder(credentials(MailProtocol.IMAP), "INBOX.Client/Acme", " Acme Corp ") }

        assertEquals(listOf(false), imap.subscriptionAsked)
        assertEquals(listOf(ACCOUNT to listOf(row("INBOX", subscribed = true))), replaced)
    }

    /**
     * And a delete forgets them, over the whole destroyed subtree. A role key left on a destroyed
     * path makes a folder later created there be born the bin: deletes would land in it, it would
     * drop out of every search, and emptying the trash would destroy what the user filed there.
     */
    @Test fun `an imap delete forgets the special folder choices of the whole subtree`() {
        val imap = RecordingImap(
            store,
            listing = listOf(
                folder("Archive", "."),
                folder("Archive.2024", "."),
                folder("Archive.2024b", "."),
                folder("Archive.2024.Trips", "."),
            ),
        )
        val folders = folders(
            imap,
            mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()),
            emailDao = emailDaoRecording(mutableListOf()),
        )

        runBlocking { folders.deleteFolder(credentials(MailProtocol.IMAP), "Archive.2024") }

        assertEquals(
            listOf(mapOf("id" to ACCOUNT, "folderIds" to listOf("Archive.2024.Trips", "Archive.2024"))),
            storeCalls("forgetSpecialFolders"),
        )
    }

    /**
     * The chosen folder order follows a rename like the other registries: IMAP ids are paths, so a
     * rename left un-keyed drops the folder back to the listing's place.
     */
    @Test fun `an imap rename re-keys the folder order`() {
        val imap = RecordingImap(store, listing = listOf(folder("Archive", "."), folder("Archive.2024", ".")))
        val folders = folders(imap, mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()))

        runBlocking { folders.renameFolder(credentials(MailProtocol.IMAP), "Archive.2024", " 2025 ") }

        assertEquals(
            listOf(mapOf("id" to ACCOUNT, "oldId" to "Archive.2024", "newId" to "Archive.2025", "delimiter" to ".")),
            storeCalls("replaceFolderOrder"),
        )
    }

    /** And a delete forgets the whole destroyed subtree: a path left behind hands its place to the
     *  next folder created there. */
    @Test fun `an imap delete forgets the folder order of the whole subtree`() {
        val imap = RecordingImap(
            store,
            listing = listOf(folder("Archive", "."), folder("Archive.2024", "."), folder("Archive.2024.Trips", ".")),
        )
        val folders = folders(
            imap,
            mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()),
            emailDao = emailDaoRecording(mutableListOf()),
        )

        runBlocking { folders.deleteFolder(credentials(MailProtocol.IMAP), "Archive.2024") }

        assertEquals(
            listOf(mapOf("id" to ACCOUNT, "folderIds" to listOf("Archive.2024.Trips", "Archive.2024"))),
            storeCalls("forgetFolderOrder"),
        )
    }

    // ---- the choice itself (#227)

    /**
     * Pinning writes the record AND the folder table, in that order, and drops the warm JMAP
     * session: the drawer, the search and the delete all read the cached role, so a choice left to
     * the next network refresh is a setting the app visibly ignores, and the warm session holds a
     * role table built from the choice that was just replaced.
     */
    @Test fun `pinning a folder re-roles the cache at once and drops the warm session`() {
        val written = mutableListOf<Pair<String, String?>>()
        val host = RecordingHost()
        val folders = folders(
            RecordingImap(store),
            mailboxDao = mailboxDaoRoles(
                rows = listOf(
                    MailboxIdRole("Corbeille", null),
                    MailboxIdRole("INBOX", "inbox"),
                    MailboxIdRole("Trash", "trash"),
                ),
                written = written,
            ),
            host = host,
        )

        runBlocking { folders.setSpecialFolder(ACCOUNT, "trash", "Corbeille") }

        assertEquals(
            listOf(mapOf("id" to ACCOUNT, "role" to "trash", "folderId" to "Corbeille")),
            storeCalls("setSpecialFolder"),
        )
        assertEquals(listOf("Trash" to "~trash", "Corbeille" to "trash"), written)
        assertEquals(1, host.syncStateResets)
    }

    /**
     * Unpinning reads the choice being undone off the record before overwriting it: which folder
     * the role leaves is the one the account NAMED, never whoever happens to answer the role now.
     */
    @Test fun `unpinning strips the folder the record named and hands the claim back`() {
        val written = mutableListOf<Pair<String, String?>>()
        store.records = listOf(account(mapOf("trash" to "Corbeille")))
        val folders = folders(
            RecordingImap(store),
            mailboxDao = mailboxDaoRoles(
                rows = listOf(
                    MailboxIdRole("Corbeille", "trash"),
                    MailboxIdRole("INBOX", "inbox"),
                    MailboxIdRole("Trash", "~trash"),
                ),
                written = written,
            ),
            host = RecordingHost(),
        )

        runBlocking { folders.setSpecialFolder(ACCOUNT, "trash", null) }

        assertEquals(listOf("Corbeille" to null, "Trash" to "trash"), written)
    }

    /**
     * The second designation of one role, the gesture the screen makes whenever the user changes
     * her mind: the folder she had designated goes back to carrying no role, never to the mark of
     * a lost claim, which would take an ordinary mail folder out of every search result.
     */
    @Test fun `designating a second folder lets the first one go without marking it`() {
        val written = mutableListOf<Pair<String, String?>>()
        store.records = listOf(account(mapOf("trash" to "Projets")))
        val folders = folders(
            RecordingImap(store),
            mailboxDao = mailboxDaoRoles(
                rows = listOf(
                    MailboxIdRole("Corbeille", null),
                    MailboxIdRole("INBOX", "inbox"),
                    MailboxIdRole("Projets", "trash"),
                ),
                written = written,
            ),
            host = RecordingHost(),
        )

        runBlocking { folders.setSpecialFolder(ACCOUNT, "trash", "Corbeille") }

        assertEquals(listOf("Projets" to null, "Corbeille" to "trash"), written)
    }

    /**
     * The folder already carrying a role, designated for a second one. A cache row carries one
     * role, so a record letting the first role stand is the whole defect: `specialRole` settles
     * that row on the first of `SPECIAL_FOLDER_ROLES` and marks the server's own bin with the lost
     * claim the standing `trash` key takes from it, so no row answers `trash` at all. On IMAP
     * `MailRepository.cachedTrashMailboxId` stops there, no name table is consulted, and the delete
     * creates a bin server-side while the screen reads the designated folder back (#227).
     */
    @Test fun `designating a folder for a second role takes the first role off it`() {
        val written = mutableListOf<Pair<String, String?>>()
        val state = mutableListOf(
            MailboxIdRole("Mein Archiv", "archive"),
            MailboxIdRole("Deleted Items", "trash"),
            MailboxIdRole("INBOX", "inbox"),
        )
        store.records = listOf(account(mapOf("archive" to "Mein Archiv"), protocol = MailProtocol.IMAP))
        val folders = folders(
            RecordingImap(store),
            mailboxDao = mailboxDaoRoles(rows = state.toList(), written = written, state = state),
            host = RecordingHost(),
        )

        runBlocking { folders.setSpecialFolder(ACCOUNT, "trash", "Mein Archiv") }

        assertEquals(
            listOf("Mein Archiv" to null, "Deleted Items" to "~trash", "Mein Archiv" to "trash"),
            written,
        )
        assertEquals(
            listOf(
                MailboxIdRole("Mein Archiv", "trash"),
                MailboxIdRole("Deleted Items", "~trash"),
                MailboxIdRole("INBOX", "inbox"),
            ),
            state,
        )
        assertEquals(mapOf("trash" to "Mein Archiv"), store.records.first().specialFolders)
    }

    /**
     * And the other way round, the release being symmetric where the role order is not: the same
     * folder designated for the bin first, then for the archives. Releasing only the roles listed
     * before the one taken leaves the `trash` key standing on a folder `specialRole` settles on
     * `archive`, which is the defect above with its two roles swapped, and the server's own bin
     * stays marked with a claim nobody takes back (#227).
     */
    @Test fun `designating a folder for an earlier role takes the later one off it too`() {
        val written = mutableListOf<Pair<String, String?>>()
        val state = mutableListOf(
            MailboxIdRole("Mein Archiv", "trash"),
            MailboxIdRole("Deleted Items", "~trash"),
            MailboxIdRole("Archive", "archive"),
            MailboxIdRole("INBOX", "inbox"),
        )
        store.records = listOf(account(mapOf("trash" to "Mein Archiv"), protocol = MailProtocol.IMAP))
        val folders = folders(
            RecordingImap(store),
            mailboxDao = mailboxDaoRoles(rows = state.toList(), written = written, state = state),
            host = RecordingHost(),
        )

        runBlocking { folders.setSpecialFolder(ACCOUNT, "archive", "Mein Archiv") }

        assertEquals(
            listOf(
                "Mein Archiv" to null,
                "Deleted Items" to "trash",
                "Archive" to null,
                "Mein Archiv" to "archive",
            ),
            written,
        )
        assertEquals(
            listOf(
                MailboxIdRole("Mein Archiv", "archive"),
                MailboxIdRole("Deleted Items", "trash"),
                MailboxIdRole("Archive", null),
                MailboxIdRole("INBOX", "inbox"),
            ),
            state,
        )
        assertEquals(mapOf("archive" to "Mein Archiv"), store.records.first().specialFolders)
    }

    /** Designating the folder a role already names is not a way to strip it: the roles released are
     *  the other ones, so the gesture writes no role at all. */
    @Test fun `designating the folder a role already names writes no role`() {
        val written = mutableListOf<Pair<String, String?>>()
        store.records = listOf(account(mapOf("trash" to "Corbeille"), protocol = MailProtocol.IMAP))
        val folders = folders(
            RecordingImap(store),
            mailboxDao = mailboxDaoRoles(
                rows = listOf(MailboxIdRole("Corbeille", "trash"), MailboxIdRole("INBOX", "inbox")),
                written = written,
            ),
            host = RecordingHost(),
        )

        runBlocking { folders.setSpecialFolder(ACCOUNT, "trash", "Corbeille") }

        assertEquals(emptyList<Pair<String, String?>>(), written)
    }

    /**
     * And the inbox is refused whatever its cached row says, on an account whose ids are paths: a
     * server announcing `\Archive` on INBOX takes it out of the role election, and a guard that
     * only read the cached role would hand the inbox `trash`: incoming mail out of every search
     * and every delete aimed at it.
     */
    @Test fun `the inbox of an imap account is refused by its path`() {
        val written = mutableListOf<Pair<String, String?>>()
        store.records = listOf(account(protocol = MailProtocol.IMAP))
        val folders = folders(
            RecordingImap(store),
            mailboxDao = mailboxDaoRoles(
                rows = listOf(MailboxIdRole("INBOX", "archive"), MailboxIdRole("Trash", "trash")),
                written = written,
            ),
            host = RecordingHost(),
        )

        runBlocking { folders.setSpecialFolder(ACCOUNT, "trash", "INBOX") }

        assertEquals(emptyList<Pair<String, String?>>(), written)
    }

    /**
     * Handing `sent` back to the automatic rule leaves the cache with NO holder for it: only
     * `trash` and `junk` carry a lost claim, so the delta has nobody to give the role back to and
     * the drawer would lose its Sent entry with nothing to bring it back. The listing goes back out
     * for exactly that, and what it answers is written whole.
     */
    @Test fun `handing a role back to the automatic rule asks the server for the listing again`() {
        val written = mutableListOf<Pair<String, String?>>()
        val replaced = mutableListOf<Pair<String, List<MailboxEntity>>>()
        store.known = credentials(MailProtocol.IMAP)
        store.records = listOf(account(mapOf("sent" to "Envoyes"), protocol = MailProtocol.IMAP))
        val imap = RecordingImap(store, listed = listOf(row("Sent", true)))
        val folders = folders(
            imap,
            mailboxDao = mailboxDaoRoles(
                rows = listOf(MailboxIdRole("Envoyes", "sent"), MailboxIdRole("INBOX", "inbox")),
                written = written,
                replaced = replaced,
            ),
            host = RecordingHost(),
        )

        runBlocking { folders.setSpecialFolder(ACCOUNT, "sent", null) }

        assertEquals(listOf("Envoyes" to null), written)
        assertEquals(listOf(false), imap.subscriptionAsked)
        assertEquals(listOf(ACCOUNT to listOf(row("Sent", true))), replaced)
    }

    /** And a listing that never comes back takes nothing down with it: the choice is written, the
     *  cache is re-roled, and the gesture returns rather than throwing at its caller. */
    @Test fun `a listing that fails leaves the choice and the re-roled cache standing`() {
        val written = mutableListOf<Pair<String, String?>>()
        store.known = credentials(MailProtocol.IMAP)
        store.records = listOf(account(protocol = MailProtocol.IMAP))
        val folders = folders(
            RecordingImap(store, listingFails = true),
            mailboxDao = mailboxDaoRoles(
                rows = listOf(MailboxIdRole("Corbeille", null), MailboxIdRole("INBOX", "inbox")),
                written = written,
            ),
            host = RecordingHost(),
        )

        runBlocking { folders.setSpecialFolder(ACCOUNT, "trash", "Corbeille") }

        assertEquals(
            listOf(mapOf("id" to ACCOUNT, "role" to "trash", "folderId" to "Corbeille")),
            storeCalls("setSpecialFolder"),
        )
        assertEquals(listOf("Corbeille" to "trash"), written)
    }

    // ---- JMAP

    private fun serveJmap() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val call = Json.parseToJsonElement(request.body.readUtf8())
                    .jsonObject["methodCalls"]!!.jsonArray[0].jsonArray
                val name = call[0].jsonPrimitive.content
                val args = call[1].jsonObject
                received += name to args
                val body = when {
                    name == "Mailbox/get" -> """{"methodResponses":[["Mailbox/get",{"list":$mailboxList},"c0"]]}"""
                    args.containsKey("create") ->
                        """{"methodResponses":[["Mailbox/set",{"created":{"new":{"id":"mb-new"}}},"m0"]]}"""
                    args.containsKey("update") -> {
                        val id = args["update"]!!.jsonObject.keys.single()
                        """{"methodResponses":[["Mailbox/set",{"updated":{"$id":null}},"m0"]]}"""
                    }
                    args.containsKey("destroy") -> {
                        val id = args["destroy"]!!.jsonArray.single().jsonPrimitive.content
                        """{"methodResponses":[["Mailbox/set",{"destroyed":["$id"]},"m0"]]}"""
                    }
                    else -> error("unexpected JMAP call: $name $args")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
    }

    /** A warm session handed straight over: the seam exists so no `fetchSession` is owed. */
    private fun jmapSession(): MailRepository.JmapAccountSession = MailRepository.JmapAccountSession(
        credentials = credentials(MailProtocol.JMAP),
        session = JmapSession(
            capabilities = mapOf(
                Jmap.CORE_CAPABILITY to JsonObject(emptyMap()),
                Jmap.MAIL_CAPABILITY to JsonObject(emptyMap()),
            ),
            accounts = mapOf("acc1" to JmapAccount(name = "Alex")),
            primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "acc1"),
            username = "alex",
            apiUrl = server.url("/jmap/api").toString(),
        ),
        accountId = "acc1",
        auth = BasicAuth("alex", "secret"),
        rolesToMailboxId = emptyMap(),
    )

    private fun mailbox(id: String, parentId: String? = null): String =
        """{"id":"$id","name":"$id"${parentId?.let { ""","parentId":"$it"""" }.orEmpty()}}"""

    /** The ids of the `Mailbox/set` destroys the server received, in the order they arrived. */
    private fun destroys(): List<String> = received
        .filter { it.first == "Mailbox/set" && it.second.containsKey("destroy") }
        .map { it.second["destroy"]!!.jsonArray.single().jsonPrimitive.content }

    /**
     * On JMAP the hierarchy is a field, so the parent travels as `parentId` and the name stays a
     * leaf: composing a path here would create a folder literally called `Archive.2024.Trips`.
     */
    @Test fun `a jmap create carries the parent as an id and the name as a leaf`() {
        serveJmap()
        val folders = folders(
            RecordingImap(store),
            mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()),
            host = RecordingHost(jmapSession()),
        )

        runBlocking { folders.createFolder(credentials(MailProtocol.JMAP), " Trips ", parentId = "mb-travel") }

        val created = received.first { it.first == "Mailbox/set" }
            .second["create"]!!.jsonObject["new"]!!.jsonObject
        assertEquals("Trips", created["name"]!!.jsonPrimitive.content)
        assertEquals("mb-travel", created["parentId"]!!.jsonPrimitive.content)
    }

    /**
     * A JMAP rename updates the name and gives the id back unchanged, and the two re-keys are not
     * owed: a JMAP folder id survives its name, so re-keying the registries would move the watch
     * and the fold onto an id that names nothing.
     */
    @Test fun `a jmap rename updates the name and keeps the id, re-keying nothing`() {
        serveJmap()
        val folders = folders(
            RecordingImap(store),
            mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()),
            host = RecordingHost(jmapSession()),
        )

        val newId = runBlocking { folders.renameFolder(credentials(MailProtocol.JMAP), "mb-2024", " 2025 ") }

        assertEquals("mb-2024", newId)
        val update = received.first { it.first == "Mailbox/set" }.second["update"]!!.jsonObject
        assertEquals(listOf("mb-2024"), update.keys.toList())
        assertEquals("2025", update["mb-2024"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(emptyList<Map<String, Any?>>(), storeCalls("replaceWatchedFolder"))
        assertEquals(emptyList<Map<String, Any?>>(), storeCalls("replaceCollapsedFolder"))
        assertEquals(emptyList<Map<String, Any?>>(), storeCalls("replaceSpecialFolder"))
        assertEquals(emptyList<Map<String, Any?>>(), storeCalls("replaceFolderOrder"))
    }

    /**
     * The same deepest-first order on JMAP, read off the `parentId` graph the server hands back,
     * and the same whole list answered. `mb-elsewhere` is here to be spared.
     */
    @Test fun `a jmap delete destroys the children before their parent and answers all three`() {
        serveJmap()
        mailboxList = listOf(
            mailbox("mb-parent"),
            mailbox("mb-first", parentId = "mb-parent"),
            mailbox("mb-second", parentId = "mb-parent"),
            mailbox("mb-elsewhere"),
        ).joinToString(",", "[", "]")
        val emptied = mutableListOf<Pair<String, String>>()
        val folders = folders(
            RecordingImap(store),
            mailboxDao = mailboxDaoRecording(mutableListOf(), mutableListOf()),
            emailDao = emailDaoRecording(emptied),
            host = RecordingHost(jmapSession()),
        )

        val destroyed = runBlocking { folders.deleteFolder(credentials(MailProtocol.JMAP), "mb-parent") }

        val subtree = listOf("mb-first", "mb-second", "mb-parent")
        assertEquals(subtree, destroys())
        assertEquals(subtree, destroyed)
        assertEquals(subtree.map { ACCOUNT to it }, emptied)
        assertEquals(listOf(mapOf("id" to ACCOUNT, "folderIds" to subtree)), storeCalls("forgetFolderOrder"))
    }

    private companion object {
        const val ACCOUNT = "account-1"
    }
}
