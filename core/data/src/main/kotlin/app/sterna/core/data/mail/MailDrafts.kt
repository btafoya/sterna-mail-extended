package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStorage
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.imap.ImapUidValidityChanged
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** The budget for the emptying gestures, before they may destroy the server copy of a draft judged
 *  empty. Bounded rather than [ImapBudget.NO_BUDGET]: this gesture is reached when there is no
 *  network (#95), and an addressing that could not be established is not an empty one. */
private const val EMPTIED_DRAFT_PROOF_BUDGET_MS = 10_000

/**
 * What [MailDrafts] needs from [MailRepository] and nothing more. It is an interface so the save
 * and the upload can be entered in a JVM test with a stand-in: `uploadDraft` is 130 lines that no
 * test could execute while reaching them meant building the repository, its eleven DAOs and a warm
 * JMAP session.
 */
interface MailDraftHost {

    /** The account's JMAP session, from the repository's cache when it is warm. */
    suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession

    /** Reload one folder into the cache. An IMAP APPEND returns no id to key a row on, and a
     *  refused destroy leaves a dead row the list would otherwise keep showing. */
    suspend fun refreshMailbox(credentials: AccountCredentials, mailboxId: String)

    /** Upload attachment bytes as a JMAP blob the saved draft can reference. */
    suspend fun uploadAttachment(
        credentials: AccountCredentials,
        bytes: ByteArray,
        type: String?,
        name: String?,
        disposition: String,
        cid: String?,
    ): EmailBodyPart

    /** Expunge [ids] from one IMAP folder, refusing unless the server confirms [expectedUidValidity]
     *  (#99). The guard is the repository's, never copied here. */
    suspend fun imapDestroyGroup(
        credentials: AccountCredentials, source: String, ids: List<String>,
        succeeded: MutableSet<String>, failed: MutableSet<String>,
        expectedUidValidity: Long?,
    )

    /** Destroy [emailIds], sparing on JMAP whatever the server says is not in [expectedMailboxId]
     *  alone (#122). */
    suspend fun destroyAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedMailboxId: String?,
        expectedUidValidity: Long? = null,
    ): MailRepository.BulkResult

    /** The IMAP numbering last recorded for [mailboxId]; null on JMAP and on an unseen folder. */
    suspend fun recordedUidValidity(credentials: AccountCredentials, mailboxId: String): Long?
}

/**
 * Every draft this app holds: the ones written on the phone and not yet on the server (#95), the
 * save that uploads them on either protocol, and the destruction of the server original a re-save
 * or a discard replaces (#63/#69). Reached as [MailRepository.drafts].
 */
class MailDrafts(
    private val client: JmapClient,
    private val imap: ImapMailService,
    private val mailboxDao: MailboxDao,
    private val emailDao: EmailDao,
    private val accountStore: AccountStorage,
    /** Drafts written on the phone and not yet on the server (#95). */
    private val localDraftDao: LocalDraftDao,
    /** The folder numbering each mailbox was last seen under, frozen when a draft is saved over another (#99). */
    private val mailboxUidValidityDao: MailboxUidValidityDao,
    /**
     * Where a local draft's attachment bytes are staged. Its own tree, not the outbox's: an outbox item's
     * files die with the send, a draft's wait as long as the draft does.
     */
    private val localDraftFilesDir: java.io.File,
    private val host: MailDraftHost,
) {
    /**
     * The seam for a draft the server could not be given (#95): [saveDraft] books the deferred upload
     * itself. Set by the app layer at startup.
     */
    var localDraftScheduler: LocalDraftScheduler? = null

    /**
     * Save a draft, on the phone first, then on the server, always (#95). A failed upload keeps
     */
    suspend fun saveDraft(
        credentials: AccountCredentials,
        to: List<String>,
        subject: String,
        body: String,
        /** The `text/html` alternative for [body] (#131), null when plain. Never derived here. */
        html: String? = null,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        replacesEmailId: String? = null,
        /**
         * The IMAP numbering [replacesEmailId] belongs to, frozen by the caller (#99). No
         * default: read at save time it would be the number recorded after a renumbering.
         */
        replacesUidValidity: Long?,
        attachments: List<EmailBodyPart> = emptyList(),
        bodyIsLossy: Boolean = false,
        /**
         * Whether this composer lost the body it hands over, not [bodyIsLossy], the licence to
         * destroy a server original (#63); this weighs the write. No default, either way losing.
         */
        composerBodyWasLost: Boolean,
        /**
         * Whether the composer had nothing typed, its own `body.text`, not `[body].isBlank()`:
         * [body] is the PROJECTION, and a bullet on an empty composer projects to `"- "`.
         */
        typedBodyIsBlank: Boolean,
        // The composer's chosen From, so a delegated sub-account's draft is honest (issue #31).
        fromName: String? = null,
        fromEmail: String? = null,
        /** Whether the draft asks for a read receipt (RFC 8098); not put back on reopen (D1). */
        requestReceipt: Boolean = false,
    ): DraftSaveOutcome {
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
        // Minted or reloaded before anything is attempted, and by both keys, never keyed on
        // the `Message-ID`, which names one version of the text.
        val target = localDraftTarget(
            replacesEmailId,
            byId = { localDraftDao.byId(credentials.id, it) },
            forServerDraft = { localDraftDao.forServerDraft(credentials.id, it) },
        )
        val existing = target.existing
        // A local id is not a server id: it names nothing the server could be asked to destroy.
        val serverReplaced = serverDraftReplacedBy(replacesEmailId)
        val row = localDraftRow(
            accountId = credentials.id,
            id = target.id,
            messageId = localDraftMessageId(existing) { newMessageId(credentials.username) },
            to = recipients,
            cc = cc,
            bcc = bcc,
            subject = subject,
            body = body,
            html = html,
            fromName = fromName,
            fromEmail = fromEmail,
            inReplyTo = inReplyTo,
            references = references,
            replacesEmailId = serverReplaced,
            // Read now, while it is still true: this row may wait weeks (#99).
            replacesUidValidity = localDraftUidValidity(serverReplaced) { mailboxId ->
                mailboxUidValidityDao.recorded(credentials.id, mailboxId)
            },
            bodyIsLossy = bodyIsLossy,
            composerBodyWasLost = composerBodyWasLost,
            // Handed down, never re-derived: `body` is a projection (see the parameter's KDoc).
            typedBodyIsBlank = typedBodyIsBlank,
            requestReceipt = requestReceipt,
            existing = existing,
            nowMillis = System.currentTimeMillis(),
        )
        return saveDraftLocalFirst(
            row = row,
            // The row as it was before this save: `discard` takes the whole DRAFT away.
            existing = existing,
            nowMillis = System.currentTimeMillis(),
            upsert = { localDraftDao.upsert(it) },
            stage = { stageLocalDraftAttachments(localDraftDir(it.id), attachments) },
            discard = { discardLocalDraft(credentials.id, it.id) },
            upload = { saved -> uploadDraft(credentials, saved, attachments) },
            record = { saved, attempt ->
                localDraftDao.recordAttempt(
                    credentials.id, saved.id, LocalDraftState.PENDING, attempt.attemptCount, attempt.error,
                    attempt.atMillis, attempt.notBeforeMillis,
                )
            },
            // The draft's own work item. Not suspending: the composer's coroutine may be dead.
            schedule = { job -> localDraftScheduler?.schedule(job.accountId, job.id, job.delayMillis) },
        )
    }

    suspend fun accountsWithLocalDrafts(): List<String> = localDraftDao.accountsWithDrafts()

    /** One account's local drafts, newest first (#95). No state filter: `STAGING` shows too. */
    fun observeLocalDrafts(accountId: String): Flow<List<LocalDraftEntity>> =
        localDraftDao.observeForAccount(accountId)

    /** The row a save of [serverDraftId] would land on, the one a reopen must read instead (#216). */
    suspend fun localDraftReplacing(accountId: String, serverDraftId: String): String? =
        localDraftDao.forServerDraft(accountId, serverDraftId)?.id

    /** Open a local draft in the composer (#95), held [LocalDraftState.Editing] against the worker. */
    suspend fun takeLocalDraftForEdit(accountId: String, id: String): LocalDraftEdit =
        takeLocalDraftEdit(
            id,
            load = { localDraftDao.byId(accountId, it) },
            setState = { localDraftDao.setState(accountId, id, it) },
        )

    /** Give an edited row back, re-armed; the only way out of [LocalDraftState.Editing]. */
    suspend fun releaseLocalDraftEdit(accountId: String, id: String) =
        giveLocalDraftEditBack(
            id,
            nowMillis = System.currentTimeMillis(),
            load = { localDraftDao.byId(accountId, it) },
            setState = { localDraftDao.setState(accountId, id, it) },
            schedule = { delay -> localDraftScheduler?.schedule(accountId, id, delay) },
        )

    /**
     * Take one of this phone's own drafts away, the row and the files staged with it (#95), and
     * only after the other row exists: from a `finally`, it deletes the only copy of the text.
     */
    suspend fun consumeLocalDraft(accountId: String, id: String) = discardLocalDraft(accountId, id)

    /**
     * Bring one account's rows back from a process death mid-upload or mid-staging (#95), never
     */
    suspend fun revertUnfinishedLocalDrafts(accountId: String) {
        localDraftDao.revertUploadingToPending(accountId)
        localDraftDao.revertStagedToPendingLossy(accountId)
    }

    /** Every row still owed to the server, backed-off ones included: a startup re-arm needs them. */
    suspend fun localDraftsAwaitingUpload(accountId: String): List<LocalDraftEntity> =
        localDraftDao.pending(accountId, Long.MAX_VALUE)

    /** One deferred attempt at [id] (#95), through [uploadDraft], the save's own upload path. */
    suspend fun uploadLocalDraft(credentials: AccountCredentials, id: String): LocalDraftUploadResult {
        val now = System.currentTimeMillis()
        return uploadLocalDraftOnce(
            row = localDraftDao.byId(credentials.id, id),
            nowMillis = now,
            markUploading = { localDraftDao.setState(credentials.id, id, LocalDraftState.UPLOADING) },
            // Two bounds, neither replacing the other: per read ([LOCAL_DRAFT_UPLOAD_BUDGET_MS]),
            // `appendDraft` naming none; and per attempt ([LOCAL_DRAFT_ATTEMPT_BUDGET_MS]), the
            // wait for the IMAP mutex having none. The expiry must arrive as an ordinary failure.
            upload = { row ->
                withLocalDraftAttemptBudget(LOCAL_DRAFT_ATTEMPT_BUDGET_MS) {
                    withContext(ImapReadBudget(LOCAL_DRAFT_UPLOAD_BUDGET_MS)) {
                        uploadDraft(credentials, row, localDraftUploadAttachments(row.attachmentsJson))
                    }
                }
            },
            discard = { discardLocalDraft(credentials.id, it.id) },
            record = { attempt ->
                localDraftDao.recordAttempt(
                    credentials.id, id, LocalDraftState.PENDING, attempt.attemptCount, attempt.error,
                    attempt.atMillis, attempt.notBeforeMillis,
                )
            },
        )
    }

    private fun localDraftDir(id: String): java.io.File =
        java.io.File(localDraftFilesDir, localDraftDirName(id))

    private suspend fun discardLocalDraft(accountId: String, id: String) {
        runCatching { localDraftDao.deleteById(accountId, id) }
        runCatching { localDraftDir(id).deleteRecursively() }
    }

    /** [row] is already durable: a throw here means "not uploaded", not "lost". */
    private suspend fun uploadDraft(
        credentials: AccountCredentials,
        row: LocalDraftEntity,
        attachments: List<EmailBodyPart>,
    ): DraftSaveOutcome {
        val ccTrimmed = localDraftAddresses(row.cc)
        val bccTrimmed = localDraftAddresses(row.bcc)
        val replacesEmailId = row.replacesEmailId
        // Read off the row, never the composer or the folder: both guards below outlive the screen
        // that answered them, and `replacesUidValidity` was frozen when the row was minted (#99).
        val requestReceipt = row.requestReceipt
        val replacesUidValidity = row.replacesUidValidity
        if (credentials.protocol == MailProtocol.IMAP) {
            val drafts = mailboxDao.idForRole(credentials.id, "drafts") ?: error("This account has no Drafts folder.")
            val parts = imapDraftAttachments(attachments)
            // The bytes go out at most once per attempt, and a retry asks first. `false` means
            // this attempt appended nothing, so the destroy guard below has no replacement to weigh.
            val wentOut = appendDraftUnlessAlreadyThere(
                row = row,
                alreadyThere = { messageId ->
                    imap.draftIsAlreadyThere(credentials = credentials, draftsMailbox = drafts, messageId = messageId)
                },
                append = {
                    imap.appendDraft(
                        credentials, drafts,
                        // Built from the row: the `Message-ID` sent is the one the phone kept.
                        localDraftOutgoing(row, credentials.username, parts, System.currentTimeMillis()),
                    )
                },
            )
            // What the copy about to be destroyed was addressed to, read from the server: a cache
            // row's cc/bcc hold only what some sync put there, so an unknown forbids the destroy.
            // Cc and Bcc, this APPEND building with `BlindCopies.Written`; if that changes, back
            // to the Cc alone (#63).
            val addressingIsCarried = draftAddressingIsCarried(
                replacesEmailId = replacesEmailId,
                replacement = draftReplacementAddressing(ccTrimmed, bccTrimmed),
                // NO_BUDGET explicitly: blocking under the save, bounded by the ambient
                // ImapReadBudget under the deferred upload (#95). Expired means unknown.
                originalAddressing = { id -> imapDraftAddressing(credentials, id, ImapBudget.NO_BUDGET) },
            )
            // The same question for the read receipt, whose box comes back clear on reopen (D1).
            val receiptIsCarried = draftReceiptRequestIsCarried(
                replacesEmailId = replacesEmailId,
                replacement = requestReceipt,
                originalRequested = { id -> imapDraftReceiptRequested(credentials, id) },
            )
            val outcome = finishDraftSave(
                credentials, replacesEmailId,
                faithful = draftReplacementIsFaithful(
                    replacementWentOut = wentOut,
                    attachments.size, parts.size, row.bodyIsLossy,
                    addressingIsCarried = addressingIsCarried,
                    receiptRequestIsCarried = receiptIsCarried,
                ),
                frozenUidValidity = replacesUidValidity,
            )
            // IMAP APPEND returns no stable id to key a row on, so reload Drafts (#63).
            runCatching { host.refreshMailbox(credentials, drafts) }
            return outcome
        }
        val ctx = host.jmapSession(credentials)
        val recipients = localDraftAddresses(row.toAddresses).map { EmailAddress(email = it) }
        // The composer's chosen identity, then the stored ones, then the signed-in address, never
        // a live Identity/get, which is member-only and refused on a sub-account (issue #31).
        val from = if (!row.fromEmail.isNullOrBlank()) {
            EmailAddress(name = row.fromName, email = row.fromEmail)
        } else {
            val stored = accountStore.identities(credentials.id).firstOrNull()
            EmailAddress(name = stored?.name, email = stored?.email ?: credentials.username)
        }
        val draftsId = ctx.rolesToMailboxId["drafts"]
            ?: error("This account has no Drafts folder.")
        val blobs = jmapDraftAttachments(credentials, attachments)
        val inReplyTo = row.inReplyTo?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        val references = row.references?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        val savedId = client.saveDraft(
            session = ctx.session,
            accountId = ctx.accountId,
            auth = ctx.auth,
            from = from,
            to = recipients,
            cc = ccTrimmed.map { EmailAddress(email = it) },
            bcc = bccTrimmed.map { EmailAddress(email = it) },
            subject = row.subject,
            textBody = row.textBody,
            // Read off the row: the styling the save stored (#131), null for a plain draft.
            htmlBody = row.htmlBody,
            draftMailboxId = draftsId,
            inReplyTo = inReplyTo,
            references = references,
            attachments = blobs,
            requestReceipt = row.requestReceipt,
        )
        // Optimistically cache the just-saved draft, keyed on the server-returned id (#63).
        // Best-effort: a throw would be caught as KEPT_ON_DEVICE and uploaded a second time.
        if (savedId != null) {
            runCatching {
                cacheSavedDraft(
                    credentials, savedId, draftsId, from, recipients,
                    cc = ccTrimmed.map { EmailAddress(email = it) },
                    bcc = bccTrimmed.map { EmailAddress(email = it) },
                    row.subject, row.textBody,
                    inReplyTo, references, hasAttachment = blobs.isNotEmpty(),
                )
            }
        }
        // A read on JMAP too: Email/get returns recipients but not the receipt checkbox (D1).
        val receiptIsCarried = draftReceiptRequestIsCarried(
            replacesEmailId = replacesEmailId,
            replacement = requestReceipt,
            originalRequested = { id -> jmapDraftReceiptRequested(ctx, id) },
        )
        // Evicts the replaced original after the new row is in, so the list never flickers empty.
        return finishDraftSave(
            credentials, replacesEmailId,
            faithful = draftReplacementIsFaithful(
                // The server's answer, never the request: `saveDraft` answers NULL when the
                // response names neither a created id nor a refusal, and an unknown never destroys.
                replacementWentOut = savedId != null,
                attachments.size, blobs.size, row.bodyIsLossy,
                // True, and not a read: Email/get returns to/cc/bcc, so the composer's fields are
                // the original's addressing. A failed reopen carries `editingDraftLossy` instead.
                addressingIsCarried = true,
                receiptRequestIsCarried = receiptIsCarried,
            ),
            // Null on JMAP, where nothing is numbered, carried so neither branch reads one itself.
            frozenUidValidity = replacesUidValidity,
        )
    }

    /** An IMAP draft's cc+bcc as the server holds it; `null` is the unknown that keeps it. */
    suspend fun imapDraftAddressing(credentials: AccountCredentials, emailId: String, budgetMs: Int): Set<String>? {
        val cached = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull() ?: return null
        val uid = ImapMailService.uidOf(emailId) ?: return null
        return imap.ccAndBccOf(credentials, cached.mailboxId, uid, budgetMs)
    }

    /** Whether the IMAP draft asks for a receipt, as the server holds it; `null` keeps the original. */
    private suspend fun imapDraftReceiptRequested(credentials: AccountCredentials, emailId: String): Boolean? {
        val cached = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull() ?: return null
        val uid = ImapMailService.uidOf(emailId) ?: return null
        val raw = imap.fetchSource(credentials, cached.mailboxId, uid)
        return receiptRequestedInSource(raw)
    }

    /** The same for a JMAP draft; a server refusing the header property answers unknown, not "no". */
    private suspend fun jmapDraftReceiptRequested(ctx: MailRepository.JmapAccountSession, emailId: String): Boolean? {
        val email = client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)
        return receiptRequestedInProperty(
            header = email.dispositionNotificationTo,
            serverRefusesProperty = client.refusesReceiptHeader(ctx.session),
        )
    }

    private suspend fun cacheSavedDraft(
        credentials: AccountCredentials,
        emailId: String,
        draftMailboxId: String,
        from: EmailAddress,
        to: List<EmailAddress>,
        cc: List<EmailAddress>,
        bcc: List<EmailAddress>,
        subject: String,
        body: String,
        inReplyTo: List<String>,
        references: List<String>,
        hasAttachment: Boolean,
    ) {
        emailDao.upsertAll(
            listOf(
                savedDraftRow(
                    accountId = credentials.id,
                    emailId = emailId,
                    draftMailboxId = draftMailboxId,
                    from = from,
                    to = to,
                    cc = cc,
                    bcc = bcc,
                    subject = subject,
                    body = body,
                    inReplyTo = inReplyTo,
                    references = references,
                    hasAttachment = hasAttachment,
                ),
            ),
        )
    }

    /** The edited original is destroyed only when [faithful] (#63). */
    private suspend fun finishDraftSave(
        credentials: AccountCredentials,
        replacesEmailId: String?,
        faithful: Boolean,
        frozenUidValidity: Long?,
    ): DraftSaveOutcome {
        if (replacesEmailId == null) return DraftSaveOutcome.SAVED
        if (!faithful) return DraftSaveOutcome.ORIGINAL_KEPT
        runCatching { destroyDraft(credentials, replacesEmailId, frozenUidValidity) }
        return DraftSaveOutcome.SAVED
    }

    /** Staged attachment files as MIME parts; an unreadable part is dropped, keeping the original. */
    private fun imapDraftAttachments(attachments: List<EmailBodyPart>): List<OutgoingAttachment> =
        attachments.mapNotNull { part ->
            val path = part.partId ?: return@mapNotNull null
            val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
            val inline = part.disposition.equals("inline", ignoreCase = true) && !part.cid.isNullOrBlank()
            OutgoingAttachment(
                part.name ?: "attachment", part.type ?: "application/octet-stream", bytes,
                cid = part.cid, inline = inline,
            )
        }

    /** Blob-backed parts for a JMAP draft; a part that is neither is dropped, keeping the original. */
    private suspend fun jmapDraftAttachments(
        credentials: AccountCredentials,
        attachments: List<EmailBodyPart>,
    ): List<EmailBodyPart> = attachments.mapNotNull { part ->
        if (part.blobId != null) return@mapNotNull part
        val path = part.partId ?: return@mapNotNull null
        val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
        runCatching {
            host.uploadAttachment(
                credentials, bytes, part.type, part.name,
                part.disposition ?: "attachment", part.cid,
            )
        }.getOrNull()
    }

    /**
     * Was the draft [emailId] provably addressed to nobody but the people the composer is showing?
     */
    suspend fun emptiedDraftAddressingIsProvenEmpty(
        credentials: AccountCredentials,
        emailId: String,
    ): Boolean {
        if (credentials.protocol == MailProtocol.IMAP) {
            // The server's own envelope: an empty replacement can only include an empty original.
            return draftAddressingIsCarried(
                replacesEmailId = emailId,
                replacement = emptySet(),
                originalAddressing = { id -> imapDraftAddressing(credentials, id, EMPTIED_DRAFT_PROOF_BUDGET_MS) },
            )
        }
        // JMAP proves it without a read: Email/get hands back the server's to/cc/bcc, so the
        // emptied fields are the original's addressing. A failed reopen arrives `editingDraftLossy`.
        return true
    }

    /**
     * Discard a draft the user emptied while editing it (#69): the same permanent server+cache
     */
    suspend fun discardDraft(credentials: AccountCredentials, emailId: String, frozenUidValidity: Long?) {
        destroyDraft(credentials, emailId, frozenUidValidity)
    }

    /**
     * [frozenUidValidity] is the numbering the caller froze when it read [emailId], nothing here
     * reads one; even a local `val` shadowing it hands the guard a POST-renumbering number.
     */
    suspend fun destroyDraft(
        credentials: AccountCredentials,
        emailId: String,
        frozenUidValidity: Long?,
    ) {
        if (credentials.protocol == MailProtocol.IMAP) {
            // Account-scoped (issue #31): the draft's folder must be resolved within this account.
            val folder = emailDao.mailboxOf(credentials.id, emailId)
                ?: mailboxDao.idForRole(credentials.id, "drafts")
                ?: return
            // An EXPUNGE names messages by UID, and a UID means a message only inside one
            // numbering (#99). Nothing to oppose licenses nothing: the draft stays.
            val destroyable = UidValidity.destroyableUnderNumbering(listOf(emailId), frozenUidValidity)
            try {
                host.imapDestroyGroup(credentials, folder, destroyable, mutableSetOf(), mutableSetOf(), frozenUidValidity)
            } catch (unconfirmed: ImapNumberingUnconfirmed) {
                // Refused, not failed: nothing left the client. Only this type.
            } catch (renumbered: ImapUidValidityChanged) {
                // The refusal a frozen numbering makes reachable, silent as above. `onMailbox` has
                // invalidated the folder, so the draft's dead LIST row is re-read best-effort.
                runCatching { host.refreshMailbox(credentials, folder) }
            }
            return
        }
        // The JMAP half of the same trade (#122): a JMAP id still names the draft after another
        // client has moved it out of Drafts. Through `destroyAll`, which asks the server where the
        // id is and spares what is not in that folder alone. The folder is read here, not frozen.
        // Refused is not failed, and it is silent; a transport failure still throws.
        host.destroyAll(credentials, listOf(emailId), draftMailboxOf(credentials, emailId))
    }

    /** The numbering to freeze for the draft [emailId], opposed later by [discardDraft] and the replace
     *  half of [saveDraft]. Call it when the ID is read, never when saving: at the save it reads the
     *  number recorded after the renumbering and the expunge hits a UID that names another draft (#99). */
    suspend fun recordedUidValidityForDraft(credentials: AccountCredentials, emailId: String): Long? {
        if (credentials.protocol != MailProtocol.IMAP) return null
        val folder = emailDao.mailboxOf(credentials.id, emailId)
            ?: mailboxDao.idForRole(credentials.id, "drafts")
            ?: return null
        return host.recordedUidValidity(credentials, folder)
    }

    /** The folder the server draft [emailId] sits in, what a held-back destroy has to carry, or on
     *  JMAP `destroyAll` spares every wave in silence (#122). The pick is [draftDestroyFolder]. */
    suspend fun draftMailboxOf(credentials: AccountCredentials, emailId: String): String? =
        draftDestroyFolder(
            cachedMailboxId = emailDao.mailboxOf(credentials.id, emailId),
            draftsRoleMailboxId = mailboxDao.idForRole(credentials.id, "drafts"),
        )

    suspend fun carriesDraftFlag(credentials: AccountCredentials, draft: Email, uidValidity: Long?): Boolean {
        return draftFlagIsCarried(credentials.protocol, draft.isDraft) { imapDraftFlag(credentials, draft, uidValidity) }
    }

    private suspend fun imapDraftFlag(credentials: AccountCredentials, draft: Email, uidValidity: Long?): Boolean? {
        val uid = ImapMailService.uidOf(draft.id) ?: return null
        val mailboxId = draft.mailboxId ?: return null
        return imap.fetchIsDraft(credentials, mailboxId, uid, uidValidity)
    }
}
