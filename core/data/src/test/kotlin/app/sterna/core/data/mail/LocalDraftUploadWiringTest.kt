package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, the last resort, as [LocalDraftSaveWiringTest] is and for the same reason:
 */
class LocalDraftUploadWiringTest {

    private fun codeOf(body: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    private fun codeLinesNaming(source: String, needle: String): List<String> =
        source.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * The body of `fun [functionName]` in `StorageRepository`, braces included.
     */
    private fun storageBody(functionName: String): String {
        val source = STORAGE.readText()
        val at = source.indexOf("fun $functionName(")
        assertTrue("StorageRepository has no $functionName( — renamed?", at >= 0)
        val open = source.indexOf('{', at)
        var depth = 0
        var i = open
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(open, i)
    }

    @Test fun `a deferred upload records its failure on the row, and consumes it only on success`() {
        // The whole body, in order. Three ways to break it that all compile:
        //  - `discard` moved out of the success path (or into a `finally`): the typed text is gone
        val body = codeOf(DaoQuerySource.mailFunctionBody("MailRepository", "uploadLocalDraft"))
        assertEquals(
            "MailRepository.uploadLocalDraft must hand uploadLocalDraftOnce the REAL store — the " +
                "row by both halves of its key, the shipped upload, the shipped discard and the " +
                "shipped recordAttempt:",
            listOf(
                "{",
                "val now = System.currentTimeMillis()",
                "return uploadLocalDraftOnce(",
                "row = localDraftDao.byId(credentials.id, id),",
                "nowMillis = now,",
                "markUploading = { localDraftDao.setState(credentials.id, id, LocalDraftState.UPLOADING) },",
                // Two bounds, at two levels, and both lines with their argument.
                //  - the read budget: `appendDraft` names none of its own, so without this element
                "upload = { row ->",
                "withLocalDraftAttemptBudget(LOCAL_DRAFT_ATTEMPT_BUDGET_MS) {",
                "withContext(ImapReadBudget(LOCAL_DRAFT_UPLOAD_BUDGET_MS)) {",
                "uploadDraft(credentials, row, localDraftUploadAttachments(row.attachmentsJson))",
                "}",
                "}",
                "},",
                "discard = { discardLocalDraft(credentials.id, it.id) },",
                "record = { attempt ->",
                "localDraftDao.recordAttempt(",
                "credentials.id, id, LocalDraftState.PENDING, attempt.attemptCount, attempt.error,",
                "attempt.atMillis, attempt.notBeforeMillis,",
                ")",
                "},",
                ")",
                "}",
            ),
            body,
        )
    }

    @Test fun `a save that could not reach the server books the deferred upload itself`() {
        // The promise of the volet, and nothing was booking it: `LocalDraftUploads.enqueue` had
        // two callers, the startup re-arm and the worker re-booking itself. A draft saved in a lift
        // therefore left the phone only after the app had died and started again, the row was
        // written, the backoff was armed, and no timer was ever attached to it.
        //
        // The seam is the outbox's, copied: the data layer books work without knowing WorkManager.
        val source = DaoQuerySource.mailSource("MailRepository")
        assertEquals(
            "MailRepository must hold a scheduler seam and use it at save time, with the delay the " +
                "attempt just wrote — `schedule = { }`, or a 0 delay, is either a draft that never " +
                "leaves or a retry storm against a server that is refusing. The third line is the " +
                "SAME promise at the other end (#95, reopen): the work item booked for a draft may " +
                "well have fired while the composer held the row EDITING, found it not due and gone " +
                "away, so giving the row back re-books one. ⛔ The DELAY it is re-booked at is not " +
                "pinned here — a line naming the seam cannot say what value goes through it — it is " +
                "computed inside `giveLocalDraftEditBack` and read back in LocalDraftReopenTest:",
            listOf(
                "var localDraftScheduler: LocalDraftScheduler? = null",
                "schedule = { job -> localDraftScheduler?.schedule(job.accountId, job.id, job.delayMillis) },",
                "schedule = { delay -> localDraftScheduler?.schedule(accountId, id, delay) },",
            ),
            codeLinesNaming(source, "localDraftScheduler"),
        )
    }

    @Test fun `the IMAP upload asks before it appends, and every guard behind it still stands`() {
        // The whole IMAP branch, in order, because two separate things have to hold at once:
        //  - the APPEND sits behind `appendDraftUnlessAlreadyThere`, fed by the real search
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "uploadDraft")
        val to = body.indexOf("return outcome")
        assertTrue("uploadDraft no longer holds an IMAP branch ending in `return outcome`", to > 0)
        assertEquals(
            "uploadDraft down to the end of its IMAP half, whole and in order:",
            listOf(
                "{",
                "val ccTrimmed = localDraftAddresses(row.cc)",
                "val bccTrimmed = localDraftAddresses(row.bcc)",
                "val replacesEmailId = row.replacesEmailId",
                "val requestReceipt = row.requestReceipt",
                "val replacesUidValidity = row.replacesUidValidity",
                "if (credentials.protocol == MailProtocol.IMAP) {",
                "val drafts = mailboxDao.idForRole(credentials.id, \"drafts\") ?: error(\"This account has no Drafts folder.\")",
                "val parts = imapDraftAttachments(attachments)",
                // `val wentOut =` is load-bearing, and dropping it compiles: the function
                // already answers "did the bytes actually go out"; thrown away, that answer is lost.
                "val wentOut = appendDraftUnlessAlreadyThere(",
                "row = row,",
                // Named, and it is not a matter of taste: `draftIsAlreadyThere` takes a folder
                // and a Message-ID, both `String`, and swapping the two at the declaration compiles
                // against a positional call. The search then selects a folder named after a message
                // id, throws, is read as "not there", and appends. Silent, and green.
                "alreadyThere = { messageId ->",
                "imap.draftIsAlreadyThere(credentials = credentials, draftsMailbox = drafts, messageId = messageId)",
                "},",
                "append = {",
                "imap.appendDraft(",
                "credentials, drafts,",
                "localDraftOutgoing(row, credentials.username, parts, System.currentTimeMillis()),",
                ")",
                "},",
                ")",
                "val addressingIsCarried = draftAddressingIsCarried(",
                "replacesEmailId = replacesEmailId,",
                // The Cc and the Bcc since 2026-08-25, the appended draft carries a real `Bcc:`
                // header now, so the server-side read has one to match. Why it says that is
                // [DraftAddressingWiringTest]'s and [DraftSaveTest]'s; this list only has to keep
                // following the shipped body, or it pins a guard the app no longer runs.
                "replacement = draftReplacementAddressing(ccTrimmed, bccTrimmed),",
                "originalAddressing = { id -> imapDraftAddressing(credentials, id, ImapBudget.NO_BUDGET) },",
                ")",
                "val receiptIsCarried = draftReceiptRequestIsCarried(",
                "replacesEmailId = replacesEmailId,",
                "replacement = requestReceipt,",
                "originalRequested = { id -> imapDraftReceiptRequested(credentials, id) },",
                ")",
                "val outcome = finishDraftSave(",
                "credentials, replacesEmailId,",
                "faithful = draftReplacementIsFaithful(",
                "replacementWentOut = wentOut,",
                "attachments.size, parts.size, row.bodyIsLossy,",
                "addressingIsCarried = addressingIsCarried,",
                "receiptRequestIsCarried = receiptIsCarried,",
                "),",
                "frozenUidValidity = replacesUidValidity,",
                ")",
                "runCatching { refresh(credentials, drafts) }",
                "return outcome",
            ),
            codeOf(body.substring(0, to + "return outcome".length)),
        )
    }

    @Test fun `every destroy guard is told whether the replacement actually went out`() {
        // The line that re-opens the defect, and it is one word: `replacementWentOut = true` on
        // the IMAP branch.
        val upload = DaoQuerySource.mailFunctionBody("MailRepository", "uploadDraft")
        assertEquals(
            "the APPEND's answer must be CAUGHT, not dropped: a bare call is the defect itself",
            listOf("val wentOut = appendDraftUnlessAlreadyThere("),
            codeLinesNaming(upload, "appendDraftUnlessAlreadyThere("),
        )
        assertEquals(
            "the IMAP branch must hand the guard the boolean the lookup just produced, and the " +
                "JMAP branch the id the SERVER returned — `client.saveDraft` is declared " +
                "`: String?` and answers null when the response names neither a created id nor a " +
                "refusal, which is an unknown, and an unknown may not authorise a destroy. " +
                "`replacementWentOut = true` on either line is the defect, restored",
            listOf(
                "replacementWentOut = wentOut,",
                "replacementWentOut = savedId != null,",
            ),
            codeLinesNaming(upload, "replacementWentOut"),
        )
        assertEquals(
            "the send path answers true, and says so exactly once",
            listOf("replacementWentOut = true,"),
            codeLinesNaming(
                DaoQuerySource.mailFunctionBody("DraftSave", "replaceableDraftIdOrNull"),
                "replacementWentOut",
            ),
        )
    }

    @Test fun `the leg is declared with no default, and production answers it in three places`() {
        // The guard is the absent default, not the position. `replacementWentOut: Boolean = true`
        // compiles, leaves all three call sites untouched, leaves every assertion above green, and
        // arms the defect for every caller written from then on, which inherits "it went out"
        assertEquals(
            "draftReplacementIsFaithful must declare `replacementWentOut: Boolean,` bare — no " +
                "`= true`, which would compile and silently arm every future caller — and weigh " +
                "it as the FIRST term of the conjunction",
            listOf(
                "replacementWentOut: Boolean,",
                "): Boolean = replacementWentOut &&",
                "replacementWentOut = true,",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("DraftSave"), "replacementWentOut"),
        )
        // And a closed set of answers: exactly three, each one read above. A fourth call site
        // appearing in either file turns this red rather than shipping an unreviewed `true`.
        assertEquals(
            "exactly three answers in production — the IMAP lookup's boolean, the JMAP server's " +
                "id, and the send's justified literal. A fourth is an unreviewed authorisation " +
                "to destroy an original",
            listOf(
                "replacementWentOut = wentOut,",
                "replacementWentOut = savedId != null,",
                "replacementWentOut = true,",
            ),
            codeLinesNaming(DaoQuerySource.mailSource("MailRepository"), "replacementWentOut =") +
                codeLinesNaming(DaoQuerySource.mailSource("DraftSave"), "replacementWentOut ="),
        )
    }

    @Test fun `the lookup behind it enumerates the folder and compares the ids here`() {
        // The whole body, and every line of it is load-bearing:
        //  - `newestUids`, not `allUids`: our copy is the last thing appended, and `allUids` keeps
        assertEquals(
            "draftIsAlreadyThere must SELECT the Drafts folder, enumerate its newest UIDs and " +
                "compare the Message-IDs here:",
            listOf(
                "{",
                "return withSession(credentials) { session ->",
                "session.select(draftsMailbox)",
                "session.fetchUids(session.newestUids(DRAFT_LOOKUP_SCAN))",
                ".any { localDraftMessageIdMatches(messageId, it.messageId) }",
                "}",
                "}",
            ),
            codeOf(DaoQuerySource.mailFunctionBody("ImapMailService", "draftIsAlreadyThere")),
        )
        // And the declaration, parameters included, the body lint above starts at the brace
        // after the closing parenthesis, so it sees nothing of the two `String`s the caller has to
        // get the right way round. [ImapDraftAppendGoesOutOnceTest] drives the function itself,
        // positionally, and reads back what the server was actually sent; this states the contract
        // that call relies on.
        val source = DaoQuerySource.mailSource("ImapMailService")
        val at = source.indexOf("suspend fun draftIsAlreadyThere(")
        assertTrue("ImapMailService no longer declares draftIsAlreadyThere( — renamed?", at >= 0)
        assertEquals(
            "the folder comes before the Message-ID, and both are named at the one call site:",
            listOf(
                "suspend fun draftIsAlreadyThere(",
                "credentials: AccountCredentials,",
                "draftsMailbox: String,",
                "messageId: String,",
                "): Boolean {",
            ),
            codeOf(source.substring(at, source.indexOf('{', at) + 1)),
        )
    }

    @Test fun `the one place every IMAP call passes reads the budget its coroutine carries`() {
        // The other half of the wiring above: `MailRepository.uploadLocalDraft` installing an
        // [ImapReadBudget] is worth nothing unless `runWithRetry` looks for it. The decision itself
        // ([ImapBudget.effective]) is executed by ImapBudgetTest; this pins that it is asked, with
        // the caller's budget and the ambient one, and that the answer becomes the deadline.
        //
        // The whole body, in order, because two mutations that compile live in it:
        //  - `ImapBudget.deadline(budgetMs, …)` back as it was: every caller keeps working, every
        assertEquals(
            "ImapMailService.runWithRetry must take ONE deadline, from the caller's budget or the " +
                "coroutine's, and feed both the connect and the reads from it:",
            listOf(
                "{",
                "val ambientMs = currentCoroutineContext()[ImapReadBudget]?.millis ?: ImapBudget.NO_BUDGET",
                "val deadline = ImapBudget.deadline(ImapBudget.effective(budgetMs, ambientMs), System.currentTimeMillis())",
                "var attempt = 0",
                "while (true) {",
                "val session = pooled.session",
                "?: imapClient.connect(config, ImapBudget.remaining(deadline, System.currentTimeMillis()))",
                ".also { pooled.session = it }",
                "val forReads = ImapBudget.remaining(deadline, System.currentTimeMillis())",
                "try {",
                "return session.withReadTimeoutSuspending(forReads) { block(session) }",
                "} catch (cancelled: CancellationException) {",
                "runCatching { session.close() }",
                "pooled.session = null",
                "throw cancelled",
                "} catch (renumbered: ImapUidValidityChanged) {",
                "throw renumbered",
                "} catch (t: Throwable) {",
                "runCatching { session.close() }",
                "pooled.session = null",
                "if (!retryOnFailure || ++attempt >= 2) throw t",
                "}",
                "}",
                "}",
            ),
            codeOf(DaoQuerySource.mailFunctionBody("ImapMailService", "runWithRetry")),
        )
        // And the deferred upload's own bound, which no executed test can read off the call site:
        // a minute is above any honest APPEND and well under the ten minutes at which WorkManager
        // stops a worker, the attempt has to record its failure inside its own run.
        assertEquals(
            listOf("const val LOCAL_DRAFT_UPLOAD_BUDGET_MS: Int = 60_000"),
            codeLinesNaming(DaoQuerySource.mailSource("LocalDraftUpload"), "LOCAL_DRAFT_UPLOAD_BUDGET_MS: Int"),
        )
    }

    @Test fun `a draft's staged bytes are built under filesDir, never a tree Android empties`() {
        // `DataFactory` is read by no executed test, it builds Room, a Context and every
        // repository. `appContext.filesDir` → `appContext.cacheDir` compiles, and the attachments
        assertEquals(
            "one tree, resolved under filesDir and handed to BOTH readers — the repository that " +
                "stages a draft's bytes and the sign-out that has to clear them:",
            listOf(
                "val localDraftFiles = java.io.File(appContext.filesDir, LOCAL_DRAFT_FILES_DIR)",
                "localDraftFilesDir = localDraftFiles,",
                "localDraftFilesDir = localDraftFiles,",
            ),
            codeLinesNaming(FACTORY.readText(), "localDraftFiles"),
        )
    }

    @Test fun `the startup re-arm reads the queue through the shipped statements`() {
        // Read over the whole file: these three are one-liners, and what has to be pinned is the
        // arguments each hands the DAO, not that a call of that name exists somewhere.
        val source = DaoQuerySource.mailSource("MailRepository")
        assertEquals(
            "the accounts to re-arm come from the DAO's own DISTINCT, never from a caller's list",
            listOf("suspend fun accountsWithLocalDrafts(): List<String> = localDraftDao.accountsWithDrafts()"),
            codeLinesNaming(source, "accountsWithDrafts("),
        )
        assertEquals(
            "⛔ the re-arm is scoped to ONE account and takes NOTHING else: an unscoped UPDATE " +
                "would reach another account's row, whose upload may be genuinely in flight in this " +
                "process. ⛔ And there is no second argument to add: the statement rescues " +
                "UPLOADING alone (LocalDraftDaoSqlTest executes it), so no id, no lease and no " +
                "exception is passed here — a call that carried one again would mean an EDITING " +
                "sweep is back",
            listOf("localDraftDao.revertUploadingToPending(accountId)"),
            codeLinesNaming(source, "revertUploadingToPending("),
        )
        assertEquals(
            "⛔ a startup must see the rows that are BACKED OFF too — asking `pending` at the " +
                "current instant would leave a draft that failed an hour ago with no work item at " +
                "all, i.e. waiting for a save that may never come",
            listOf("localDraftDao.pending(accountId, Long.MAX_VALUE)"),
            codeLinesNaming(source, "localDraftDao.pending("),
        )
    }

    @Test fun `removing an account takes its local drafts and their staged bytes with it`() {
        // A local draft is not cache: it is text the user typed that the server has not got. It
        // belongs with `snoozed` and the outbox on the user-data side, so it goes on sign-out and
        // on nothing else, not on "clear cache", not on the orphan sweep.
        //
        // The whole body, in order, because the two halves are separable and the second is the one
        // that gets forgotten: deleting the rows alone leaves the attachments of a removed account
        // on the phone with nothing left to name them.
        //
        // And one `runCatching` per step, as `purgeOrphanedAccounts` already does table by
        // table.
        assertEquals(
            "StorageRepository.purgeAccount must purge the local drafts, rows AND files, and no " +
                "step may take the ones after it down with it:",
            listOf(
                "{",
                "runCatching { emailDao.deleteForAccount(accountId) }",
                "runCatching { emailFtsDao.clearAccount(accountId) }",
                "runCatching { emailBodyDao.deleteForAccount(accountId) }",
                "runCatching { mailboxDao.deleteForAccount(accountId) }",
                "runCatching { snoozedDao.deleteForAccount(accountId) }",
                "runCatching { purgeSnapshotDao.deleteForAccount(accountId) }",
                "runCatching { mailboxUidValidityDao.deleteForAccount(accountId) }",
                "runCatching {",
                "purgeLocalDraftsOfAccount(",
                "ids = { localDraftDao.observeForAccount(accountId).first().map { it.id } },",
                "deleteRows = { localDraftDao.deleteForAccount(accountId) },",
                "dirOf = { File(localDraftFilesDir, localDraftDirName(it)) },",
                ")",
                "}",
                "runCatching { clearAttachments() }",
                "}",
            ),
            codeOf(storageBody("purgeAccount")),
        )
    }

    @Test fun `clearing the cache never touches a draft the server has not got`() {
        // The witness on the test above, and the reason the purge is in one of the four. "Clear
        // cache" and "clear this account's cache" are offered as free of consequence, every byte
        // they drop can be downloaded again. A local draft cannot: nothing else holds it.
        for (function in listOf("clearAllCache", "clearAccountCache")) {
            assertEquals(
                "⛔ $function must not name the local drafts: it is offered as a purge of " +
                    "re-downloadable bytes, and this text exists nowhere else",
                emptyList<String>(),
                codeOf(storageBody(function)).filter { "localDraft" in it || "LocalDraft" in it },
            )
        }
    }

    private companion object {
        val STORAGE: File by lazy {
            repoFile("core/data/src/main/kotlin/app/sterna/core/data/storage/StorageRepository.kt")
        }
        val FACTORY: File by lazy {
            repoFile("core/data/src/main/kotlin/app/sterna/core/data/DataFactory.kt")
        }

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot locate $path from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
    }
}
