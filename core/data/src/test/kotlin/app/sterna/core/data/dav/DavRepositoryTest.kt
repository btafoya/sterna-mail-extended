package app.sterna.core.data.dav

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.DavDao
import app.sterna.core.data.db.DavObjectEntity
import app.sterna.core.data.db.DavPendingEntity
import app.sterna.core.data.db.DavStateEntity
import app.sterna.core.data.mail.MailAccountHost
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.JmapType
import app.sterna.core.jmap.model.JmapAccount
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The queue: edits show at once, go out in order with the stored state, survive a conflict, and a refusal is kept. */
class DavRepositoryTest {
    private class MemoryDao : DavDao {
        val objects = linkedMapOf<Triple<String, String, String>, DavObjectEntity>()
        val states = mutableMapOf<Pair<String, String>, String>()
        val queue = mutableListOf<DavPendingEntity>()
        private var next = 1L

        override fun watch(accountId: String, type: String): Flow<List<DavObjectEntity>> = flowOf(emptyList())
        override suspend fun all(accountId: String, type: String) = objects.values.filter { it.accountId == accountId && it.type == type }
        override fun watchGroup(accountId: String, type: String, groupId: String): Flow<List<DavObjectEntity>> = flowOf(emptyList())
        override suspend fun get(accountId: String, type: String, id: String) = objects[Triple(accountId, type, id)]
        override suspend fun inWindow(accountId: String, tasks: Boolean, fromMs: Long, toMs: Long) = emptyList<DavObjectEntity>()
        override fun watchTasks(accountId: String): Flow<List<DavObjectEntity>> = flowOf(emptyList())
        override suspend fun state(accountId: String, type: String) = states[accountId to type]
        override fun watchSupported(accountId: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun upsertObjects(rows: List<DavObjectEntity>) { rows.forEach { objects[Triple(it.accountId, it.type, it.id)] = it } }
        override suspend fun upsertState(row: DavStateEntity) { states[row.accountId to row.type] = row.state }
        override suspend fun deleteType(accountId: String, type: String) { objects.keys.removeAll { it.first == accountId && it.second == type } }
        override suspend fun deleteState(accountId: String, type: String) { states.remove(accountId to type) }
        override suspend fun deleteIds(accountId: String, type: String, ids: List<String>) { ids.forEach { objects.remove(Triple(accountId, type, it)) } }
        override suspend fun renameId(accountId: String, type: String, oldId: String, newId: String) {
            objects.remove(Triple(accountId, type, oldId))?.let { objects[Triple(accountId, type, newId)] = it.copy(id = newId) }
        }
        override suspend fun deleteObjectsOf(accountId: String) {}
        override suspend fun deleteStatesOf(accountId: String) {}
        override suspend fun deletePendingOf(accountId: String) {}
        override suspend fun insertPending(row: DavPendingEntity): Long {
            val id = if (row.id != 0L) row.id else next++
            queue += row.copy(id = id)
            queue.sortBy { it.id }
            return id
        }
        override suspend fun pending(accountId: String) = queue.filter { it.accountId == accountId && !it.failed }
        override suspend fun pendingFor(accountId: String, type: String, objectId: String) =
            pending(accountId).filter { it.type == type && it.objectId == objectId }
        override suspend fun accountsWithPending() = queue.filter { !it.failed }.map { it.accountId }.distinct()
        override fun watchPendingCount(accountId: String): Flow<Int> = flowOf(0)
        override fun watchFailed(accountId: String): Flow<List<DavPendingEntity>> = flowOf(emptyList())
        override suspend fun deletePending(id: Long) { queue.removeAll { it.id == id } }
        override suspend fun failPending(id: Long, error: String) {
            queue.indices.firstOrNull { queue[it].id == id }?.let { queue[it] = queue[it].copy(failed = true, lastError = error) }
        }
        override suspend fun retryPending(id: Long, error: String) {
            queue.indices.firstOrNull { queue[it].id == id }?.let { queue[it] = queue[it].copy(attempts = queue[it].attempts + 1, lastError = error) }
        }
        override suspend fun renamePendingTarget(accountId: String, type: String, oldId: String, newId: String) {
            queue.indices.forEach { if (queue[it].objectId == oldId) queue[it] = queue[it].copy(objectId = newId) }
        }
    }

    private lateinit var server: MockWebServer
    private val dao = MemoryDao()
    private val auth = BasicAuth("alex", "x")
    private val answers = ArrayDeque<String>()
    private val posted = mutableListOf<JsonObject>()

    @Before fun start() {
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = if (request.method == "GET") {
                    Json.encodeToString(JmapSession.serializer(), session())
                } else {
                    posted += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    answers.removeFirst()
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
    }

    @After fun stop() = server.shutdown()

    private fun session() = JmapSession(
        capabilities = mapOf(
            Jmap.CORE_CAPABILITY to buildJsonObject { put("maxSizeUpload", 10) }, Jmap.MAIL_CAPABILITY to JsonObject(emptyMap()),
            Jmap.CALENDARS_CAPABILITY to JsonObject(emptyMap()), Jmap.FILENODE_CAPABILITY to JsonObject(emptyMap()),
        ),
        accounts = mapOf("m" to JmapAccount("me", mapOf(Jmap.MAIL_CAPABILITY to JsonObject(emptyMap())))),
        primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "m", Jmap.CALENDARS_CAPABILITY to "cal", Jmap.FILENODE_CAPABILITY to "fil"),
        apiUrl = server.url("/api").toString(),
    )

    private val creds get() = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"), username = "a", password = "x", id = "acc",
        protocol = MailProtocol.JMAP, authType = AuthType.BASIC,
    )

    private val host = object : MailAccountHost {
        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession = error("no")
        override suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth = auth
        override suspend fun firstSync(credentials: AccountCredentials) = error("no")
        override fun cachedJmapSession(): MailRepository.JmapAccountSession? = null
    }

    private val repo get() = DavRepository(JmapClient(), dao, RecordingAccountStorage(), host)
    private val event = JmapType.CALENDAR_EVENT
    private fun set(vararg body: String) = answers.addAll(body)
    private fun title(id: String) = Json.parseToJsonElement(dao.objects[Triple("acc", "CalendarEvent", id)]!!.json).jsonObject["title"]?.jsonPrimitive?.content

    @Test fun `an edit shows at once, goes out with the stored state, and the created id replaces the local one`() = runBlocking {
        dao.states["acc" to "CalendarEvent"] = "s1"
        val id = repo.create("acc", event, buildJsonObject { put("title", "Lunch"); put("start", "2026-01-05T12:00:00") })
        assertTrue(id.startsWith("local-"))
        assertEquals("Lunch", title(id))
        repo.update("acc", event, id, buildJsonObject { put("title", "Late lunch") })
        assertEquals(1, dao.queue.size) // merged into the create: one request, not two
        set("""{"methodResponses":[["CalendarEvent/set",{"newState":"s2","created":{"c":{"id":"srv1"}}},"s"]]}""")

        val r = repo.drain(creds)

        assertEquals(1, r.done)
        assertTrue(dao.queue.isEmpty())
        assertEquals("Late lunch", title("srv1"))
        val args = posted.single()["methodCalls"]!!.let { Json.parseToJsonElement(it.toString()) }.toString()
        assertTrue(args.contains("\"ifInState\":\"s1\"") && args.contains("Late lunch"))
    }

    @Test fun `a stale state is synced and the write retried once`() = runBlocking {
        dao.states["acc" to "CalendarEvent"] = "s1"
        dao.upsertObjects(listOf(DavObjectEntity("acc", "CalendarEvent", "e1", name = "A", json = """{"title":"A"}""")))
        repo.update("acc", event, "e1", buildJsonObject { put("title", "B") })
        set(
            """{"methodResponses":[["error",{"type":"stateMismatch"},"s"]]}""",
            """{"methodResponses":[["CalendarEvent/changes",{"created":[],"updated":[],"destroyed":[],"newState":"s5"},"c"]]}""",
            """{"methodResponses":[["CalendarEvent/set",{"newState":"s6","updated":{"e1":null}},"s"]]}""",
        )

        val r = repo.drain(creds)

        assertEquals(1, r.done)
        assertEquals("s5", dao.states["acc" to "CalendarEvent"])
        assertTrue(dao.queue.isEmpty())
    }

    @Test fun `a refused write is kept as failed and the pass goes on`() = runBlocking {
        dao.upsertObjects(listOf(DavObjectEntity("acc", "CalendarEvent", "e1", json = """{"title":"A"}""")))
        repo.update("acc", event, "e1", buildJsonObject { put("title", "B") })
        set("""{"methodResponses":[["CalendarEvent/set",{"newState":"s2","notUpdated":{"e1":{"type":"forbidden"}}},"s"]]}""")

        val r = repo.drain(creds)

        assertEquals(1, r.failed)
        assertTrue(dao.queue.single().failed)
        assertEquals("forbidden", dao.queue.single().lastError)
    }

    @Test fun `a file over the server's upload limit is failed at once and nothing is sent`() = runBlocking {
        val f = java.io.File.createTempFile("big", ".bin").apply { writeBytes(ByteArray(50)); deleteOnExit() }
        repo.uploadFile("acc", null, "big.bin", "application/octet-stream", f.path, 50)

        val r = repo.drain(creds)

        assertEquals(1, r.failed)
        assertTrue(dao.queue.single().failed)
        assertTrue(dao.queue.single().lastError!!.contains("limit"))
        assertTrue(posted.isEmpty())
    }

    @Test fun `a destroy of a never-sent object sends nothing`() = runBlocking {
        val id = repo.create("acc", event, buildJsonObject { put("title", "X") })
        repo.destroy("acc", event, id)
        assertTrue(dao.queue.isEmpty())
        assertNull(dao.objects[Triple("acc", "CalendarEvent", id)])
        assertEquals(0, repo.drain(creds).done)
        assertTrue(posted.isEmpty())
    }
}
