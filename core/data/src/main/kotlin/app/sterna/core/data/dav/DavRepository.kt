package app.sterna.core.data.dav

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStorage
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.DavDao
import app.sterna.core.data.db.DavObjectEntity
import app.sterna.core.data.db.DavPendingEntity
import app.sterna.core.data.mail.MailAccountHost
import app.sterna.core.jmap.JMAP_STATE_MISMATCH
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.JmapObject
import app.sterna.core.jmap.JmapType
import app.sterna.core.jmap.getAllObjects
import app.sterna.core.jmap.objectChanges
import app.sterna.core.jmap.parseCalendarBlob
import app.sterna.core.jmap.setObjects
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

/** One occurrence of a calendar object, with the row it came from. */
class CalendarOccurrence(val row: DavObjectEntity, val occurrence: Occurrence, val unsupportedRule: Boolean)

/** What one write-queue pass did. [touched] holds the type names that reached the server. */
class DrainResult(val done: Int, val failed: Int, val touched: Set<String>, val retryLater: Boolean)

/**
 * Calendars, tasks, contact cards and files of a JMAP account: a Room copy synced by `/changes`, and
 * a queue of writes that survives the process. The network only ever writes Room; screens read Room.
 * Every method that takes an [accountId] means Sterna's account id, not the JMAP one.
 */
class DavRepository(
    private val client: JmapClient,
    private val dao: DavDao,
    private val accountStore: AccountStorage,
    private val host: MailAccountHost,
) {
    private val types = listOf(JmapType.CALENDAR, JmapType.CALENDAR_EVENT, JmapType.ADDRESS_BOOK, JmapType.CONTACT_CARD, JmapType.FILE_NODE)

    // ---- reading ----

    fun watch(accountId: String, type: JmapType): Flow<List<DavObjectEntity>> = dao.watch(accountId, type.name)
    fun watchGroup(accountId: String, type: JmapType, groupId: String): Flow<List<DavObjectEntity>> =
        dao.watchGroup(accountId, type.name, groupId)
    fun watchTasks(accountId: String): Flow<List<DavObjectEntity>> = dao.watchTasks(accountId)
    suspend fun get(accountId: String, type: JmapType, id: String): DavObjectEntity? = dao.get(accountId, type.name, id)

    /** The type names the account's server listed at its last sync; empty until a sync has run. */
    fun watchSupported(accountId: String): Flow<List<String>> = dao.watchSupported(accountId)
    fun watchPendingCount(accountId: String): Flow<Int> = dao.watchPendingCount(accountId)
    fun watchFailed(accountId: String): Flow<List<DavPendingEntity>> = dao.watchFailed(accountId)
    suspend fun dismissFailed(id: Long) = dao.deletePending(id)

    /** Events (or tasks) with an occurrence in [fromMs, toMs), recurring ones expanded. */
    suspend fun occurrences(accountId: String, fromMs: Long, toMs: Long, tasks: Boolean = false): List<CalendarOccurrence> =
        dao.inWindow(accountId, tasks, fromMs, toMs).flatMap { row ->
            val x = expand(Json.parseToJsonElement(row.json).jsonObject, fromMs, toMs)
            x.occurrences.map { CalendarOccurrence(row, it, x.unsupportedRule) }
        }.sortedBy { it.occurrence.startMs }

    suspend fun plannedAlarms(accountId: String, nowMs: Long, horizonMs: Long): List<PlannedAlarm> {
        val calendars = dao.all(accountId, JmapType.CALENDAR.name).associate { it.id to Json.parseToJsonElement(it.json).jsonObject }
        return planAlarms(dao.all(accountId, JmapType.CALENDAR_EVENT.name), calendars, nowMs, horizonMs)
    }

    // ---- syncing ----

    /** True when the account is one this repository serves: JMAP, and not a sub-account of another login. */
    private fun serves(credentials: AccountCredentials): Boolean =
        credentials.protocol != MailProtocol.IMAP && accountStore.account(credentials.id)?.isLinked != true

    private suspend fun session(credentials: AccountCredentials): Pair<JmapSession, app.sterna.core.jmap.JmapAuth> {
        val auth = host.jmapAuth(credentials)
        return client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth) to auth
    }

    /** Brings every type the server lists up to date; one that it no longer lists is forgotten. Throws on a failed read, leaving the copy as it was. */
    suspend fun sync(credentials: AccountCredentials) {
        if (!serves(credentials)) return
        val (session, auth) = session(credentials)
        for (type in types) refresh(credentials.id, session, auth, type)
    }

    private suspend fun refresh(accountId: String, session: JmapSession, auth: app.sterna.core.jmap.JmapAuth, type: JmapType) {
        if (session.accountIdFor(type.capability) == null) {
            dao.forgetType(accountId, type.name)
            return
        }
        // Calendar and AddressBook are a handful of rows: read whole, no query.
        val paged = type != JmapType.CALENDAR && type != JmapType.ADDRESS_BOOK
        val since = dao.state(accountId, type.name)?.takeIf { it.isNotEmpty() }
        if (since == null) {
            val all = client.getAllObjects(session, auth, type, paged) ?: return
            dao.replaceAll(accountId, type.name, all.objects.map { davRow(accountId, type, it) }, all.state)
        } else {
            val changes = client.objectChanges(session, auth, type, since, paged) ?: return
            val rows = changes.changed.map { davRow(accountId, type, it) }
            if (changes.replacesAll) dao.replaceAll(accountId, type.name, rows, changes.newState)
            else dao.applyChanges(accountId, type.name, rows, changes.destroyed, changes.newState)
        }
        reapplyPending(accountId, type)
    }

    /** A sync overwrote rows with the server's: put back what the phone has changed and not yet sent. */
    private suspend fun reapplyPending(accountId: String, type: JmapType) {
        for (p in dao.pending(accountId).filter { it.type == type.name }) apply(p)
    }

    private suspend fun apply(p: DavPendingEntity) {
        val type = types.first { it.name == p.type }
        val patch = Json.parseToJsonElement(p.patchJson).jsonObject
        when (p.op) {
            "destroy" -> dao.deleteIds(p.accountId, p.type, listOf(p.objectId))
            "create" -> dao.upsertObjects(listOf(davRow(p.accountId, type, JmapObject(p.objectId, patch.withoutLocal()))))
            else -> dao.get(p.accountId, p.type, p.objectId)?.let {
                val patched = applyPatch(Json.parseToJsonElement(it.json).jsonObject, patch)
                dao.upsertObjects(listOf(davRow(p.accountId, type, JmapObject(p.objectId, patched))))
            }
        }
    }

    // ---- writing: shown at once, sent by [drain] ----

    /** Makes an object. [body] is what the server is sent; [shownAs] is extra JSON only the local row has (a file's placeholder blob). Returns the local id. */
    suspend fun create(accountId: String, type: JmapType, body: JsonObject, shownAs: JsonObject = JsonObject(emptyMap())): String {
        val id = "local-" + UUID.randomUUID()
        dao.upsertObjects(listOf(davRow(accountId, type, JmapObject(id, JsonObject(body.withoutLocal() + shownAs)))))
        dao.insertPending(DavPendingEntity(accountId = accountId, type = type.name, op = "create", objectId = id, patchJson = body.toString(), createdAt = System.currentTimeMillis()))
        return id
    }

    /** [patch]: JSON-pointer keys (a changed top-level property, or "participants/p1/participationStatus"); null removes. */
    suspend fun update(accountId: String, type: JmapType, id: String, patch: JsonObject) {
        val row = dao.get(accountId, type.name, id) ?: return
        val patched = applyPatch(Json.parseToJsonElement(row.json).jsonObject, patch.withoutLocal())
        dao.upsertObjects(listOf(davRow(accountId, type, JmapObject(id, patched))))
        val create = if (id.startsWith("local-")) dao.pendingFor(accountId, type.name, id).firstOrNull { it.op == "create" } else null
        if (create != null) {
            // Not sent yet: the create itself becomes the edited object, nothing to send but the one request.
            val merged = applyPatch(Json.parseToJsonElement(create.patchJson).jsonObject, patch)
            dao.deletePending(create.id)
            dao.insertPending(create.copy(id = create.id, patchJson = merged.toString()))
        } else {
            dao.insertPending(DavPendingEntity(accountId = accountId, type = type.name, op = "update", objectId = id, patchJson = patch.toString(), createdAt = System.currentTimeMillis()))
        }
    }

    suspend fun destroy(accountId: String, type: JmapType, id: String) {
        dao.deleteIds(accountId, type.name, listOf(id))
        if (id.startsWith("local-")) {
            // Never sent: forget it, and with it every edit queued on it.
            dao.pendingFor(accountId, type.name, id).forEach { cleanLocalFile(it); dao.deletePending(it.id) }
            return
        }
        dao.insertPending(DavPendingEntity(accountId = accountId, type = type.name, op = "destroy", objectId = id, patchJson = "{}", createdAt = System.currentTimeMillis()))
    }

    suspend fun accountsWithPendingWrites(): List<String> = dao.accountsWithPending()

    /**
     * Sends the queue in order. A server that says the state moved (`stateMismatch`) is synced and the
     * write retried once; a write the server refuses for good is kept, marked failed, for the user to see.
     * Network and server trouble end the pass with [DrainResult.retryLater].
     */
    suspend fun drain(credentials: AccountCredentials): DrainResult {
        if (!serves(credentials)) return DrainResult(0, 0, emptySet(), false)
        val pending = dao.pending(credentials.id)
        if (pending.isEmpty()) return DrainResult(0, 0, emptySet(), false)
        val (session, auth) = session(credentials)
        var done = 0
        var failed = 0
        val touched = mutableSetOf<String>()
        for (p in pending) {
            val type = types.first { it.name == p.type }
            var outcome = send(credentials, session, auth, type, dao.pending(credentials.id).firstOrNull { it.id == p.id } ?: continue)
            if (outcome == SendOutcome.STALE) {
                refresh(credentials.id, session, auth, type)
                outcome = send(credentials, session, auth, type, dao.pending(credentials.id).firstOrNull { it.id == p.id } ?: continue)
            }
            when (outcome) {
                SendOutcome.DONE -> { done++; touched += p.type }
                SendOutcome.REFUSED -> failed++
                SendOutcome.STALE, SendOutcome.LATER -> return DrainResult(done, failed, touched, retryLater = true)
            }
        }
        return DrainResult(done, failed, touched, retryLater = false)
    }

    private enum class SendOutcome { DONE, REFUSED, STALE, LATER }

    private suspend fun send(
        credentials: AccountCredentials,
        session: JmapSession,
        auth: app.sterna.core.jmap.JmapAuth,
        type: JmapType,
        p: DavPendingEntity,
    ): SendOutcome {
        val patch = Json.parseToJsonElement(p.patchJson).jsonObject
        val ifInState = dao.state(p.accountId, p.type)?.takeIf { it.isNotEmpty() }
        // The server writes the invitations, updates, cancellations and replies (iMIP); with no attendees it sends nothing.
        val extra = if (type == JmapType.CALENDAR_EVENT) JsonObject(mapOf("sendSchedulingMessages" to JsonPrimitive(true))) else JsonObject(emptyMap())
        return try {
            val result = when (p.op) {
                "create" -> {
                    val body = prepareUpload(session, auth, type, patch)
                    client.setObjects(session, auth, type, ifInState, create = mapOf("c" to body), extra = extra)
                }
                "update" -> client.setObjects(session, auth, type, ifInState, update = mapOf(p.objectId to patch), extra = extra)
                else -> client.setObjects(session, auth, type, ifInState, destroy = listOf(p.objectId), extra = extra)
            }
            val failure = result.failed.values.firstOrNull()
            when {
                failure != null && p.op == "destroy" && failure.type == "notFound" -> finish(p)
                failure != null -> {
                    dao.failPending(p.id, "${failure.type}${failure.description?.let { ": $it" }.orEmpty()}")
                    if (p.op == "create") dao.deleteIds(p.accountId, p.type, listOf(p.objectId))
                    SendOutcome.REFUSED
                }
                else -> {
                    result.created["c"]?.let { made ->
                        dao.renameId(p.accountId, p.type, p.objectId, made.id)
                        dao.renamePendingTarget(p.accountId, p.type, p.objectId, made.id)
                    }
                    finish(p)
                }
            }
        } catch (e: JmapException) {
            when {
                e.errorType == JMAP_STATE_MISMATCH -> SendOutcome.STALE
                // The server understood and said no: the write cannot succeed by waiting.
                e.errorType != null && e.httpCode == null -> {
                    dao.failPending(p.id, e.errorType!!)
                    SendOutcome.REFUSED
                }
                else -> { dao.retryPending(p.id, e.message.orEmpty()); SendOutcome.LATER }
            }
        } catch (e: java.io.IOException) {
            dao.retryPending(p.id, e.message.orEmpty())
            SendOutcome.LATER
        }
    }

    private suspend fun finish(p: DavPendingEntity): SendOutcome {
        cleanLocalFile(p)
        dao.deletePending(p.id)
        return SendOutcome.DONE
    }

    /** A queued file upload carries `_localFile`: its bytes go up as a blob now, and the create names it. */
    private suspend fun prepareUpload(session: JmapSession, auth: app.sterna.core.jmap.JmapAuth, type: JmapType, patch: JsonObject): JsonObject {
        val path = (patch[LOCAL_FILE] as? JsonPrimitive)?.contentOrNull ?: return patch
        val accountId = session.accountIdFor(type.capability) ?: throw JmapException("Server has no files account")
        val mime = (patch["type"] as? JsonPrimitive)?.contentOrNull
        // ponytail: whole file in memory, as the attachment download is; stream when files past DownloadLimits matter.
        val blob = client.uploadBlob(session, accountId, File(path).readBytes(), mime, auth)
        return JsonObject(patch.withoutLocal() + ("blobId" to JsonPrimitive(blob.blobId)))
    }

    private fun cleanLocalFile(p: DavPendingEntity) {
        (Json.parseToJsonElement(p.patchJson).jsonObject[LOCAL_FILE] as? JsonPrimitive)?.contentOrNull?.let { File(it).delete() }
    }

    // ---- files and invites ----

    /** The bytes of a file node. */
    suspend fun downloadFile(credentials: AccountCredentials, row: DavObjectEntity): ByteArray {
        val (session, auth) = session(credentials)
        val json = Json.parseToJsonElement(row.json).jsonObject
        val blobId = (json["blobId"] as? JsonPrimitive)?.contentOrNull ?: throw JmapException("A folder has no content")
        val accountId = session.accountIdFor(JmapType.FILE_NODE.capability) ?: throw JmapException("Server has no files account")
        return client.downloadBlob(session, accountId, blobId, (json["type"] as? JsonPrimitive)?.contentOrNull, row.name, auth)
    }

    /** Queues a file for upload from a copy the caller staged in app storage; the copy is deleted once sent. */
    suspend fun uploadFile(accountId: String, parentId: String?, name: String, mime: String, stagedPath: String, size: Long): String {
        val body = JsonObject(
            buildMap {
                parentId?.let { put("parentId", JsonPrimitive(it)) }
                put("name", JsonPrimitive(name)); put("type", JsonPrimitive(mime)); put(LOCAL_FILE, JsonPrimitive(stagedPath))
            },
        )
        return create(accountId, JmapType.FILE_NODE, body, JsonObject(mapOf("blobId" to JsonPrimitive("local"), "size" to JsonPrimitive(size))))
    }

    /** The events an uploaded or message-attached `.ics` carries, read by the server; empty when it lists no calendars. */
    suspend fun parseInvite(credentials: AccountCredentials, blobId: String): List<JmapObject> {
        if (!serves(credentials)) return emptyList()
        val (session, auth) = session(credentials)
        return client.parseCalendarBlob(session, auth, blobId)
    }

    suspend fun forgetAccount(accountId: String) = dao.deleteForAccount(accountId)

    private fun JsonObject.withoutLocal() = JsonObject(filterKeys { it != LOCAL_FILE })

    companion object {
        /** A key in a queued create, never sent: the staged file to upload and name by blob. */
        const val LOCAL_FILE = "_localFile"
    }
}
