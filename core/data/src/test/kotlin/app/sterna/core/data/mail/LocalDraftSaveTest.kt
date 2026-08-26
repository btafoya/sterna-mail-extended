package app.sterna.core.data.mail

import app.sterna.core.data.db.LOCAL_DRAFTS_CREATE_SQL
import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.data.db.OutboxBadgeItem
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxState
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/**
 * ⭐ **Saving a draft with no network** (#95), executed: the shipped [saveDraftLocalFirst] driven
 * over the shipped `local_drafts` table, with an upload that throws exactly as an offline transport
 * throws.
 *
 * The reported symptom is the whole subject: with no network the composer showed "could not save the
 * draft" plus the raw transport error, and what the user had typed died with the process. So every
 * case below asks the same two questions of a failed upload — is the text on the phone, and did the
 * save stay quiet — and the success cases ask the opposite one, is the row gone.
 *
 * ⛔ What is NOT faked: the row builder ([localDraftRow]), the order of operations
 * ([saveDraftLocalFirst]), the staging ([stageLocalDraftAttachments]) and the delete statement are
 * the shipped ones, and the table is built by [LOCAL_DRAFTS_CREATE_SQL] — the CREATE a real 22→23
 * upgrade runs. The lambdas are only the Room/file adapters [MailRepository] supplies;
 * [LocalDraftSaveWiringTest] pins that it supplies exactly these.
 */
class LocalDraftSaveTest {
    private lateinit var db: Connection
    private lateinit var filesDir: File

    /** Every call the harness made to the store, in order — so "exactly once" is countable. */
    private val discarded = mutableListOf<String>()

    /** Every deferred upload the save booked — what `LocalDraftUploads.enqueue` would receive. */
    private val booked = mutableListOf<LocalDraftUploadJob>()

    /** Every attempt the save wrote on a row — so "recorded nothing" is checkable, not assumed. */
    private val recorded = mutableListOf<String>()

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate(LOCAL_DRAFTS_CREATE_SQL) }
        filesDir = Files.createTempDirectory("local-drafts").toFile()
    }

    @After fun tearDown() {
        db.close()
        filesDir.deleteRecursively()
    }

    // ---- the store: the shipped table, and the entity's own field list -----------------------------

    /**
     * Room's `@Upsert` generates its statement at build time, so there is none to read out of the
     * DAO. The column list is taken from the ENTITY's own fields rather than typed out again: a
     * third hand-written copy of the schema is a third thing to forget, and
     * `LocalDraftEntityMatchesItsTableTest` already holds the entity and the CREATE together.
     */
    private fun upsert(row: LocalDraftEntity) {
        val fields = LocalDraftEntity::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        // Backticked like the shipped CREATE does: `references` is a SQLite keyword.
        val columns = fields.joinToString(", ") { "`${it.name}`" }
        val holes = fields.joinToString(", ") { "?" }
        db.prepareStatement("INSERT OR REPLACE INTO local_drafts ($columns) VALUES ($holes)").use { ps ->
            fields.forEachIndexed { i, field ->
                field.isAccessible = true
                when (val value = field.get(row)) {
                    null -> ps.setObject(i + 1, null)
                    is Boolean -> ps.setInt(i + 1, if (value) 1 else 0)
                    is Enum<*> -> ps.setString(i + 1, value.name)
                    else -> ps.setObject(i + 1, value)
                }
            }
            ps.executeUpdate()
        }
    }

    /** The shipped `LocalDraftDao.deleteById`, run for real. */
    private fun deleteById(accountId: String, id: String) {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", "deleteById"))
        db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name -> ps.setString(i + 1, if (name == "accountId") accountId else id) }
            ps.executeUpdate()
        }
    }

    /** Any shipped `LocalDraftDao` SELECT, run for real, with its named parameters bound by name. */
    private fun select(function: String, arguments: Map<String, Any?>): List<Map<String, String?>> {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", function))
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                require(name in arguments) { "$function binds :$name and the test did not supply it" }
                ps.setObject(i + 1, arguments[name])
            }
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                buildList {
                    while (rs.next()) add((1..meta.columnCount).associate { meta.getColumnName(it) to rs.getString(it) })
                }
            }
        }
    }

    /** One row, read back out of SQLite — the draft as the phone would find it after a restart. */
    private fun stored(accountId: String = ACCOUNT, id: String? = null): Map<String, String?>? =
        db.createStatement().use { st ->
            val where = if (id == null) "" else " AND id = '$id'"
            st.executeQuery("SELECT * FROM local_drafts WHERE accountId = '$accountId'$where").use { rs ->
                if (!rs.next()) return null
                val meta = rs.metaData
                (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getString(it) }
            }
        }

    /** The shipped `LocalDraftDao.pending`, run for real: what an upload worker would pick up. */
    private fun pending(accountId: String = ACCOUNT, nowMillis: Long = NOW): List<Map<String, String?>> =
        select("pending", mapOf("accountId" to accountId, "nowMillis" to nowMillis))

    private fun allRows(): List<Pair<String, String>> =
        db.createStatement().use { st ->
            st.executeQuery("SELECT accountId, id FROM local_drafts ORDER BY accountId, id").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }

    // ---- the save, as MailRepository assembles it --------------------------------------------------

    @Suppress("LongParameterList")
    private suspend fun save(
        accountId: String = ACCOUNT,
        to: List<String> = listOf("bob@example.org"),
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        subject: String = "Half written",
        body: String = TEXT,
        attachments: List<EmailBodyPart> = emptyList(),
        replacesEmailId: String? = null,
        replacesUidValidity: Long? = null,
        /**
         * The composer's own verdict on the body it is handing over, exactly as
         * `MailRepository.saveDraft` receives it from `editingDraftLossy`: true when this text
         * cannot answer for the draft behind it — a reopened HTML draft flattened to plain text, or
         * a body the resume slot could not park on disk during an activity recreation.
         */
        bodyIsLossy: Boolean = false,
        /** Run inside the staging step, i.e. while the row is written but its files are not. */
        stagingProbe: suspend () -> Unit = {},
        /**
         * ⭐ **What Room does before it writes, and what plain JDBC cannot do**: every DAO call is
         * `withContext(Dispatchers.IO)`, i.e. a SUSPENSION POINT, so from a cancelled coroutine it
         * throws INSTEAD of writing. The seams below are direct JDBC and run happily inside a
         * coroutine that has been cancelled — which makes every "the composer walked away" case
         * pass here while the phone loses the write. Handed `{ yield() }`, they behave as the DAO
         * does. Default no-op: a test that never cancels cannot tell the difference.
         */
        roomDispatch: suspend () -> Unit = {},
        upload: suspend (LocalDraftEntity) -> DraftSaveOutcome,
    ): DraftSaveOutcome {
        // Assembled exactly as MailRepository.saveDraft assembles it — including BOTH lookups, the
        // second of which runs the shipped `forServerDraft` statement against the real table.
        val target = localDraftTarget(
            replacesEmailId,
            byId = { byId(accountId, it) },
            forServerDraft = { forServerDraft(accountId, it) },
        )
        val existing = target.existing
        val row = localDraftRow(
            accountId = accountId,
            id = target.id,
            messageId = localDraftMessageId(existing) { newMessageId("alex@example.org") },
            to = to, cc = cc, bcc = bcc,
            subject = subject, body = body,
            replacesEmailId = serverDraftReplacedBy(replacesEmailId),
            replacesUidValidity = replacesUidValidity,
            bodyIsLossy = bodyIsLossy,
            existing = existing,
            nowMillis = NOW,
        )
        return saveDraftLocalFirst(
            row = row,
            existing = existing,
            nowMillis = NOW,
            upsert = { roomDispatch(); upsert(it) },
            stage = {
                stagingProbe()
                stageLocalDraftAttachments(File(filesDir, localDraftDirName(it.id)), attachments)
            },
            // ⛔ `MailRepository.discardLocalDraft`'s own shape, `runCatching` included: both halves
            // are best-effort, so the CancellationException the Room delete throws is SWALLOWED
            // while the file delete — which does not suspend — goes through anyway. Written as one
            // straight line here, no test could see the row that survives its own bytes.
            discard = {
                discarded += it.id
                runCatching { roomDispatch(); deleteById(accountId, it.id) }
                runCatching { File(filesDir, localDraftDirName(it.id)).deleteRecursively() }
            },
            upload = upload,
            // The shipped `LocalDraftDao.recordAttempt`, run for real against the row just written.
            record = { saved, attempt ->
                roomDispatch()
                recorded += saved.id
                recordAttempt(accountId, saved.id, attempt)
            },
            // What `MailRepository` hands to `LocalDraftUploads.enqueue` through its scheduler seam.
            schedule = { booked += it },
        )
    }

    /** The shipped `LocalDraftDao.recordAttempt`, bound by name and executed. */
    private fun recordAttempt(accountId: String, id: String, attempt: LocalDraftAttempt) {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", "recordAttempt"))
        db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> ps.setString(i + 1, accountId)
                    "id" -> ps.setString(i + 1, id)
                    // ⛔ PENDING, always: there is no state to park a draft in (LocalDraftState).
                    "state" -> ps.setString(i + 1, LocalDraftState.PENDING.name)
                    "attemptCount" -> ps.setInt(i + 1, attempt.attemptCount)
                    "lastError" -> ps.setString(i + 1, attempt.error)
                    "lastAttemptMillis" -> ps.setLong(i + 1, attempt.atMillis)
                    "notBeforeMillis" -> ps.setLong(i + 1, attempt.notBeforeMillis)
                    else -> error("recordAttempt now binds :$name, which this test does not know")
                }
            }
            ps.executeUpdate()
        }
    }

    /** The shipped `LocalDraftDao.setState`, executed — what a reopen and a release both write. */
    private fun setState(id: String, state: LocalDraftState, accountId: String = ACCOUNT) {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", "setState"))
        db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> ps.setString(i + 1, accountId)
                    "id" -> ps.setString(i + 1, id)
                    "state" -> ps.setString(i + 1, state.name)
                    else -> error("setState now binds :$name, which this test does not know")
                }
            }
            ps.executeUpdate()
        }
    }

    /** The row as the entity — the columns a re-save reads back off it. */
    private fun byId(accountId: String, id: String): LocalDraftEntity? = entityOf(stored(accountId, id))

    /** The shipped `LocalDraftDao.forServerDraft`, run for real: the row already naming [emailId]. */
    private fun forServerDraft(accountId: String, emailId: String): LocalDraftEntity? =
        entityOf(select("forServerDraft", mapOf("accountId" to accountId, "replacesEmailId" to emailId)).firstOrNull())

    private fun entityOf(row: Map<String, String?>?): LocalDraftEntity? {
        if (row == null) return null
        return LocalDraftEntity(
            accountId = row["accountId"]!!, id = row["id"]!!, messageId = row["messageId"]!!,
            toAddresses = row["toAddresses"]!!, cc = row["cc"], bcc = row["bcc"],
            subject = row["subject"]!!, textBody = row["textBody"]!!,
            attachmentsJson = row["attachmentsJson"]!!,
            replacesEmailId = row["replacesEmailId"],
            replacesUidValidity = row["replacesUidValidity"]?.toLong(),
            createdAtMillis = row["createdAtMillis"]!!.toLong(),
            updatedAtMillis = row["updatedAtMillis"]!!.toLong(),
            notBeforeMillis = row["notBeforeMillis"]!!.toLong(),
            state = LocalDraftState.valueOf(row["state"]!!),
        )
    }

    /** What an APPEND does with no network — the real message the composer used to display. */
    private fun append(): Nothing =
        throw java.net.UnknownHostException("Unable to resolve host \"mail.example.org\"")

    /** What an offline transport does, on both protocols: it throws before anything is written. */
    private val offline: suspend (LocalDraftEntity) -> DraftSaveOutcome = { append() }

    // ---- 1. offline: the text is on the phone, and nothing is reported ------------------------------

    @Test fun `an IMAP save with no network keeps the whole draft and does not throw`() = runTest {
        val outcome = save(
            to = listOf("bob@example.org", " carol@example.org "),
            cc = listOf("dave@example.org"),
            bcc = listOf("erin@example.org"),
            upload = offline,
        )

        assertEquals(
            "an upload that could not happen is not a failed save: the composer must be able to " +
                "close without an error banner (#95)",
            DraftSaveOutcome.KEPT_ON_DEVICE, outcome,
        )
        val row = requireNotNull(stored()) { "nothing was written to local_drafts before the network was tried" }
        assertEquals(TEXT, row["textBody"])
        assertEquals("Half written", row["subject"])
        assertEquals("bob@example.org,carol@example.org", row["toAddresses"])
        assertEquals("dave@example.org", row["cc"])
        assertEquals("erin@example.org", row["bcc"])
        assertEquals("the row is due for an upload, not parked", LocalDraftState.PENDING.name, row["state"])
        assertEquals("nothing was consumed", emptyList<String>(), discarded)
    }

    @Test fun `a JMAP save whose very first call is the network keeps the draft just the same`() = runTest {
        // The JMAP branch opens with connect(), so on that protocol the throw comes from the first
        // line of the upload rather than from an APPEND. The row is written before either.
        val outcome = save(upload = { error("Couldn't reach the server: connect failed") })

        assertEquals(DraftSaveOutcome.KEPT_ON_DEVICE, outcome)
        assertEquals(TEXT, requireNotNull(stored())["textBody"])
    }

    @Test fun `the row is in the table before the upload is even attempted`() = runTest {
        // ⭐ THE assertion of this volet, and the only one that can tell the fix from a save that
        // merely files the draft away once the network has already refused. Writing after the
        // attempt would pass every other test here and still lose the text to a process killed
        // mid-upload, a socket that hangs, or a phone that runs out of battery in a lift.
        var seenDuringUpload: String? = null
        save(upload = { seenDuringUpload = stored()?.get("textBody"); append() })

        assertEquals(
            "the draft must already be durable when the first byte goes out — that is the whole " +
                "reason to write locally at all (#95)",
            TEXT, seenDuringUpload,
        )
    }

    @Test fun `a failed save arms the backoff instead of leaving the row due at once`() = runTest {
        // ⛔ The half nothing wrote (#95, upload volet): `saveDraftLocalFirst` swallowed the failure
        // into KEPT_ON_DEVICE and touched no column, so `attemptCount`, `lastError` and
        // `notBeforeMillis` stayed as the save wrote them — and `pending` handed the row straight
        // back. With a server that is refusing rather than absent, the worker then loops on it as
        // fast as WorkManager will let it, on battery, for as long as the refusal lasts.
        //
        // The couples are written out, never recomputed from the shipped rule: a test that asks
        // localDraftRetryDelayMillis what to expect stays green when the rule is inverted.
        save(upload = offline)

        val row = requireNotNull(stored())
        assertEquals("one failure, counted", 1, row["attemptCount"]!!.toInt())
        assertEquals(
            "the cause is kept on the row, so a later screen can say why it is still here",
            "Unable to resolve host \"mail.example.org\"", row["lastError"],
        )
        assertEquals("the instant of the attempt", NOW.toString(), row["lastAttemptMillis"])
        assertEquals("30 s after the first failure", (NOW + 30_000L).toString(), row["notBeforeMillis"])
        assertEquals("⛔ and it is NOT parked: no cap, no terminal state", "PENDING", row["state"])
        assertEquals("the queue must not hand it back at once", 0, pending().size)
        assertEquals("it comes back when the backoff has run out", 1, pending(nowMillis = NOW + 30_000L).size)
    }

    @Test fun `a save the network refused books its own deferred upload, at the backoff's instant`() = runTest {
        // ⛔ THE promise of the volet: "it rejoins the server on its own when the network comes
        // back". Nothing booked anything at save time — `LocalDraftUploads.enqueue` had two
        // callers, the startup re-arm and the worker re-booking itself. So a draft saved in a lift
        // left the phone only after the app had DIED and started again: the row was written, its
        // backoff was armed, and no timer was ever attached to it.
        //
        // ⛔ And the delay is the one the attempt just wrote, not 0: against a server that is
        // REFUSING rather than absent, a work item due immediately is a retry loop on battery.
        save(upload = offline)

        val id = requireNotNull(stored())["id"]!!
        assertEquals(listOf(LocalDraftUploadJob(ACCOUNT, id, 30_000L)), booked)
    }

    @Test fun `a save that did reach the server books nothing at all`() = runTest {
        // The witness: the row was consumed, so a work item would wake up for a draft that no
        // longer exists — and every wake-up costs a process start.
        save(upload = { DraftSaveOutcome.SAVED })

        assertEquals(emptyList<LocalDraftUploadJob>(), booked)
    }

    @Test fun `a save the composer walked away from books the upload before it lets the cancel out`() = runTest {
        // ⛔ THE hole measured at the bench (#95): the composer closes while the upload is in
        // flight, its scope goes with it, and nothing bounds the interactive attempt — so it has
        // all the time in the world to be cancelled. The row then stays PENDING with attemptCount
        // 0, no cause written and NO work item booked: 17 minutes of healthy network went by
        // without the draft leaving, and only a force-stop plus a restart sent it (the startup
        // re-arm, `revertUnfinishedLocalDrafts`).
        //
        // ⛔ **This coroutine is REALLY cancelled**, as LocalDraftUploadTest's own stop test is.
        // Made to `throw CancellationException()` from a live job it would pass against code that
        // cannot work: from a cancelled coroutine EVERY suspension point throws, so a booking seam
        // that suspends — or a `yield()` in front of it — books nothing on the real path while the
        // thrown-by-hand version sails through.
        var returnedNormally = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            save(upload = { awaitCancellation() })
            returnedNormally = true
        }

        job.cancelAndJoin()

        val id = requireNotNull(stored())["id"]!!
        // ⛔ The arguments, not merely "something was booked": the account, THIS row's id, and 0.
        // ⭐ Zero, unlike the ordinary failure's backoff: nothing failed here. The row carries no
        // attempt to space out, and the user has just walked away from a draft the server has not
        // got — the work item is due at once, and `pending` agrees below.
        assertEquals(listOf(LocalDraftUploadJob(ACCOUNT, id, 0L)), booked)
        assertTrue("a really cancelled job is what this pins", job.isCancelled)
        assertFalse(
            "⛔ the cancellation must still reach the caller — swallowed, a composer that is going " +
                "away is told the draft was saved",
            returnedNormally,
        )
    }

    @Test fun `a cancelled save is not a failure, so it writes nothing on the row`() = runTest {
        // ⛔ The guard the booking must not buy itself: an attempt written here makes
        // `localDraftMayAlreadyBeOnServer` true on a row that has NEVER been on a wire, and the
        // deferred upload then pays a folder read (`UID SEARCH ALL` plus envelopes) to be told no.
        // A cancellation is not a failure: no count, no cause, no backoff armed.
        val job = launch(start = CoroutineStart.UNDISPATCHED) { save(upload = { awaitCancellation() }) }

        job.cancelAndJoin()

        val row = requireNotNull(stored())
        assertEquals("⛔ no attempt is recorded on a cancellation", emptyList<String>(), recorded)
        assertEquals("the text is on the phone, whole", TEXT, row["textBody"])
        assertEquals("nothing failed, so nothing is counted", 0, row["attemptCount"]!!.toInt())
        assertNull("and no cause is invented", row["lastError"])
        assertNull("nor an attempt instant", row["lastAttemptMillis"])
        assertEquals("no backoff is armed", NOW.toString(), row["notBeforeMillis"])
        assertEquals("the row is uploadable, not parked", "PENDING", row["state"])
        assertEquals("and the queue hands it back at once", 1, pending().size)
        assertEquals(
            "⛔ nothing consumes the row on this path — the server draft it replaces is untouched",
            emptyList<String>(), discarded,
        )
    }

    @Test fun `re-saving a draft the composer holds hands it back to the worker, not to the lease`() = runTest {
        // ⛔ THE mutation that survived the first pass: `state = LocalDraftState.PENDING` on the
        // durable row → `state = existing?.state ?: LocalDraftState.PENDING`. Nothing here ever
        // built an `existing` row in EDITING, so inheriting compiled and left the suite green —
        // while restoring the defect with the fix still in the file. Reopening a kept draft takes
        // the EDITING lease (`takeLocalDraftForEdit`); a re-save that writes it back leaves a row
        // `localDraftUploadIsDue` answers false for, so the deferred attempt is NOT_DUE with no
        // retryAtMillis, `uploadLocalDraftRebooking` re-books nothing, and the draft never leaves.
        save(body = "first pass", upload = offline)
        val id = requireNotNull(stored())["id"]!!
        setState(id, LocalDraftState.EDITING)
        assertEquals("the composer holds it", LocalDraftState.EDITING.name, requireNotNull(stored())["state"])

        // The row is read back out of SQLite AT the upload, which is the only instant the durable
        // write can be judged on: the failure below records the attempt, and `recordAttempt` writes
        // PENDING itself — so the final row says PENDING either way.
        var attempted: LocalDraftEntity? = null
        save(body = "second pass", replacesEmailId = id, upload = { attempted = byId(ACCOUNT, id); append() })

        val row = requireNotNull(attempted) { "the upload was never attempted" }
        assertEquals("the newest text is what was made uploadable", "second pass", row.textBody)
        assertEquals(
            "⛔ the durable write hands the row back to the worker; it must not carry the lease over",
            LocalDraftState.PENDING, row.state,
        )
        // ⭐ And the decision itself, EXECUTED on the row as SQLite hands it back rather than
        // restated: a row still holding the lease is answered NOT_DUE, and a NOT_DUE whose
        // notBeforeMillis is in the past carries no retryAtMillis at all — nothing to re-book.
        val verdict = uploadLocalDraftOnce(
            row = row,
            nowMillis = row.notBeforeMillis,
            markUploading = {},
            upload = { DraftSaveOutcome.SAVED },
            discard = {},
            record = {},
        )
        assertEquals("the deferred attempt must be allowed to run", LocalDraftUploadStep.UPLOADED, verdict.step)
    }

    @Test fun `a save the composer left mid-staging still leaves a draft the worker picks up`() = runTest {
        // ⛔ `stageLocalDraftAttachments` does not suspend, so a cancellation arriving while the
        // attachment bytes are copied is FIRST SEEN at the write that turns the staged row into an
        // uploadable one — which sits outside the `try`, so nothing catches it. What was left was a
        // row in STAGING saying "[]" with its bytes already on disk: `LocalDraftDao.pending` skips
        // STAGING, so no upload takes it, and the startup re-arm only rescues it as a draft WITHOUT
        // the files that are sitting right there on disk. Which is why the write below is
        // NonCancellable: the row comes out PENDING and describing its bytes, here and now.
        val source = File(filesDir, "compose-cache").apply { mkdirs() }
        val attached = File(source, "notes.txt").apply { writeText("hello") }
        val part = EmailBodyPart(partId = attached.absolutePath, name = "notes.txt", size = 5L)

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            save(
                attachments = listOf(part),
                roomDispatch = { yield() },
                stagingProbe = { currentCoroutineContext().cancel() },
                upload = { awaitCancellation() },
            )
        }
        job.join()

        assertTrue("a really cancelled save is what this pins", job.isCancelled)
        val row = requireNotNull(stored()) { "the text is on the phone whatever happens to the caller" }
        assertEquals("the text is the newest one", TEXT, row["textBody"])
        assertEquals(
            "⛔ the row must come out uploadable: STAGING is a state no reader of the queue revives",
            LocalDraftState.PENDING.name, row["state"],
        )
        val staged = OutboxAttachments.decode(row["attachmentsJson"]).singleOrNull()
        assertEquals("and describing the bytes that were copied, not \"[]\"", "notes.txt", staged?.name)
        assertTrue("which are where the row says they are", File(staged?.path.orEmpty()).exists())
        assertEquals("the queue hands the draft back", 1, pending().size)
        assertEquals(
            "and the cancellation books the deferred upload, as it does on the plain path",
            listOf(LocalDraftUploadJob(ACCOUNT, row["id"]!!, 0L)), booked,
        )
    }

    @Test fun `a staging that fails while the composer is leaving takes its own row with it`() = runTest {
        // ⛔ The other half of the same hole. `discardLocalDraft` is two `runCatching`: the first
        // swallows the CancellationException the Room delete throws, the second deletes the staged
        // tree anyway. Run from a cancelled coroutine it therefore removes the BYTES and keeps the
        // ROW — STAGING, saying "[]" — which is precisely the row nothing ever picks up again.
        val gone = File(filesDir, "compose-cache/vanished.pdf")
        var failure: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            failure = runCatching {
                save(
                    attachments = listOf(EmailBodyPart(partId = gone.absolutePath, name = "vanished.pdf")),
                    roomDispatch = { yield() },
                    stagingProbe = { currentCoroutineContext().cancel() },
                    upload = { DraftSaveOutcome.SAVED },
                )
            }.exceptionOrNull()
        }
        job.join()

        assertNull(
            "⛔ the undoing of a save that never happened must finish: half of it leaves a row " +
                "describing bytes that have just been deleted, and no reader revives it",
            stored(),
        )
        assertTrue(
            "the staging failure is still what reaches the caller: $failure",
            failure is IllegalStateException,
        )
    }

    @Test fun `a draft that DID reach the server is consumed even if the composer left mid-upload`() = runTest {
        // ⛔ The success path, cut on the way back. The APPEND went through and the original it
        // replaced has been destroyed; a delete that only half runs leaves a row describing staged
        // files that are already gone, and the deferred worker uploads that a second time.
        val source = File(filesDir, "compose-cache").apply { mkdirs() }
        val attached = File(source, "notes.txt").apply { writeText("hello") }
        val part = EmailBodyPart(partId = attached.absolutePath, name = "notes.txt", size = 5L)
        var stagedDir: File? = null

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            save(
                attachments = listOf(part),
                roomDispatch = { yield() },
                upload = {
                    stagedDir = File(filesDir, localDraftDirName(it.id))
                    currentCoroutineContext().cancel()
                    DraftSaveOutcome.SAVED
                },
            )
        }
        job.join()

        assertNull("⛔ the row goes with the upload that succeeded, or Drafts holds it twice", stored())
        assertFalse("and its staged bytes go with it", requireNotNull(stagedDir).exists())
    }

    @Test fun `a save that failed as the composer left still books its retry`() = runTest {
        // ⛔ The ordinary failure branch, cancelled. `record` suspends — it is a Room write — so on
        // this path it throws, and a booking placed AFTER it never runs at all: the row keeps its
        // old backoff and has no work item, i.e. the draft waits for the next START of the app,
        // which is the very defect this volet exists to close. Booked first, the row is left due
        // and the appointment finds it: a resumption instead of nothing.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            save(
                roomDispatch = { yield() },
                upload = { currentCoroutineContext().cancel(); append() },
            )
        }
        job.join()

        val row = requireNotNull(stored())
        assertEquals(
            "the account, THIS row's id, and the backoff the attempt computed:",
            listOf(LocalDraftUploadJob(ACCOUNT, row["id"]!!, 30_000L)), booked,
        )
        assertEquals("the write itself was cut, so nothing was recorded", emptyList<String>(), recorded)
        assertEquals("the row keeps the backoff it already had", NOW.toString(), row["notBeforeMillis"])
        assertEquals("so what the booking wakes up for is a row that is due", 1, pending().size)
    }

    @Test fun `re-saving a draft starts its wait over, and that is the shipped decision`() = runTest {
        // ⚠ `localDraftRow` builds a fresh entity at every save, so `attemptCount` goes back to
        // zero whenever the user touches the draft, and the wait with it. The KDoc used to claim
        // the opposite ("it does not reset"); what is written here is what runs. It is defensible:
        // the backoff spares the battery of a phone nobody is holding, and this draft has just been
        // typed into. ⛔ Between two DEFERRED attempts the count does accumulate — see
        // LocalDraftUploadTest, which drives the worker's own path.
        val server = "imap:acc:Drafts:12"
        save(body = "first pass", replacesEmailId = server, upload = offline)
        save(body = "second pass", replacesEmailId = server, upload = offline)

        val row = requireNotNull(stored())
        assertEquals("the count starts again at the re-save", 1, row["attemptCount"]!!.toInt())
        assertEquals("and so does the wait", (NOW + 30_000L).toString(), row["notBeforeMillis"])
        assertEquals("the work item booked says the same thing", 30_000L, booked.last().delayMillis)
    }

    @Test fun `the body read back from the table after a restart is the whole body`() = runTest {
        val long = (1..200).joinToString(" ") { "line $it of what the user typed" }
        save(body = long, upload = offline)

        // Read out of SQLite, not out of the object that was written: this is the draft as a
        // process that died and came back would find it.
        assertEquals(long, requireNotNull(stored())["textBody"])
    }

    // ---- 2. the successful upload, which is the path that consumes the row ---------------------------

    @Test fun `an upload that goes through consumes the row and its files exactly once`() = runTest {
        val source = File(filesDir, "compose-cache").apply { mkdirs() }
        val attached = File(source, "notes.txt").apply { writeText("hello") }

        val outcome = save(
            attachments = listOf(EmailBodyPart(partId = attached.absolutePath, name = "notes.txt", size = 5L)),
            upload = { DraftSaveOutcome.SAVED },
        )

        assertEquals(DraftSaveOutcome.SAVED, outcome)
        assertNull("the draft is on the server; the local copy must not linger", stored())
        assertEquals("consumed once, not twice", 1, discarded.size)
        assertFalse(
            "the staged bytes go with the row",
            File(filesDir, localDraftDirName(discarded.single())).exists(),
        )
    }

    @Test fun `the outcome of a successful upload is the server path's own verdict`() = runTest {
        // ORIGINAL_KEPT is about the OLD server copy (#63) and has nothing to do with this volet:
        // the upload happened, so the local row goes either way and the verdict is passed through.
        val outcome = save(upload = { DraftSaveOutcome.ORIGINAL_KEPT })

        assertEquals(DraftSaveOutcome.ORIGINAL_KEPT, outcome)
        assertNull(stored())
    }

    // ---- 3. re-saving, and the two scopes -----------------------------------------------------------

    @Test fun `re-saving a local draft with no network updates the same row`() = runTest {
        save(body = "first pass", upload = offline)
        val id = requireNotNull(stored())["id"]!!
        val firstMessageId = requireNotNull(stored())["messageId"]

        save(body = "second pass, longer", replacesEmailId = id, upload = offline)

        assertEquals("one draft, one row — never a copy per autosave", listOf(ACCOUNT to id), allRows())
        assertEquals("second pass, longer", requireNotNull(stored())["textBody"])
        assertNotEquals(
            "⛔ the Message-ID names the VERSION, not the draft: every save by the user mints a " +
                "fresh one. Kept, it lets a deferred upload find the copy of an EARLIER version on " +
                "the server, skip the append and consume the row — the newest text nowhere",
            firstMessageId, requireNotNull(stored())["messageId"],
        )
    }

    @Test fun `re-saving the SAME server draft with no network updates the one row`() = runTest {
        // ⭐ The reachable re-save, and the one the first pass doubled. Reopening a LOCAL draft is
        // not wired to any screen yet; reopening a SERVER draft, correcting it and saving again
        // with no network is the everyday route. The row is found by `replacesEmailId`, not by an
        // id the server never issued — without that lookup each save minted a fresh UUID, and the
        // upload volet would then append N copies to Drafts and try N times to destroy the same
        // original.
        val server = "imap:acc:Drafts:12"
        save(body = "first pass", replacesEmailId = server, upload = offline)
        val id = requireNotNull(stored())["id"]!!
        val messageId = requireNotNull(stored())["messageId"]

        save(body = "second pass, longer", replacesEmailId = server, upload = offline)
        save(body = "third pass", replacesEmailId = server, upload = offline)

        assertEquals("one draft, one row — never a copy per save", listOf(ACCOUNT to id), allRows())
        assertEquals("third pass", requireNotNull(stored())["textBody"])
        assertNotEquals(
            "the third save carries the third version of the text, so it carries a third name: " +
                "what the de-duplication search must be able to tell apart is two VERSIONS, not " +
                "two drafts. The price is a stale copy left in Drafts, which is visible and " +
                "deletable — the alternative is a version that exists nowhere",
            messageId, requireNotNull(stored())["messageId"],
        )
        assertEquals(server, requireNotNull(stored())["replacesEmailId"])
    }

    @Test fun `two drafts replacing two different server drafts stay two rows`() = runTest {
        // The witness on the test above: the lookup must key on the id being replaced, not simply
        // reuse whatever row the account happens to hold.
        save(body = "answer to Bob", replacesEmailId = "imap:acc:Drafts:12", upload = offline)
        save(body = "answer to Carol", replacesEmailId = "imap:acc:Drafts:99", upload = offline)

        assertEquals("two edited originals, two local drafts", 2, allRows().size)
        assertEquals(
            listOf("answer to Bob", "answer to Carol"),
            allRows().mapNotNull { requireNotNull(stored(it.first, it.second))["textBody"] }.sorted(),
        )
    }

    @Test fun `a brand-new draft never lands on another draft's row`() = runTest {
        save(body = "one note", upload = offline)
        save(body = "another note", upload = offline)

        assertEquals("nothing was being replaced, so nothing may be reused", 2, allRows().size)
    }

    @Test fun `two accounts of the same server each keep their own draft`() = runTest {
        // #31: ids collide between two accounts of one server, and #121 was a write that outlived
        // its account. The victim here would be text that exists nowhere else.
        save(accountId = "acc-1", body = "for the first account", upload = offline)
        save(accountId = "acc-2", body = "for the second account", upload = offline)

        assertEquals("for the first account", requireNotNull(stored("acc-1"))["textBody"])
        assertEquals("for the second account", requireNotNull(stored("acc-2"))["textBody"])
        assertNotEquals(requireNotNull(stored("acc-1"))["id"], requireNotNull(stored("acc-2"))["id"])

        // And consuming the first account's draft leaves the second's alone — the delete is the
        // shipped, scoped statement, run against both rows sitting in the same table.
        val first = requireNotNull(stored("acc-1"))["id"]!!
        save(accountId = "acc-1", replacesEmailId = first, upload = { DraftSaveOutcome.SAVED })
        assertNull("the uploaded draft is gone", stored("acc-1"))
        assertEquals("for the second account", requireNotNull(stored("acc-2"))["textBody"])
    }

    @Test fun `a draft with no recipient at all is saved`() = runTest {
        // #69. The send path would refuse this (enqueueSend requires a recipient); a draft must not.
        val outcome = save(to = emptyList(), subject = "", body = "just a note to myself", upload = offline)

        assertEquals(DraftSaveOutcome.KEPT_ON_DEVICE, outcome)
        assertEquals("just a note to myself", requireNotNull(stored())["textBody"])
        assertEquals("", requireNotNull(stored())["toAddresses"])
    }

    // ---- 3 bis. ⛔ a body the composer says it LOST never writes over the one already stored ----------

    @Test fun `the four answers of the rule that decides which body is written`() {
        // The decision itself, EXECUTED — not the line of source that contains it.
        assertEquals(
            "armed + nothing to write + something already written: the stored text stands",
            "what was typed", localDraftBodyToWrite("", "what was typed", bodyIsLossy = true),
        )
        assertEquals(
            "whitespace is not text either — `draftHasContent` reads a body the same way",
            "what was typed", localDraftBodyToWrite("  \n\t ", "what was typed", bodyIsLossy = true),
        )
        assertEquals(
            "⛔ armed but the composer HAS a body: an HTML draft reopened from the server arms the " +
                "flag every time, and what the user just typed is what she means to keep",
            "the new text", localDraftBodyToWrite("the new text", "what was typed", bodyIsLossy = true),
        )
        assertEquals(
            "⛔ not armed: emptying a draft on purpose still takes, or no re-save could shorten one",
            "", localDraftBodyToWrite("", "what was typed", bodyIsLossy = false),
        )
        assertEquals(
            "a first save has no row under it and writes what it has",
            "", localDraftBodyToWrite("", null, bodyIsLossy = true),
        )
        assertEquals(
            "and a row holding nothing worth keeping has nothing to protect",
            "", localDraftBodyToWrite("", "   ", bodyIsLossy = true),
        )
    }

    @Test fun `a re-save whose body was lost keeps the text the row already holds`() {
        // D7. A draft composed with no network, whose `local_drafts` row is the ONLY copy: during an
        // activity recreation the resume slot could not park a body above INLINE_LIMIT (16 384), so
        // the composer came back with an EMPTY body and a MARKED one — `editingDraftLossy` true. A
        // surviving recipient kept Save lit, and the save arrived here with `body = ""` and
        // `bodyIsLossy = true`. Written straight onto the row, the only copy of the text was gone,
        // definitively and without a word on screen.
        val existing = row().copy(textBody = LONG)
        val resaved = localDraftRow(
            accountId = ACCOUNT, id = existing.id, messageId = "v2@example.org",
            to = listOf("carol@example.org"), cc = emptyList(), bcc = emptyList(),
            subject = "Now with a subject", body = "",
            bodyIsLossy = true, existing = existing, nowMillis = NOW + 5_000L,
        )

        // Length first, content second: a wiped body fails on a number one can read, and the exact
        // text is still asserted right behind it.
        assertEquals(
            "the only copy of the text survives a body the composer could not give back",
            LONG.length, resaved.textBody.length,
        )
        assertEquals(LONG, resaved.textBody)
        assertEquals("…and the rest of the save is NOT held back: the subject is the new one", "Now with a subject", resaved.subject)
        assertEquals("nor the addressing", "carol@example.org", resaved.toAddresses)
        assertEquals("nor the instant it was saved at", NOW + 5_000L, resaved.updatedAtMillis)
        assertTrue("and the row still says this save cannot answer for the original", resaved.bodyIsLossy)
    }

    @Test fun `an offline re-save that lost its body leaves the draft whole, on its one row`() = runTest {
        save(body = LONG, upload = offline)
        val id = requireNotNull(stored())["id"]!!

        save(body = "", bodyIsLossy = true, replacesEmailId = id, upload = offline)

        assertEquals("one draft, one row", listOf(ACCOUNT to id), allRows())
        val after = requireNotNull(stored())
        assertEquals(
            "the text is still on the phone after the save that lost it",
            LONG.length, after["textBody"]!!.length,
        )
        assertEquals(LONG, after["textBody"])
        assertEquals("and the row still carries the loss, for every destroy guard downstream", "1", after["bodyIsLossy"])
        assertNull("re-saving the phone's own draft still replaces nothing on the server", after["replacesEmailId"])
    }

    @Test fun `a body the composer did NOT lose still overwrites, shortened or emptied`() = runTest {
        // ⛔ The witness that forbids turning the guard on `existing != null`: a draft the user cuts
        // down, or empties on purpose, must still take. Guarded on the row alone, no re-save could
        // ever shorten a draft again.
        save(body = LONG, upload = offline)
        val id = requireNotNull(stored())["id"]!!

        save(body = "cut down to one line", replacesEmailId = id, upload = offline)
        assertEquals("cut down to one line", requireNotNull(stored())["textBody"])

        save(body = "", replacesEmailId = id, upload = offline)
        assertEquals("emptying a draft on purpose still takes", "", requireNotNull(stored())["textBody"])
    }

    @Test fun `a reopened HTML draft re-saved with its text keeps every word typed since`() = runTest {
        // ⛔ The witness that forbids turning the guard on `bodyIsLossy` alone: ANY draft reopened
        // from the server with an HTML body arms it (`reopenedDraftIsLossy`, DraftSave.kt:190) while
        // the composer plainly HAS its text. That is the everyday route, and on the flag alone every
        // re-save of such a draft would put the old body back and drop what was just typed.
        val server = "imap:acc:Drafts:12"
        save(body = LONG, bodyIsLossy = true, replacesEmailId = server, upload = offline)

        save(body = "rewritten from top to bottom", bodyIsLossy = true, replacesEmailId = server, upload = offline)

        assertEquals(
            "a body the composer HAS is what the user means to keep, marked or not",
            "rewritten from top to bottom", requireNotNull(stored())["textBody"],
        )
    }

    // ---- 4. attachments: staged durably, or no row at all --------------------------------------------

    @Test fun `attachments are copied somewhere durable and described on the row`() = runTest {
        val source = File(filesDir, "compose-cache").apply { mkdirs() }
        val attached = File(source, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        save(
            attachments = listOf(EmailBodyPart(partId = attached.absolutePath, name = "photo.jpg", size = 4L)),
            upload = offline,
        )

        val stored = OutboxAttachments.decode(requireNotNull(stored())["attachmentsJson"])
        assertEquals(1, stored.size)
        assertEquals("photo.jpg", stored.single().name)
        val copy = File(stored.single().path!!)
        assertTrue("the bytes must outlive the composer's cache", copy.exists())
        assertEquals(listOf<Byte>(1, 2, 3, 4), copy.readBytes().toList())
        assertNotEquals("a copy, not the composer's own file", attached.absolutePath, copy.absolutePath)
    }

    @Test fun `a row is not uploadable while its files are still being copied`() = runTest {
        // ⛔ The orphan window the catch could never close: the row is written saying "[]" and the
        // bytes are copied AFTER. An exception rolls the row back; a process KILLED between the two
        // upserts does not, and what survives is a draft that a later upload would send amputated of
        // the very files the composer was showing as chips (#70).
        //
        // Both readings run the shipped `pending` statement, which is what an upload worker asks.
        val source = File(filesDir, "compose-cache").apply { mkdirs() }
        val attached = File(source, "notes.txt").apply { writeText("hello") }
        var stateDuringStaging: String? = null
        var pendingDuringStaging = -1

        save(
            attachments = listOf(EmailBodyPart(partId = attached.absolutePath, name = "notes.txt", size = 5L)),
            stagingProbe = {
                stateDuringStaging = stored()?.get("state")
                pendingDuringStaging = pending().size
            },
            upload = offline,
        )

        assertEquals(
            "the text is durable from the first write — that is not negotiable",
            TEXT, requireNotNull(stored())["textBody"],
        )
        assertEquals(
            "⛔ but it is not offered to an upload while its attachmentsJson still says \"[]\"",
            LocalDraftState.STAGING.name, stateDuringStaging,
        )
        assertEquals("no worker may pick that row up", 0, pendingDuringStaging)
        assertEquals(
            "and the moment the files are described, it becomes uploadable",
            LocalDraftState.PENDING.name, requireNotNull(stored())["state"],
        )
        // Read past the backoff the failed upload in this very call armed: the row is uploadable,
        // it is simply not due at the instant it failed. See the backoff test above.
        assertEquals(1, pending(nowMillis = NOW + 30_000L).size)
    }

    @Test fun `an attachment that cannot be read leaves no row and reaches no server`() {
        var uploaded = false
        val gone = File(filesDir, "compose-cache/vanished.pdf")

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                save(
                    attachments = listOf(EmailBodyPart(partId = gone.absolutePath, name = "vanished.pdf")),
                    upload = { uploaded = true; DraftSaveOutcome.SAVED },
                )
            }
        }

        assertTrue(
            "the staging must refuse rather than shorten the draft (#70)",
            failure.message.orEmpty().contains("vanished.pdf"),
        )
        assertNull(
            "⛔ a row that survived a failed staging would be uploaded amputated later, silently",
            stored(),
        )
        assertFalse("nothing amputated may reach the server either", uploaded)
    }

    @Test fun `a re-save whose attachment cannot be read keeps the draft already saved`() {
        // ⛔ THE loss. The rollback of a failed staging was unconditional, and `discard` is
        // `discardLocalDraft`: `deleteById` PLUS `localDraftDir(id).deleteRecursively()`, and that
        // directory belongs to the DRAFT, not to this save. So: save with no network (the text is
        // safe), edit, save again — and if one attachment has become unreadable in between, the
        // rollback took away the row of the FIRST save, its text and every byte staged with it.
        //
        // The trigger is not exotic: the composer's sources sit in `cacheDir`, which Android
        // evicts under pressure, which "clear cache" empties, and which signing ANOTHER account
        // out empties too (StorageRepository.clearAttachments is not scoped per account).
        val source = File(filesDir, "compose-cache").apply { mkdirs() }
        val attached = File(source, "notes.txt").apply { writeText("hello") }
        val part = EmailBodyPart(partId = attached.absolutePath, name = "notes.txt", size = 5L)
        val server = "imap:acc:Drafts:12"
        runBlocking {
            save(body = "first pass", attachments = listOf(part), replacesEmailId = server, upload = offline)
        }
        val first = requireNotNull(stored())
        val staged = File(OutboxAttachments.decode(first["attachmentsJson"]).single().path!!)
        assertTrue("the first save staged its bytes", staged.exists())

        attached.delete()
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                save(body = "second pass", attachments = listOf(part), replacesEmailId = server, upload = offline)
            }
        }

        assertTrue("the error is still reported: ${failure.message}", "notes.txt" in failure.message.orEmpty())
        val kept = requireNotNull(stored()) { "⛔ the rollback deleted the draft that WAS saved" }
        assertEquals("one draft, one row", listOf(ACCOUNT to first["id"]!!), allRows())
        assertEquals(
            "the newest text the user typed is what is kept — writing it first is the promise",
            "second pass", kept["textBody"],
        )
        assertEquals(
            "⛔ and the descriptors of the save that DID work are untouched",
            first["attachmentsJson"], kept["attachmentsJson"],
        )
        assertTrue("⛔ as are their bytes", staged.exists())
        assertEquals("the draft is still uploadable", LocalDraftState.PENDING.name, kept["state"])
        assertEquals(1, pending(nowMillis = NOW + 30_000L).size)
        assertEquals("nothing was consumed", emptyList<String>(), discarded)
    }

    @Test fun `the first write of a re-save does not downgrade the draft already saved`() = runTest {
        // ⛔ The aggravating half, and it needs no exception at all: the first upsert of a re-save
        // wrote the row back as STAGING with attachmentsJson "[]". A process killed right there
        // (the composer is closing, the phone is out of memory) leaves a row that `pending`, the
        // deferred worker and the startup re-arm all walk past — a draft that would have gone out
        // becomes one nobody reads. What the row already carries has to hold until the new staging
        // has succeeded.
        val source = File(filesDir, "compose-cache").apply { mkdirs() }
        val attached = File(source, "notes.txt").apply { writeText("hello") }
        val part = EmailBodyPart(partId = attached.absolutePath, name = "notes.txt", size = 5L)
        val server = "imap:acc:Drafts:12"
        save(body = "first pass", attachments = listOf(part), replacesEmailId = server, upload = offline)
        val first = requireNotNull(stored())

        var duringStaging: Map<String, String?>? = null
        var pendingDuringStaging = -1
        save(
            body = "second pass", attachments = listOf(part), replacesEmailId = server,
            stagingProbe = {
                duringStaging = stored()
                pendingDuringStaging = pending(nowMillis = NOW + 30_000L).size
            },
            upload = offline,
        )

        assertEquals(
            "the newest text is durable at once — that half is not negotiable",
            "second pass", duringStaging?.get("textBody"),
        )
        assertEquals(
            "⛔ but the files already staged are still described on the row, not replaced by \"[]\"",
            first["attachmentsJson"], duringStaging?.get("attachmentsJson"),
        )
        assertEquals(
            "⛔ and the row is not pushed into STAGING, which every reader of the queue skips",
            LocalDraftState.PENDING.name, duringStaging?.get("state"),
        )
        assertEquals(
            "a process killed at this exact instant leaves a draft that still reaches the server",
            1, pendingDuringStaging,
        )
    }

    // ---- 5. ⛔ the destruction guards -----------------------------------------------------------------

    @Test fun `an upload that failed destroys nothing on the server`() = runTest {
        var destroyed = false
        // The shipped shape of the upload: append (or Email/set create) FIRST, and only then
        // finishDraftSave → destroyDraft on the copy being replaced.
        val appendThenDestroy: suspend (LocalDraftEntity) -> DraftSaveOutcome = {
            append()
            destroyed = true
            DraftSaveOutcome.SAVED
        }
        save(replacesEmailId = "imap:acc:Drafts:12", upload = appendThenDestroy)

        assertFalse(
            "⛔ offline the original is the ONLY copy the server holds, and it stays",
            destroyed,
        )
        assertEquals(
            "the row remembers what it will replace once it does get through",
            "imap:acc:Drafts:12", requireNotNull(stored())["replacesEmailId"],
        )
    }

    @Test fun `a local id is never handed on as a server draft to destroy`() = runTest {
        // ⛔ The id of a phone-only draft names nothing the server issued. Passed on, it would reach
        // destroyDraft — and on IMAP a cache id ends in a UID, which always means SOMETHING.
        assertNull(serverDraftReplacedBy(LOCAL_DRAFT_ID_PREFIX + "d1"))
        assertEquals("imap:acc:Drafts:12", serverDraftReplacedBy("imap:acc:Drafts:12"))
        assertEquals("Mff8aa", serverDraftReplacedBy("Mff8aa"))
        assertNull(serverDraftReplacedBy(null))

        save(replacesEmailId = LOCAL_DRAFT_ID_PREFIX + "d1", upload = offline)
        assertNull(
            "re-saving the phone's own draft replaces nothing on the server",
            requireNotNull(stored())["replacesEmailId"],
        )
    }

    @Test fun `a re-save never rubs out the server draft the row already stands in for`() {
        // ⛔ The reachable route, and the one the reopen volet opened: a draft saved offline FROM a
        // server draft carries `replacesEmailId` + the numbering it was read under. Reopened from
        // the phone's own store the composer holds the LOCAL id — `sendDraftEmailId` /
        // `serverDraftReplacedBy` answer null for it, correctly — and the re-save then arrived here
        // with both columns null. Written over the row, the link is GONE: when the network comes
        // back the original stays in Drafts beside the new copy, for ever, and the list stops
        // masking it. Nothing is destroyed, so it is the safe direction — and it is still the
        // mechanism disarmed.
        val existing = row(id = LOCAL_DRAFT_ID_PREFIX + "d1")
            .copy(replacesEmailId = "imap:acc:Drafts:12", replacesUidValidity = 4242L)
        val resaved = localDraftRow(
            accountId = ACCOUNT,
            id = existing.id,
            messageId = "v2@example.org",
            to = listOf("bob@example.org"),
            cc = emptyList(),
            bcc = emptyList(),
            subject = "Half written",
            body = "second pass",
            replacesEmailId = serverDraftReplacedBy(existing.id),
            replacesUidValidity = null,
            existing = existing,
            nowMillis = NOW,
        )
        assertEquals("second pass", resaved.textBody)
        assertEquals(
            "the draft this row stands in for survives the re-save",
            "imap:acc:Drafts:12", resaved.replacesEmailId,
        )
        assertEquals(
            "…and the numbering WITH it: an id kept beside a numbering read at another moment is " +
                "the pair #99 exists to refuse, so the two move together or not at all",
            4242L, resaved.replacesUidValidity,
        )
    }

    @Test fun `the replaced draft is written at the row's creation and never again`() {
        // The other side of the rule, stated so it can be falsified: a re-save cannot ARM a
        // replacement either. It is unreachable by construction today — `localDraftTarget` only
        // ever hands back an existing row found BY that same server id, or one found by a local id
        // for which `serverDraftReplacedBy` answers null — and pinning it is what keeps the two
        // columns a property of the row rather than of the last save that touched it.
        val existing = row(id = LOCAL_DRAFT_ID_PREFIX + "d2")
        val resaved = localDraftRow(
            accountId = ACCOUNT, id = existing.id, messageId = "v2@example.org",
            to = listOf("bob@example.org"), cc = emptyList(), bcc = emptyList(),
            subject = "Half written", body = "second pass",
            replacesEmailId = "imap:acc:Drafts:99", replacesUidValidity = 7L,
            existing = existing, nowMillis = NOW,
        )
        assertNull(resaved.replacesEmailId)
        assertNull(resaved.replacesUidValidity)

        val created = localDraftRow(
            accountId = ACCOUNT, id = LOCAL_DRAFT_ID_PREFIX + "d3", messageId = "v1@example.org",
            to = listOf("bob@example.org"), cc = emptyList(), bcc = emptyList(),
            subject = "Half written", body = "first pass",
            replacesEmailId = "imap:acc:Drafts:99", replacesUidValidity = 7L,
            existing = null, nowMillis = NOW,
        )
        assertEquals("at CREATION they are written, or a re-save could never arm one", "imap:acc:Drafts:99", created.replacesEmailId)
        assertEquals(7L, created.replacesUidValidity)
    }

    // ---- 6. which row a save lands on ------------------------------------------------------------------

    @Test fun `the two keys a draft is found by, and the one case that mints`() = runTest {
        val local = LOCAL_DRAFT_ID_PREFIX + "d1"
        val held = row(id = local).copy(replacesEmailId = "imap:acc:Drafts:12")
        val asked = mutableListOf<String>()
        val byId: suspend (String) -> LocalDraftEntity? = { asked += "id:$it"; held.takeIf { _ -> it == local } }
        val byServer: suspend (String) -> LocalDraftEntity? =
            { asked += "server:$it"; held.takeIf { _ -> it == "imap:acc:Drafts:12" } }

        assertEquals(
            "the phone's own draft re-saves onto itself, found by id",
            LocalDraftTarget(local, held), localDraftTarget(local, byId, byServer) { "minted" },
        )
        assertEquals(
            "⛔ a SERVER draft this phone already holds a row for lands on THAT row: a fresh id " +
                "here is a second local draft, a second Message-ID and a second upload per save",
            LocalDraftTarget(local, held),
            localDraftTarget("imap:acc:Drafts:12", byId, byServer) { "minted" },
        )
        assertEquals(
            "a server draft the phone holds nothing for starts a row",
            LocalDraftTarget("minted", null),
            localDraftTarget("imap:acc:Drafts:99", byId, byServer) { "minted" },
        )
        assertEquals(
            "nothing replaced: a new draft, and no lookup at all",
            LocalDraftTarget("minted", null), localDraftTarget(null, byId, byServer) { "minted" },
        )
        assertEquals(
            "each id is asked of the ONE store that could hold it — a local id is not a server id",
            listOf("id:$local", "server:imap:acc:Drafts:12", "server:imap:acc:Drafts:99"), asked,
        )
    }

    /**
     * ⭐ **Frozen for the worker's retries, re-minted when the USER saves** — and this function is
     * only ever called on the second of those, `MailRepository.uploadLocalDraft` never rebuilding a
     * row. The rule it used to state, "minted once and then belongs to the row", is what made
     * "found on the server ⇒ do not append" able to lose the newest version of a text: see
     * [LocalDraftVersionIsNamedTest], which executes that sequence.
     */
    @Test fun `every save by the user mints the name, and a retry inherits it`() {
        val existing = row(messageId = "kept@example.org")
        assertEquals(
            "⛔ a save writes a name of its own, whatever the row already carried",
            "fresh@example.org", localDraftMessageId(existing) { "fresh@example.org" },
        )
        assertEquals("fresh@example.org", localDraftMessageId(null) { "fresh@example.org" })
        assertEquals("u1@example.org", newMessageId("alex@example.org", "u1"))
        assertEquals(
            "a login with no domain still yields a syntactically valid Message-ID",
            "u1@localhost", newMessageId("alex", "u1"),
        )
    }

    // ---- 6 bis. the row builder: every argument reaches the row -------------------------------------------

    /**
     * ⭐ **The mutation that survived the first pass, executed.** `bodyIsLossy = bodyIsLossy` →
     * `= false` at the call site compiled and left every test green, and both uploads read their
     * destroy verdict off THIS row: a flattened HTML original would then be judged faithfully
     * reproduced and destroyed, with the #63 guard still nominally in place.
     *
     * So each argument is given a value nothing else could produce, and the entity is read for it.
     * The call SITE cannot be executed (`MailRepository` needs Room, a Context and a socket) and is
     * pinned line by line in [LocalDraftSaveWiringTest]; this is what the site feeds into.
     */
    @Test fun `every argument of the row builder lands on the row`() {
        val previous = row().copy(messageId = "old@example.org", createdAtMillis = 111L)

        val built = localDraftRow(
            accountId = "acc-7",
            id = "local-draft:x",
            messageId = "frozen@example.org",
            to = listOf("bob@example.org", " carol@example.org ", " "),
            cc = listOf("dave@example.org"),
            bcc = listOf("erin@example.org"),
            subject = "Half written",
            body = TEXT,
            html = "<p>typed</p>",
            fromName = "Alex",
            fromEmail = "alex@example.org",
            inReplyTo = listOf("parent@example.org"),
            references = listOf("root@example.org", "parent@example.org"),
            replacesEmailId = "imap:acc:Drafts:12",
            replacesUidValidity = 4242L,
            bodyIsLossy = true,
            requestReceipt = true,
            existing = previous,
            nowMillis = NOW,
        )

        assertEquals("acc-7", built.accountId)
        assertEquals("local-draft:x", built.id)
        assertEquals("frozen@example.org", built.messageId)
        assertEquals("bob@example.org,carol@example.org", built.toAddresses)
        assertEquals("dave@example.org", built.cc)
        assertEquals("erin@example.org", built.bcc)
        assertEquals("Half written", built.subject)
        assertEquals(TEXT, built.textBody)
        assertEquals("<p>typed</p>", built.htmlBody)
        assertEquals("Alex", built.fromName)
        assertEquals("alex@example.org", built.fromEmail)
        assertEquals("parent@example.org", built.inReplyTo)
        assertEquals("root@example.org parent@example.org", built.references)
        assertNull(
            "⛔ the ONE pair of arguments that deliberately does NOT land on a RE-save (#95): the " +
                "server draft this row stands in for, and the numbering that id belongs to, are " +
                "written at the row's CREATION and never again — they are a property of the DRAFT, " +
                "not of the last save that touched it. `previous` here carries neither, so neither " +
                "is written. The reason is the reopen: a draft reopened from the phone's own store " +
                "hands back its LOCAL id, `serverDraftReplacedBy` answers null for it — correctly — " +
                "and the re-save then rubbed the link out. See `a re-save never rubs out the server " +
                "draft the row already stands in for` and `the replaced draft is written at the " +
                "row's creation and never again`, which execute both directions.",
            built.replacesEmailId,
        )
        assertNull(built.replacesUidValidity)
        assertTrue(
            "⛔ the flag both uploads weigh before destroying the original (#63): false on the row " +
                "authorises destroying an HTML draft this composer could only flatten to text",
            built.bodyIsLossy,
        )
        assertTrue(built.requestReceipt)
        assertEquals("the draft was created when it was first written, not at this save", 111L, built.createdAtMillis)
        assertEquals(NOW, built.updatedAtMillis)
        assertEquals(NOW, built.notBeforeMillis)
    }

    @Test fun `a first save of a draft is created now and carries no history`() {
        val built = localDraftRow(
            accountId = ACCOUNT, id = "local-draft:x", messageId = "m@example.org",
            to = listOf("bob@example.org"), cc = emptyList(), bcc = emptyList(),
            subject = "", body = "", existing = null, nowMillis = NOW,
        )
        assertEquals(NOW, built.createdAtMillis)
        assertNull(built.cc)
        assertNull(built.bcc)
        assertNull(built.inReplyTo)
        assertNull(built.references)
        assertEquals("[]", built.attachmentsJson)
        assertFalse(built.bodyIsLossy)
        assertFalse(built.requestReceipt)
    }

    // ---- 6 ter. a malformed address is an error of entry, not an outage ----------------------------------

    @Test fun `an address with a line break is refused before any row exists`() {
        // ⛔ Checked while the row is BUILT, i.e. before it is written and long before the upload.
        // Checked only inside localDraftOutgoing, as it was, the throw landed inside
        // saveDraftLocalFirst's catch and came back as KEPT_ON_DEVICE: the screen said the draft was
        // kept because of the network, while on IMAP that draft could never leave the phone (every
        // attempt throws at the same line) and on JMAP the very same address went out untouched,
        // because Email/set carries structured addressing and builds no header.
        for (field in listOf("to", "cc", "bcc")) {
            val injected = listOf("bob@example.org\nBcc: victim@evil.com")
            val failure = assertThrows(IllegalArgumentException::class.java) {
                localDraftRow(
                    accountId = ACCOUNT, id = "local-draft:x", messageId = "m@example.org",
                    to = if (field == "to") injected else listOf("bob@example.org"),
                    cc = if (field == "cc") injected else emptyList(),
                    bcc = if (field == "bcc") injected else emptyList(),
                    subject = "Half written", body = TEXT, nowMillis = NOW,
                )
            }
            assertTrue("the $field field must be refused: ${failure.message}", "line break" in failure.message.orEmpty())
        }
    }

    @Test fun `an address merely pasted with surrounding whitespace is not an injection`() {
        // The witness. The stored form is trimmed, so a trailing newline off a paste disappears
        // before any header is built — refusing it would turn a benign paste into an error banner.
        val built = localDraftRow(
            accountId = ACCOUNT, id = "local-draft:x", messageId = "m@example.org",
            to = listOf("bob@example.org\n", "\n carol@example.org "), cc = emptyList(), bcc = emptyList(),
            subject = "", body = TEXT, nowMillis = NOW,
        )
        assertEquals("bob@example.org,carol@example.org", built.toAddresses)
    }

    @Test fun `a save whose addressing is malformed writes nothing at all`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { save(cc = listOf("bob@example.org\r\nBcc: victim@evil.com"), upload = offline) }
        }
        assertTrue("line break" in failure.message.orEmpty())
        assertNull("⛔ a typing mistake must not be filed away as a draft kept for the network", stored())
    }

    // ---- 7. what the IMAP APPEND actually carries -------------------------------------------------------

    @Test fun `the appended draft carries the row's own Message-ID and addressing`() {
        val row = row(
            messageId = "frozen@example.org",
            toAddresses = "bob@example.org,carol@example.org",
            cc = "dave@example.org",
            bcc = "erin@example.org",
        ).copy(
            subject = "Half written", textBody = TEXT,
            inReplyTo = "parent@example.org", references = "root@example.org parent@example.org",
            requestReceipt = true,
        )

        val message = localDraftOutgoing(row, "alex@example.org", emptyList(), NOW)

        assertEquals(
            "⛔ the Message-ID on the wire IS the row's. Minted at the APPEND, as it used to be, " +
                "nothing the phone kept names what the server received, and a later upload cannot " +
                "ask UID SEARCH HEADER whether this draft is already there",
            "frozen@example.org", message.messageId,
        )
        assertEquals(listOf("bob@example.org", "carol@example.org"), message.to)
        assertEquals(listOf("dave@example.org"), message.cc)
        assertEquals(listOf("erin@example.org"), message.bcc)
        assertEquals("Half written", message.subject)
        assertEquals(TEXT, message.body)
        assertEquals("parent@example.org", message.inReplyTo)
        assertEquals("root@example.org parent@example.org", message.references)
        assertEquals("the receipt the row asked for is what the server copy says", true, message.requestReceipt)
        assertEquals("no chosen identity: the login address", "alex@example.org", message.from)
        assertEquals(NOW, message.dateMillis)
    }

    @Test fun `the chosen identity is the From, quoted as a header needs`() {
        val row = row().copy(fromName = "Alex, Jr.", fromEmail = "alex@example.org")
        assertEquals("\"Alex, Jr.\" <alex@example.org>", localDraftOutgoing(row, "login@example.org", emptyList(), NOW).from)
    }

    @Test fun `an address carrying a line break never becomes a header`() {
        val row = row(toAddresses = "bob@example.org\nBcc: victim@evil.com")
        assertThrows(IllegalArgumentException::class.java) {
            localDraftOutgoing(row, "alex@example.org", emptyList(), NOW)
        }
    }

    // ---- 8. the numbering frozen with the row ------------------------------------------------------------

    @Test fun `the UIDVALIDITY recorded is the one of the folder the replaced id names`() = runTest {
        val asked = mutableListOf<String>()
        val recorded: suspend (String) -> Long? = { asked += it; 4242L }

        assertEquals(4242L, localDraftUidValidity("imap:acc:INBOX/Drafts:12", recorded))
        assertEquals(
            "the folder asked about must be the one encoded in the id being replaced, ':' and all",
            listOf("INBOX/Drafts"), asked,
        )

        assertNull("nothing replaced, nothing to freeze", localDraftUidValidity(null, recorded))
        assertNull("a JMAP id has no numbering at all", localDraftUidValidity("Mff8aa", recorded))
        assertNull("neither has the phone's own id", localDraftUidValidity(LOCAL_DRAFT_ID_PREFIX + "d1", recorded))
        assertEquals("none of those three may query the store", listOf("INBOX/Drafts"), asked)
    }

    // ---- 9. what the screen does with the outcome ----------------------------------------------------------

    @Test fun `a draft kept on the phone is not something the composer says anything about`() {
        assertFalse(
            "⛔ a notice here would be issue #95 with softer wording",
            draftSaveNeedsNotice(DraftSaveOutcome.KEPT_ON_DEVICE),
        )
        assertFalse(draftSaveNeedsNotice(DraftSaveOutcome.SAVED))
        assertTrue(
            "the one outcome worth a word: the edited original was deliberately left behind (#63)",
            draftSaveNeedsNotice(DraftSaveOutcome.ORIGINAL_KEPT),
        )
    }

    @Test fun `a draft that never reached the server does not consume the queued row it came from`() {
        // ⛔ The destructive half of the save, and the loss the first pass shipped: a message waiting
        // to be sent, "edit", "save", no network. deleteOutbox drops the queued row AND its staged
        // attachment directory — and until local drafts are displayed, the only copy left is one
        // nothing shows. Before the draft was written locally at all, saveDraft simply threw here
        // and the row stayed in the queue, visible on the Outbox screen.
        assertFalse(
            "the upload did not happen, so nothing may be destroyed on the strength of it (#95×#70)",
            draftSaveConsumesTheQueuedRow(DraftSaveOutcome.KEPT_ON_DEVICE),
        )
        assertTrue(
            "the draft is in Drafts: giving the queued row back would send what the user chose to " +
                "keep as a draft",
            draftSaveConsumesTheQueuedRow(DraftSaveOutcome.SAVED),
        )
        assertTrue(
            "ORIGINAL_KEPT is about the OLD server copy (#63) — this draft did reach the server",
            draftSaveConsumesTheQueuedRow(DraftSaveOutcome.ORIGINAL_KEPT),
        )
    }

    @Test fun `a queued row that survives its save is parked where it can be seen, not left EDITING`() {
        // ⛔ The pairs are written out, never recomputed from the shipped rule: a test that asks the
        // function which state to expect stays green when the rule is inverted.
        assertEquals(
            "⛔ the row the composer was editing must be PARKED, in KEPT_AS_DRAFT. Left in EDITING " +
                "it is on no screen (Outbox screen and badge both filter EDITING out), and the " +
                "next launch reverts it to QUEUED and sends the pre-edit message — the user tapped " +
                "'save as draft', not 'send' (#95 × #70). And parking it in FAILED is the other " +
                "wrong answer: it is not a failure, it lights the Inbox banner for good and paints " +
                "the row red over a save that did exactly what was asked",
            OutboxState.KEPT_AS_DRAFT,
            draftSaveParksTheQueuedRowAs(DraftSaveOutcome.KEPT_ON_DEVICE),
        )
        assertNull(
            "the draft reached Drafts, so the queued row is CONSUMED — there is nothing to park",
            draftSaveParksTheQueuedRowAs(DraftSaveOutcome.SAVED),
        )
        assertNull(
            "ORIGINAL_KEPT is about the old server copy (#63); this row is consumed too",
            draftSaveParksTheQueuedRowAs(DraftSaveOutcome.ORIGINAL_KEPT),
        )
    }

    @Test fun `the state a kept-on-device save parks in is one that shows and does not send itself`() {
        // The five properties the parked state has to have, EXECUTED against the shipped outbox
        // rules rather than restated in prose: a state that fails any one of them is either
        // invisible (the reported defect), sent on its own (the worse half of it), or shouting
        // about a failure that never happened (what parking it in FAILED did).
        val parked = draftSaveParksTheQueuedRowAs(DraftSaveOutcome.KEPT_ON_DEVICE)
            ?: error("nothing is parked at all — see the pinned pairs above")
        assertFalse(
            "⛔ the send worker must never pick it up: the user chose to save, not to send",
            OutboxLogic.isReadyToSend(parked, notBeforeMillis = 0L, now = NOW),
        )
        assertTrue(
            "the Outbox screen lists exactly the rows this accepts — the message has to be there",
            OutboxLogic.isWaitingInOutbox(parked),
        )
        assertEquals(
            "and the badge must show it AT ONCE, with no grace: the message is not on its way",
            1,
            OutboxLogic.activeCount(listOf(OutboxBadgeItem(parked, notBeforeMillis = NOW)), NOW),
        )
        assertTrue(
            "it must still reopen, or 'Retry'/'Edit' cannot send it by hand",
            OutboxLogic.canEdit(pgpMode = null, state = parked, carriesUnreplayablePrebuiltEntity = false),
        )
        assertFalse(
            "⛔ and it must NOT raise the Inbox's failure banner: 'Some messages didn't send. Tap " +
                "to review.' across the Inbox, plus a red dot, over a draft saved exactly as the " +
                "user asked — and nothing would ever clear it, because there is no deferred upload " +
                "whose success could take it back down (#95)",
            OutboxLogic.needsFailureBanner(parked),
        )
        assertEquals(
            "the same, through the count the banner is built on",
            0,
            OutboxLogic.failedCount(listOf(parked)),
        )
    }

    @Test fun `the parked row schedules no badge wake-up, because it already counts`() {
        // The clock side of the same choice: a state that counts at once has no threshold ahead of
        // it, so badgeCount must not arm a timer for it. Slipped onto the graced side, the row
        // would be counted from the first emission AND book a wake-up 30 s later — and, worse for
        // reading the code, "counts at once" and "waits out the grace" would both be true of it.
        val parked = draftSaveParksTheQueuedRowAs(DraftSaveOutcome.KEPT_ON_DEVICE)
            ?: error("nothing is parked at all — see the pinned pairs above")
        assertNull(
            "a row that already counts has no next change to schedule",
            OutboxLogic.nextBadgeChange(listOf(OutboxBadgeItem(parked, notBeforeMillis = NOW)), NOW),
        )
    }

    // ---- 9. a token that cannot be an address goes out as a group naming nobody ---------------

    /**
     * ⭐ THE case, measured on `emu`/Stalwart on 2026-08-26. Appending `Cc: undisclosed-recipients`
     * as a bare atom makes Stalwart answer `cc = NIL` for that message: the value never reaches the
     * parser, the second open shows an EMPTY Cc, and the second save reads "addressing proved
     * empty" and expunges the copy that still held the header (UID 431 gone, UID 432 with no `Cc:`
     * at all). Written as RFC 5322 group syntax the same field survives.
     */
    @Test fun `a Cc that cannot be an address is appended as a group naming nobody`() {
        val row = row(cc = "undisclosed-recipients")

        assertEquals(
            "⛔ a bare atom is what the server drops on the floor — the APPEND must write the " +
                "group form, which Stalwart hands back as a start and an end",
            listOf("undisclosed-recipients:;"),
            localDraftOutgoing(row, "alex@example.org", emptyList(), NOW).cc,
        )
    }

    /** All THREE fields, so "only the cc is rewritten" is not a shape this can be left in. */
    @Test fun `the To the Cc and the Bcc all write the group form`() {
        val row = row(toAddresses = "the-team", cc = "undisclosed-recipients", bcc = "blind-list")

        val message = localDraftOutgoing(row, "alex@example.org", emptyList(), NOW)

        assertEquals(listOf("the-team:;"), message.to)
        assertEquals(listOf("undisclosed-recipients:;"), message.cc)
        assertEquals(
            "the Bcc too: a blind copy only survives a save because the appended draft carries the " +
                "header, so a token lost there is lost for good",
            listOf("blind-list:;"), message.bcc,
        )
    }

    /**
     * ⛔ THE witness, and the one that matters most: a real address goes out UNTOUCHED. `a@b` is
     * here on purpose — `isValidEmail` (the composer's typo-catcher) rejects it, RFC 5322 does not,
     * and judging with that function would send `a@b:;` to the server and destroy a real address.
     */
    @Test fun `a real address is appended unchanged`() {
        val row = row(
            toAddresses = "bob@exemple.org",
            cc = "a@b",
            bcc = "Alex <alex@exemple.org>",
        )

        val message = localDraftOutgoing(row, "alex@example.org", emptyList(), NOW)

        assertEquals(listOf("bob@exemple.org"), message.to)
        assertEquals("a@b is a valid addr-spec, and rewriting it would destroy a recipient", listOf("a@b"), message.cc)
        assertEquals(listOf("Alex <alex@exemple.org>"), message.bcc)
    }

    /** A token that already holds a group delimiter is left alone: `foo:bar:;` is a worse header. */
    @Test fun `a token already carrying a group delimiter is appended unchanged`() {
        val row = row(toAddresses = "foo:bar", cc = "already-a-group:;")

        val message = localDraftOutgoing(row, "alex@example.org", emptyList(), NOW)

        assertEquals(listOf("foo:bar"), message.to)
        assertEquals(listOf("already-a-group:;"), message.cc)
    }

    /** Mixed in one field: only the token that cannot be an address moves, and the order stands. */
    @Test fun `only the token that cannot be an address is rewritten, in place`() {
        val row = row(cc = "bob@exemple.org,undisclosed-recipients,carol@exemple.org")

        assertEquals(
            listOf("bob@exemple.org", "undisclosed-recipients:;", "carol@exemple.org"),
            localDraftOutgoing(row, "alex@example.org", emptyList(), NOW).cc,
        )
    }

    /**
     * ⭐ D1 — what a user who KNOWS the syntax actually produces. Typing `undisclosed-recipients:;`
     * into the Cc goes through the composer's `parseAddrs`, which splits on `,` AND on `;`
     * (`ComposeText.kt:664`): the stored token is `undisclosed-recipients:` — a group start with no
     * end, which is not group syntax either. Appended as such it is the SAME loss, replayed: an
     * empty Cc on the second open and the copy expunged.
     *
     * ⛔ The rule is "ENDS with a `:`", never "holds one": `foo:bar` is left alone here, in the same
     * test, or lengthening it to `foo:bar:;` would be a new malformed header.
     */
    @Test fun `a trailing colon is completed into the group form, an inner one is left alone`() {
        val row = row(toAddresses = "foo:bar", cc = "undisclosed-recipients:", bcc = "the-team:")

        val message = localDraftOutgoing(row, "alex@example.org", emptyList(), NOW)

        assertEquals(
            "⛔ `undisclosed-recipients:` opens a group and never closes it — the token the " +
                "composer's own splitter hands over must go out completed",
            listOf("undisclosed-recipients:;"), message.cc,
        )
        assertEquals(listOf("the-team:;"), message.bcc)
        assertEquals("an inner colon is not a group start, and must not be lengthened", listOf("foo:bar"), message.to)
    }

    /**
     * ⭐ THE case the whole thing exists for, and the one every other test here misses: a row that
     * REPLACES a server draft. `replacesEmailId == null` is a first save — there is no original, so
     * nothing can be expunged and the defect cannot happen. Every assertion above runs on that
     * harmless shape, so a fix that skipped the rewrite whenever a draft has an original would pass
     * them all while leaving the data loss exactly where it was.
     */
    @Test fun `the group form is written on the save that replaces a server draft`() {
        val row = row(cc = "undisclosed-recipients").copy(
            replacesEmailId = "imap:acc:INBOX/Drafts:431",
            replacesUidValidity = 9L,
        )

        assertEquals(
            "⛔ the replacing save is the one that expunges UID 431 — this is the path the token " +
                "has to survive, not the first save",
            listOf("undisclosed-recipients:;"),
            localDraftOutgoing(row, "alex@example.org", emptyList(), NOW).cc,
        )
    }

    /** A token holding the group TERMINATOR and no start is left alone: `the-list;:;` is worse. */
    @Test fun `a token already carrying a group terminator is appended unchanged`() {
        assertEquals(
            listOf("the-list;"),
            localDraftOutgoing(row(cc = "the-list;"), "alex@example.org", emptyList(), NOW).cc,
        )
    }

    /** A short name is a name: an internal alias like `hr` is exactly what such a header holds. */
    @Test fun `a short token is written as a group too`() {
        val message = localDraftOutgoing(row(toAddresses = "hr", cc = "team"), "alex@example.org", emptyList(), NOW)

        assertEquals(listOf("hr:;"), message.to)
        assertEquals(listOf("team:;"), message.cc)
    }

    // ---- the fixture --------------------------------------------------------------------------------------

    @Suppress("LongParameterList")
    private fun row(
        accountId: String = ACCOUNT,
        id: String = LOCAL_DRAFT_ID_PREFIX + "d1",
        messageId: String = "mid@example.org",
        toAddresses: String = "bob@example.org",
        cc: String? = null,
        bcc: String? = null,
    ) = LocalDraftEntity(
        accountId = accountId, id = id, messageId = messageId,
        toAddresses = toAddresses, cc = cc, bcc = bcc,
        subject = "Half written", textBody = TEXT,
        createdAtMillis = NOW, updatedAtMillis = NOW, notBeforeMillis = NOW,
    )

    private companion object {
        const val ACCOUNT = "acc"
        const val NOW = 1_760_000_000_000L
        const val TEXT = "I will be there at six, do not wait for me at the station"

        /** A body no parcel carries: well above the resume slot's INLINE_LIMIT of 16 384 characters. */
        val LONG = (1..600).joinToString("\n") { "line $it of what the user typed with no network" }
    }
}
