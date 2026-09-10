package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxEntity
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

        override fun credentials(id: String): AccountCredentials? {
            super.credentials(id)
            return known?.takeIf { it.id == id }
        }
    }

    private class RecordingImap(
        store: RecordingAccountStorage,
        /** What `LIST` says the account holds: paths and the server's real delimiter. */
        private val listing: List<ImapFolder> = emptyList(),
        /** The rows a folder listing brings back, which the cache is then handed whole. */
        private val listed: List<MailboxEntity> = emptyList(),
    ) : ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)) {
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
        }

        override suspend fun deleteFolder(credentials: AccountCredentials, path: String) {
            deleted += path
        }

        override suspend fun listImapFolders(credentials: AccountCredentials): List<ImapFolder> = listing

        override suspend fun loadFolder(
            credentials: AccountCredentials,
            requestedMailboxId: String?,
            limit: Int,
            onlySubscribed: Boolean,
            onPage: suspend (List<EmailEntity>) -> Unit,
            cachedPreviewsFor: (suspend (List<String>, Long?) -> Map<String, String>)?,
        ): ImapFolderLoad {
            subscriptionAsked += onlySubscribed
            return ImapFolderLoad(listed, "INBOX", "Inbox", 0, "Alex", ImapFolderWalk(emptyList(), moved = false))
        }
    }

    /** The one edge `MailFolders` reaches the repository through. */
    private class RecordingHost(private val session: MailRepository.JmapAccountSession? = null) : MailFolderHost {
        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession =
            session ?: error("the JMAP session seam was reached by a path that should not need one")
    }

    /** Folder-cache writes, recorded rather than executed. */
    private fun mailboxDaoRecording(
        replaced: MutableList<Pair<String, List<MailboxEntity>>>,
        hidden: MutableList<Pair<String, List<String>>>,
    ): MailboxDao =
        Proxy.newProxyInstance(MailboxDao::class.java.classLoader, arrayOf(MailboxDao::class.java)) { _, method, args ->
            when (method.name) {
                "replaceAll" -> {
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

    private fun folder(path: String, delimiter: String) =
        ImapFolder(name = path.substringAfterLast(delimiter), path = path, role = null, delimiter = delimiter)

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
    }

    private companion object {
        const val ACCOUNT = "account-1"
    }
}
