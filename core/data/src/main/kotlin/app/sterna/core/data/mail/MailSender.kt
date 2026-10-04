package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStorage
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.autocryptHeaderValue
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.OutboxAttachment
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.OutboxEdit
import app.sterna.core.data.db.OutboxEntity
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxLogic.outboxPgpModeName
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.ReceivedContactRow
import app.sterna.core.data.db.SentContactRow
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.settings.OutgoingDateZones
import app.sterna.core.data.unsubscribe.UnsubscribeClient
import app.sterna.core.data.unsubscribe.UnsubscribeResult
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.SubmissionEnvelope
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Wall-clock budget for the IMAP envelope read the send path makes before it may destroy the draft
 *  a message was edited from. It bounds the connect and the reads, not the wait for the account's
 *  IMAP mutex, which nothing bounds. Expiry throws, read as "could not be established". */
private const val SEND_PROOF_BUDGET_MS = 10_000

/**
 * What [MailSender] needs from [MailRepository] and nothing more. It is an interface so the
 * delivery path can be entered in a JVM test with a stand-in: `performDelivery` is 179 lines that
 * no test could execute while reaching them meant building the repository, its eleven DAOs and a
 * warm JMAP session.
 */
interface MailSendHost {

    /** The account's JMAP session, from the repository's cache when it is warm. */
    suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession

    /** Pull one folder into the cache; the send uses it on the Sent copy it has just filed. */
    suspend fun syncMailbox(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        mailboxId: String,
        sizing: FolderSyncSizing,
        localAccountId: String,
    ): MailRepository.MailboxSync

    /** Upload attachment bytes as a JMAP blob the submission can reference. */
    suspend fun uploadAttachment(
        credentials: AccountCredentials,
        bytes: ByteArray,
        type: String?,
        name: String?,
        disposition: String = "attachment",
        cid: String? = null,
    ): EmailBodyPart

    /** Destroy the server draft a delivered message replaces (#63), under [frozenUidValidity] (#99). */
    suspend fun destroyDraft(credentials: AccountCredentials, emailId: String, frozenUidValidity: Long?)

    /** The addressing an IMAP draft carries, or null for "could not be established". */
    suspend fun imapDraftAddressing(credentials: AccountCredentials, emailId: String, budgetMs: Int): Set<String>?
}

/**
 * Everything that leaves the phone as mail: the outbox queue and its edit parking, the delivery of
 * one item on either protocol, scheduled sends, the unsubscribe and read-receipt messages, and the
 * recipients learnt from them. Reached as [MailRepository.sending].
 */
class MailSender(
    private val client: JmapClient,
    private val imap: ImapMailService,
    private val mailboxDao: MailboxDao,
    private val emailDao: EmailDao,
    private val accountStore: AccountStorage,
    private val outboxDao: OutboxDao,
    private val scheduledSendDao: ScheduledSendDao,
    private val recentContactDao: RecentContactDao,
    private val outboxFilesDir: java.io.File,
    private val host: MailSendHost,
    private val outgoingDates: OutgoingDateZones,
    private val logWarn: (String, String, Throwable?) -> Unit = { _, _, _ -> },
    /** Where a scheduled send keeps its attachments until its hour, one directory per row. */
    private val scheduledFilesDir: java.io.File,
) {
    /**
     * Schedules the WorkManager job that delivers an outbox item. Set by the app layer at startup: the data
     * module cannot reference the worker.
     */
    var outboxScheduler: OutboxScheduler? = null

    // ---- recipient suggestions ----

    /** Count one send to each of [addresses], for the recipient menu's ranking. */
    suspend fun rememberRecipients(addresses: List<String>) {
        val now = System.currentTimeMillis()
        sendsToRecord(addresses).forEach { (email, name) -> recentContactDao.recordSend(email, name, now) }
    }

    suspend fun sentContacts(): List<SentContactRow> = recentContactDao.sentContacts()

    suspend fun receivedContacts(): List<ReceivedContactRow> = emailDao.receivedFrom()

    /** SMTP OutgoingMessage from compose fields (IMAP). [requestReceipt] has no default. */
    private fun outgoing(
        credentials: AccountCredentials,
        recipients: List<String>,
        subject: String,
        body: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        html: String? = null,
        fromName: String? = null,
        fromEmail: String? = null,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        requestReceipt: Boolean,
    ): OutgoingMessage {
        val from = formatFromAddress(fromName, fromEmail) ?: credentials.username
        // The read receipt is addressed to this `from` string, so the check below covers it.
        requireSingleLineAddresses(listOf(from) + recipients + cc + bcc)
        return OutgoingMessage(
            from = from,
            to = recipients,
            cc = cc,
            bcc = bcc,
            subject = subject,
            body = body,
            html = html,
            inReplyTo = inReplyTo.firstOrNull(),
            references = references.joinToString(" ").ifBlank { null },
            messageId = newMessageId(credentials.username),
            dateMillis = System.currentTimeMillis(),
            requestReceipt = requestReceipt,
        )
    }

    /** Whether the send provably carries the addressing of the draft it destroys; unknown forbids. */
    private suspend fun sendCarriesDraftAddressing(
        credentials: AccountCredentials,
        replacesEmailId: String,
        cc: List<String>,
        bcc: List<String>,
    ): Boolean {
        if (credentials.protocol != MailProtocol.IMAP) return true
        return draftAddressingIsCarried(
            replacesEmailId = replacesEmailId,
            replacement = draftReplacementAddressing(cc, bcc),
            // Bounded, unlike the save's twin read: this runs before the outbox row is inserted.
            originalAddressing = { id -> host.imapDraftAddressing(credentials, id, SEND_PROOF_BUDGET_MS) },
        )
    }

    // ---- outbox (persistent send queue) ----

    /** The single send path: queue in the outbox and arm the worker; [holdMs] > 0 HELDs it. */
    suspend fun enqueueSend(
        credentials: AccountCredentials,
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        attachments: List<EmailBodyPart> = emptyList(),
        htmlBody: String? = null,
        fromName: String? = null,
        fromEmail: String? = null,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        holdMs: Long = 0,
        pgpMode: PgpMode? = null,
        /** A complete MIME entity replacing the body byte for byte. Not conditional on [pgpMode]. */
        prebuiltEntity: String? = null,
        draftEmailId: String? = null,
        /**
         * The IMAP numbering [draftEmailId] was read under, frozen by whoever read it (#99). No
         * default: the destroy happens days later, and `null` there means "destroy nothing".
         */
        draftUidValidity: Long?,
        /** Whether the composer could not reproduce the draft (#63). An unknown answers `true`. */
        bodyIsLossy: Boolean = true,
        /** Ask for a read receipt (RFC 8098), per message; persisted so the worker still asks. */
        requestReceipt: Boolean = false,
        /** Teach these addresses to the contact suggestions. False for a receipt, [recipientsToLearn]. */
        learnRecipients: Boolean = true,
    ): Long {
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
        require(recipients.isNotEmpty()) { "Add at least one recipient." }
        val ccTrimmed = cc.map { it.trim() }.filter { it.isNotEmpty() }
        val bccTrimmed = bcc.map { it.trim() }.filter { it.isNotEmpty() }
        recipientsToLearn(recipients + ccTrimmed + bccTrimmed, learnRecipients)
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { rememberRecipients(it) } }

        // performSend destroys whatever draft id this row carries, so the id is written only when
        // the outgoing message provably reproduces the draft it replaces.
        val replaceableDraftId =
            replaceableDraftIdOrNull(draftEmailId, attachments.size, bodyIsLossy) { id ->
                sendCarriesDraftAddressing(credentials, id, ccTrimmed, bccTrimmed)
            }

        // An encrypted payload replaces the plaintext at rest, and both halves of that are
        // [outboxPayload]'s: headers on the row, ciphertext in a file, attachments in the entity.
        val payload = outboxPayload(prebuiltEntity, pgpMode)
        val encrypted = payload.redactBody
        val now = System.currentTimeMillis()
        val held = holdMs > 0
        val id = outboxDao.insert(
            OutboxEntity(
                accountId = credentials.id,
                recipients = recipients.joinToString(","),
                cc = ccTrimmed.joinToString(",").ifBlank { null },
                bcc = bccTrimmed.joinToString(",").ifBlank { null },
                subject = subject,
                textBody = if (encrypted) "" else body,
                htmlBody = if (encrypted) null else htmlBody,
                fromName = fromName,
                fromEmail = fromEmail,
                inReplyTo = inReplyTo.joinToString(" ").ifBlank { null },
                references = references.joinToString(" ").ifBlank { null },
                attachmentsJson = "[]",
                createdAtMillis = now,
                notBeforeMillis = now + holdMs,
                state = if (held) OutboxState.HELD else OutboxState.QUEUED,
                pgpMode = outboxPgpModeName(pgpMode),
                draftEmailId = replaceableDraftId,
                draftUidValidity = draftUidValidity,
                requestReceipt = requestReceipt,
            ),
        )
        // Staging runs after the insert (it needs the id), and a throw must roll the row back: an
        // orphan with attachmentsJson "[]" is re-armed at startup and sent amputated (#70).
        stageOrRollback(rollback = { deleteOutbox(id) }) {
            if (payload.entityFile != null) {
                val dir = java.io.File(outboxFilesDir, id.toString()).apply { mkdirs() }
                val entityFile = java.io.File(dir, PREBUILT_ENTITY_FILE).apply { writeText(payload.entityFile) }
                outboxDao.byId(id)?.let { outboxDao.update(it.copy(pgpEntityPath = entityFile.absolutePath)) }
            } else {
                val durable = persistAttachments(id, attachments, credentials.protocol)
                outboxDao.byId(id)?.let { outboxDao.update(it.copy(attachmentsJson = OutboxAttachments.encode(durable))) }
            }
        }
        // The row is durable and its payload staged, so the send IS queued, and arming is not
        // queueing. A scheduler that throws must not be relayed, or the caller hears "nothing was
        // queued" about a message in the Outbox and a quick reply goes out twice. The failure goes
        // on the row as OutboxLogic.NOT_ARMED, lastError only, never its state.
        return armOrNote(id, holdMs, outboxScheduler) { note ->
            logWarn("MailRepository", "outbox $id: $note", null)
            outboxDao.byId(id)?.let { outboxDao.update(it.copy(lastError = OutboxLogic.NOT_ARMED)) }
        }
    }

    private fun persistAttachments(
        id: Long,
        attachments: List<EmailBodyPart>,
        protocol: MailProtocol,
    ): List<OutboxAttachment> {
        if (attachments.isEmpty()) return emptyList()
        val dir = java.io.File(outboxFilesDir, id.toString()).apply { mkdirs() }
        return attachments.mapNotNull { part ->
            // Refuse rather than drop an attachment the send cannot carry, unreadable or a server
            // blob an IMAP send has no bytes for: it would send amputated.
            when {
                part.blobId != null && protocol == MailProtocol.IMAP ->
                    error(attachmentPreparedForAnotherAccount(part.name))
                part.blobId != null -> OutboxAttachment(
                    kind = OutboxAttachments.KIND_JMAP_BLOB,
                    blobId = part.blobId, type = part.type, name = part.name, size = part.size,
                    cid = part.cid, disposition = part.disposition,
                )
                part.partId != null -> {
                    val sourcePath = part.partId!!
                    val bytes = runCatching { java.io.File(sourcePath).readBytes() }.getOrNull()
                        ?: error("Couldn't read the attachment ${part.name ?: sourcePath} to queue it.")
                    val safe = (part.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val dest = java.io.File(dir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
                    OutboxAttachment(
                        kind = OutboxAttachments.KIND_IMAP_FILE,
                        path = dest.absolutePath, type = part.type, name = part.name, size = bytes.size.toLong(),
                        cid = part.cid, disposition = part.disposition,
                    )
                }
                else -> null
            }
        }
    }

    /** Outbox rows for the list, through [OutboxLogic.isWaitingInOutbox] so the counts agree. */
    fun outboxFlow(): Flow<List<OutboxEntity>> =
        outboxDao.observeAll().map { rows -> rows.filter { OutboxLogic.isWaitingInOutbox(it.state) } }

    /** Failed items plus those waiting past their undo window, see [OutboxLogic.activeCount]. */
    fun outboxActiveCount(): Flow<Int> = OutboxLogic.badgeCount(outboxDao.observeBadgeItems())

    /** How many messages are in the Outbox right now, no grace period, unlike [outboxActiveCount]. */
    fun outboxQueuedCount(): Flow<Int> =
        outboxDao.observeBadgeItems().map { OutboxLogic.queuedCount(it) }

    suspend fun outboxItem(id: Long): OutboxEntity? = outboxDao.byId(id)

    suspend fun unfinishedOutbox(): List<OutboxEntity> = outboxDao.unfinished()

    suspend fun updateOutboxState(id: Long, state: OutboxState, attemptCount: Int, lastError: String?) {
        outboxDao.updateState(id, state, attemptCount, lastError, System.currentTimeMillis())
    }

    suspend fun deleteOutbox(id: Long) {
        outboxDao.delete(id)
        runCatching { java.io.File(outboxFilesDir, id.toString()).deleteRecursively() }
    }

    /** Re-queue for an immediate retry; `notBeforeMillis` is also the anchor the badge counts from. */
    suspend fun retryOutbox(id: Long) =
        requeueForRetry(id, System.currentTimeMillis(), { row, now -> outboxDao.requeueForRetry(row, now) }, outboxScheduler)

    /** Fields to reopen a queued/failed item in compose (#70); the row is not deleted ([outboxId]). */
    data class OutboxDraft(
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
        /** The queued row's `text/html` part (#131). No default; it is the send's html. */
        val htmlBody: String?,
        val fromAccountId: String?,
        val fromEmail: String?,
        val attachments: List<EmailBodyPart>,
        val inReplyTo: List<String>,
        val references: List<String>,
        /** The PGP mode the item was queued with (#35/#70): a signed/encrypted item reopens as such. */
        val pgpMode: String?,
        /** The saved draft the item was edited from (#63), kept so re-sending still replaces it. */
        val draftEmailId: String? = null,
        /** The numbering [draftEmailId] was frozen under (#99). No default: the row is its home. */
        val draftUidValidity: Long?,
        val outboxId: Long,
        /** Whether the queued item asks for a read receipt. No default: the row is its only home. */
        val requestReceipt: Boolean,
    )

    /** Mark the row editing (#70); it stays, so a process death costs the edit and not the mail. */
    suspend fun takeOutboxForEdit(id: Long, stagingDir: java.io.File): OutboxDraft? {
        val item = outboxDao.byId(id) ?: return null
        // State guard at the choke point (#70): editing on a sending row races updateOutboxState.
        val unreplayable = OutboxLogic.isUnreplayablePrebuiltEntity(item.pgpMode, item.pgpEntityPath)
        if (!OutboxLogic.canEdit(item.pgpMode, item.state, unreplayable)) return null
        // Stage attachments before changing state: `OutboxEdit.take` throws on an unreadable one,
        // and a throw must leave the row still queued and deliverable.
        val attachments = OutboxEdit.take(OutboxAttachments.decode(item.attachmentsJson), stagingDir)
        val draft = outboxDraftOf(item, attachments)
        outboxDao.setState(id, OutboxState.EDITING)
        return draft
    }

    /** Give a message taken out with [takeOutboxForEdit] back to the queue unchanged (#70). */
    suspend fun releaseOutboxEdit(id: Long) {
        val row = outboxDao.byId(id) ?: return
        if (row.state != OutboxState.EDITING) return
        // A failed item reopened then closed untouched must not restart on its own (#70).
        val next = OutboxLogic.stateAfterEdit(row.attemptCount)
        outboxDao.setState(id, next)
        if (next != OutboxState.QUEUED) return
        // Held or scheduled items keep whatever is left of their wait; everything else goes now.
        outboxScheduler?.schedule(id, (row.notBeforeMillis - System.currentTimeMillis()).coerceAtLeast(0))
    }

    /**
     * Park a row taken out with [takeOutboxForEdit] in [state] instead of giving it back to the queue.
     */
    suspend fun parkOutboxEdit(id: Long, state: OutboxState) {
        val row = outboxDao.byId(id) ?: return
        if (row.state != OutboxState.EDITING) return
        outboxDao.setState(id, state)
    }

    /**
     * Park a row whose edit was INTERRUPTED, or fail it once its attempts are exhausted.
     */
    suspend fun parkInterruptedOutboxEdit(id: Long) {
        val row = outboxDao.byId(id) ?: return
        if (row.state != OutboxState.EDITING) return
        if (row.attemptCount >= OutboxLogic.MAX_ATTEMPTS) outboxDao.setState(id, OutboxState.FAILED)
        else outboxDao.parkEditingAsInterrupted(id, OutboxLogic.MAX_ATTEMPTS, OutboxLogic.EDIT_INTERRUPTED)
    }

    /**
     * Startup recovery (#70): park every row stranded in editing by a process death, failed past its attempts.
     */
    suspend fun parkInterruptedOutboxEdits() {
        outboxDao.revertEditingExhaustedToFailed(OutboxLogic.MAX_ATTEMPTS)
        outboxDao.parkAllEditingAsInterrupted(OutboxLogic.MAX_ATTEMPTS, OutboxLogic.EDIT_INTERRUPTED)
    }

    /** Actually deliver one outbox item (no queue indirection); exceptions propagate to the worker. */
    suspend fun performSend(credentials: AccountCredentials, item: OutboxEntity) {
        performDelivery(credentials, item)
        // Delivered: the draft this item replaces (#63) leaves Drafts, best-effort and after
        // success. The numbering is carried by the row, never looked up here (#99); on JMAP it is
        // null, that branch opposing the folder instead (#122).
        item.draftEmailId?.let {
            runCatching { host.destroyDraft(credentials, it, item.draftUidValidity) }
        }
    }

    private suspend fun performDelivery(credentials: AccountCredentials, item: OutboxEntity) {
        val to = item.recipients.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val subject = item.subject
        val body = item.textBody
        val htmlBody = item.htmlBody
        val fromName = item.fromName
        val fromEmail = item.fromEmail
        val inReplyTo = item.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        val references = item.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        val ccTrimmed = item.cc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val bccTrimmed = item.bcc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val stored = OutboxAttachments.decode(item.attachmentsJson)

        // A pre-built entity (PGP/MIME, or an RFC 8098 receipt), read once above the fork: what
        // sends the message verbatim is the entity, never the PGP mode.
        val prebuiltEntity = prebuiltEntityAt(item.pgpEntityPath, item.pgpMode) { path ->
            runCatching { java.io.File(path).readText() }.getOrNull()
        }

        val sendingAccount = accountStore.account(credentials.id)

        if (credentials.protocol == MailProtocol.IMAP) {
            val recipients = to
            require(recipients.isNotEmpty()) { "Add at least one recipient." }
            val outAttachments = stored.map { a ->
                val path = a.path
                    ?: error(attachmentPreparedForAnotherAccount(a.name))
                // Refuse rather than deliver amputated: the item stays in the outbox with its error.
                val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
                    ?: error("Couldn't read the attachment ${a.name ?: path} to send it.")
                val inline = a.disposition.equals("inline", ignoreCase = true) && !a.cid.isNullOrBlank()
                OutgoingAttachment(
                    a.name ?: "attachment", a.type ?: "application/octet-stream", bytes,
                    cid = a.cid, inline = inline,
                )
            }
            val message = outgoing(
                credentials, recipients, subject, body, inReplyTo, references, htmlBody,
                fromName, fromEmail, ccTrimmed, bccTrimmed,
                requestReceipt = item.requestReceipt,
            ).copy(
                attachments = if (prebuiltEntity != null) emptyList() else outAttachments,
                prebuiltEntity = prebuiltEntity,
                autocryptHeader = autocryptHeaderValue(
                    sendingAccount,
                    fromEmail?.takeIf { it.isNotBlank() } ?: credentials.username,
                ),
            )
            // The Sent copy is a second write, and some servers file it themselves;
            // sentMailboxToUpload decides which folder (if any) reaches the APPEND. It lists,
            // creates and may refuse, all of it above the submission: a failure once the SMTP
            // server has accepted the message is replayed by the outbox, and the recipient
            // receives it again.
            val sentMailbox = sentMailboxToUpload(
                uploadSentCopy = accountStore.uploadSentCopy(credentials.id),
                cached = mailboxDao.idForRole(credentials.id, "sent"),
                chosen = credentials.specialFolders,
                listed = { imap.listMailboxes(credentials) },
                create = { imap.createFolder(credentials, it) },
            )
            imap.send(credentials, message, sentMailbox)
            return
        }
        // A part's shape (local file or server blob) was fixed for the sender's protocol at attach
        // time, and the sender may have changed since. A part that no longer matches this path is
        // prepared again when the worker can do it alone, refused when it cannot, never dropped:
        // dropped, the delivery succeeds and `performSend` destroys the draft that still held it.
        // A pre-built entity carries its own parts, and `enqueueSend` stored none beside it.
        val attachments = if (prebuiltEntity != null) emptyList() else stored.map { a ->
            when {
                a.blobId != null -> EmailBodyPart(
                    blobId = a.blobId, type = a.type, size = a.size, name = a.name,
                    disposition = a.disposition ?: "attachment", cid = a.cid,
                )
                a.path != null -> {
                    val bytes = runCatching { java.io.File(a.path).readBytes() }.getOrNull()
                        ?: error("Couldn't read the attachment ${a.name ?: a.path} to send it.")
                    host.uploadAttachment(
                        credentials, bytes, a.type, a.name,
                        disposition = a.disposition ?: "attachment", cid = a.cid,
                    )
                }
                else -> error("The attachment ${a.name ?: "attachment"} has no file to send; attach it again.")
            }
        }
        val ctx = host.jmapSession(credentials)
        val recipients = to.map { EmailAddress(email = it) }
        require(recipients.isNotEmpty()) { "Add at least one recipient." }
        val ccAddrs = ccTrimmed.map { EmailAddress(email = it) }
        val bccAddrs = bccTrimmed.map { EmailAddress(email = it) }

        // Delegated sub-account (issue #31): member-only methods are refused on the shared account,
        // but a submission created on the LOGIN's account with the delegated address in From is
        // accepted; the Sent copy is re-filed into the sub-account's own Sent below.
        val submissionAccountId = ctx.session.mailAccountId() ?: ctx.accountId
        val onBehalf = submissionAccountId != ctx.accountId

        val serverIdentities = client.getIdentities(ctx.session, submissionAccountId, ctx.auth)
        // Which identity to submit under, and whether the envelope must be named (issue #172), is
        // [SubmissionIdentity]'s rule; null means no server identity at all.
        val choice = SubmissionIdentity.choose(fromEmail, serverIdentities, onBehalf)
            ?: error("This account has no sending identity.")
        val identity = choice.identity
        // Trimmed like the address `choose` names: untrimmed, From: " a@b " faces an `a@b` mail from.
        val from = if (!fromEmail.isNullOrBlank()) EmailAddress(name = fromName, email = fromEmail.trim())
        else EmailAddress(name = identity.name, email = identity.email)
        // The bytes a pre-built send imports carry no `Bcc:`, and a server left to derive `rcptTo`
        // reads it from the headers: a blind copy is then never delivered, and nothing says so.
        // Its envelope takes the submitting identity's address, which on a delegated send is the
        // login's and not the `From:`, the address the submission is created under.
        val mailFrom = choice.envelopeMailFrom
            ?: identity.email.takeIf { prebuiltEntity != null && bccAddrs.isNotEmpty() }
        // rcptTo carries to + cc + bcc: RFC 8621 §7.5 makes both envelope fields mandatory.
        val envelope = mailFrom?.let {
            SubmissionEnvelope(
                mailFrom = it,
                rcptTo = (recipients + ccAddrs + bccAddrs).map { a -> a.email },
            )
        }
        // Mailbox ids are per-account: an on-behalf send files in the login's account, so its
        // roles must come from there. No pinned folders: they are stored on the account record the
        // choice was made on, and these are the login's folders, another record entirely. The
        // election still applies, or a server listing two Sent folders drops the copy in the one
        // the drawer does not call Sent.
        val submissionRoles = if (onBehalf) {
            jmapRolesToMailboxId(client.getMailboxes(ctx.session, submissionAccountId, ctx.auth), emptyMap())
        } else {
            ctx.rolesToMailboxId
        }
        val draftsId = submissionRoles["drafts"]
            ?: submissionRoles["sent"]
            ?: error("This account has no Drafts or Sent folder.")
        val sentId = submissionRoles["sent"] ?: draftsId

        val dateZone = outgoingDates.atSend()
        val sentEmailId = if (prebuiltEntity != null) {
            // A pre-built entity must reach the wire byte-exact, the bytes the signature covers
            // do not survive re-encoding from Email/set fields.
            val raw = OutgoingMime.build(
                outgoing(
                    credentials, to, subject, "", inReplyTo, references, null,
                    from.name ?: fromName, from.email, ccTrimmed, bccTrimmed,
                    requestReceipt = item.requestReceipt,
                ).copy(
                    prebuiltEntity = prebuiltEntity,
                    autocryptHeader = autocryptHeaderValue(sendingAccount, from.email),
                ),
                zone = dateZone,
            )
            client.importAndSendEmail(
                session = ctx.session,
                accountId = submissionAccountId,
                auth = ctx.auth,
                identityId = identity.id,
                rawMessage = raw.toByteArray(Charsets.UTF_8),
                draftMailboxId = draftsId,
                sentMailboxId = sentId,
                envelope = envelope,
            )
        } else {
            client.sendEmail(
                session = ctx.session,
                accountId = submissionAccountId,
                auth = ctx.auth,
                identityId = identity.id,
                from = from,
                to = recipients,
                cc = ccAddrs,
                bcc = bccAddrs,
                subject = subject,
                textBody = body,
                htmlBody = htmlBody,
                draftMailboxId = draftsId,
                sentMailboxId = sentId,
                inReplyTo = inReplyTo,
                references = references,
                attachments = attachments,
                requestReceipt = item.requestReceipt,
                autocryptHeader = autocryptHeaderValue(sendingAccount, from.email),
                envelope = envelope,
                sentAt = OffsetDateTime.now(dateZone),
            )
        }

        // On-behalf: move the Sent copy into the sub-account's own Sent, best-effort after the
        // send stands, a failed copy leaves the message in the login's Sent.
        val ownSentId = ctx.rolesToMailboxId["sent"]
        if (onBehalf && sentEmailId != null && ownSentId != null) {
            runCatching {
                client.copyEmailToAccount(
                    session = ctx.session,
                    auth = ctx.auth,
                    fromAccountId = submissionAccountId,
                    toAccountId = ctx.accountId,
                    emailId = sentEmailId,
                    mailboxId = ownSentId,
                )
            }
        }
        // Pull the Sent copy into the cache at once (best-effort): the list counts a thread from
        // the cache, so an un-cached reply leaves the conversation looking single.
        runCatching {
            val sizing = folderSyncSizing(PAGE_SIZE, ctx.session.getBatchSize())
            if (onBehalf) {
                ownSentId?.let { host.syncMailbox(ctx.session, ctx.accountId, ctx.auth, it, sizing, credentials.id) }
            } else {
                host.syncMailbox(ctx.session, ctx.accountId, ctx.auth, sentId, sizing, credentials.id)
            }
        }
    }

    /** iTIP reply through the outbox; the decisive signal is the in-body method:reply line. */
    suspend fun sendCalendarReply(
        credentials: AccountCredentials,
        organizerEmail: String,
        subject: String,
        textBody: String,
        replyIcs: ByteArray,
    ) {
        require(organizerEmail.isNotBlank()) { "The invite has no organizer to reply to." }
        val attachment = if (credentials.protocol == MailProtocol.IMAP) {
            // No blob store for IMAP, stage the bytes as a temp file enqueueSend copies durably.
            val file = java.io.File.createTempFile("sterna-reply", ".ics").apply { writeBytes(replyIcs) }
            EmailBodyPart(
                partId = file.absolutePath,
                type = "text/calendar; method=REPLY; charset=utf-8",
                size = replyIcs.size.toLong(),
                name = "invite.ics",
                disposition = "attachment",
            )
        } else {
            host.uploadAttachment(credentials, replyIcs, "text/calendar", "invite.ics")
        }
        // The account's own identity: a sub-account's reply submits through its login (issue #31).
        val identity = accountStore.identities(credentials.id).firstOrNull()
        enqueueSend(
            credentials = credentials,
            to = listOf(organizerEmail),
            subject = subject,
            body = textBody,
            attachments = listOf(attachment),
            fromName = identity?.name,
            fromEmail = identity?.email,
            // An invitation reply replaces no draft: nothing to freeze, nothing to destroy.
            draftUidValidity = null,
        )
    }

    // ---- unsubscribe (RFC 2369 / RFC 8058) ----

    private val unsubscribeClient by lazy { UnsubscribeClient() }

    /**
     * RFC 8058 one-click unsubscribe: one POST, no `Authorization` header, [UnsubscribeClient]
     * exists so this cannot inherit the account's credentials.
     */
    suspend fun unsubscribeOneClick(url: String, isOnline: () -> Boolean): UnsubscribeResult =
        unsubscribeClient.oneClick(url, isOnline)

    /** Queue the `mailto:` unsubscribe through the outbox; [UNSUBSCRIBE_SUBJECT] is untranslated. */
    suspend fun sendUnsubscribeMail(
        credentials: AccountCredentials,
        mailto: MailtoUnsubscribe,
    ) {
        val identity = accountStore.identities(credentials.id).firstOrNull()
        val mail = unsubscribeMail(mailto, identity?.name, identity?.email)
        enqueueSend(
            credentials = credentials,
            to = mail.to,
            subject = mail.subject,
            body = mail.body,
            fromName = mail.fromName,
            fromEmail = mail.fromEmail,
            draftUidValidity = null,
        )
    }

    /**
     * Queue the RFC 8098 read receipt answering [receiptTo]. Nothing calls this yet: a receipt
     * tells a stranger when their mail was opened, never sent without the reader saying so.
     */
    suspend fun sendReadReceipt(
        credentials: AccountCredentials,
        receiptTo: List<String>,
        originalSubject: String?,
        originalMessageId: String?,
        deliveredTo: String? = null,
    ): ReadReceiptPreview? {
        val identity = receiptIdentity(accountStore.identities(credentials.id), deliveredTo)
        val mail = readReceiptMail(
            receiptTo = receiptTo,
            originalSubject = originalSubject,
            originalMessageId = originalMessageId,
            identityName = identity?.name,
            identityEmail = identity?.email,
            deliveredTo = deliveredTo,
            accountAddress = credentials.username,
            // Drawn here, never inside the builder: a random number makes bytes no test can pin.
            boundary = "----sterna_mdn_${java.util.UUID.randomUUID().toString().replace("-", "")}",
        ) ?: return null
        enqueueSend(
            credentials = credentials,
            to = mail.to,
            subject = mail.subject,
            body = mail.body,
            fromName = mail.fromName,
            fromEmail = mail.fromEmail,
            prebuiltEntity = mail.prebuiltEntity,
            learnRecipients = mail.learnRecipients,
            draftUidValidity = null,
        )
        return mail.preview()
    }

    // ---- scheduled send ----

    /** Writes the row with its own copy of [attachments]; [fetchBlob] brings a JMAP blob back. */
    suspend fun insertScheduledSend(
        entity: ScheduledSendEntity,
        attachments: List<EmailBodyPart>,
        fetchBlob: suspend (EmailBodyPart) -> ByteArray,
    ): Long = insertScheduledWithAttachments(scheduledFilesDir, attachments, fetchBlob) { json ->
        scheduledSendDao.insert(entity.copy(attachmentsJson = json))
    }

    /** The parts [row] hands to [enqueueSend] at its hour; throws rather than drop one. */
    suspend fun scheduledSendAttachments(credentials: AccountCredentials, row: ScheduledSendEntity): List<EmailBodyPart> =
        scheduledPartsFor(credentials, OutboxAttachments.decode(row.attachmentsJson))

    /**
     * [stored] as parts a send or a draft save takes, uploaded again on JMAP; throws rather than drop
     * one. A cancelled schedule goes through here and not through `saveDraft`'s own upload, which
     * skips a part it fails to upload: the scheduled files are deleted right after that deposit.
     */
    suspend fun scheduledPartsFor(credentials: AccountCredentials, stored: List<OutboxAttachment>): List<EmailBodyPart> =
        scheduledPartsToSend(stored, credentials.protocol) { bytes, a ->
            host.uploadAttachment(credentials, bytes, a.type, a.name, a.disposition ?: "attachment", a.cid)
        }

    suspend fun scheduledSend(id: Long): ScheduledSendEntity? = scheduledSendDao.byId(id)

    /** The row first, its files after: a death in between leaks files, never a row without them. */
    suspend fun deleteScheduledSend(id: Long) {
        val row = scheduledSendDao.byId(id)
        scheduledSendDao.delete(id)
        row?.let { deleteScheduledAttachments(it.attachmentsJson, scheduledFilesDir) }
    }

    suspend fun setScheduledSendFailedAttempts(id: Long, failedAttempts: Int) =
        scheduledSendDao.setFailedAttempts(id, failedAttempts)

    suspend fun scheduledSends(): List<ScheduledSendEntity> = scheduledSendDao.all()

    fun scheduledSendsFlow(): Flow<List<ScheduledSendEntity>> = scheduledSendDao.observeAll()
}

/** Retry on one row, [requeue] being `OutboxDao.requeueForRetry`; a JVM test runs it on SQLite. */
internal suspend fun requeueForRetry(
    id: Long,
    now: Long,
    requeue: suspend (id: Long, now: Long) -> Int,
    scheduler: OutboxScheduler?,
) {
    // A row the worker is delivering or the composer holds is not Retry's to take back: the
    // schedule would replace its live worker and deliver it twice. `OutboxDao.requeueForRetry` is
    // one statement with no read before it, so a row already flipped to SENDING is refused; the
    // worker's own flip is unconditional, so a flip landing after this write is not.
    if (requeue(id, now) > 0) scheduler?.schedule(id, 0)
}

/**
 * A server blob with no bytes this account can reach. The send refuses with it, and the composer
 * says the same when asked to open such an attachment.
 */
fun attachmentPreparedForAnotherAccount(name: String?): String =
    "The attachment ${name ?: "attachment"} was prepared for another account; attach it again."

/**
 * One `(lowercased address, name)` per person of one send: `Bob <bob@x.org>` and `bob@x.org` in the
 * same message are one send to Bob, not two. The first name given wins over a bare address.
 */
internal fun sendsToRecord(addresses: List<String>): List<Pair<String, String?>> =
    addresses.map { it.trim() }.filter { it.contains('@') }
        .map { parseAddress(it) }
        .groupBy({ it.second.lowercase() }, { it.first })
        .map { (email, names) -> email to names.firstOrNull { it != null } }

private fun parseAddress(s: String): Pair<String?, String> {
    val m = Regex("^(.*?)<([^>]+)>").find(s.trim())
    return if (m != null) m.groupValues[1].trim().ifBlank { null } to m.groupValues[2].trim()
    else null to s.trim()
}
