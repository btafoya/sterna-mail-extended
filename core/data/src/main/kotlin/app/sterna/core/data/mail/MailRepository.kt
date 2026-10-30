package app.sterna.core.data.mail

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStorage
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.UnifiedInbox
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.ListedScript
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.ScriptSnapshot
import app.sterna.core.data.filter.SieveParse
import app.sterna.core.data.filter.SieveScript
import app.sterna.core.data.db.AccountMailboxRole
import app.sterna.core.data.db.AccountMailboxUnread
import app.sterna.core.data.db.CachedPreview
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.EmailLabels
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailBodyEntity
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.OutboxEntity
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.RECENT_EMAIL_ROW_COLUMNS
import app.sterna.core.data.db.ReadAndStar
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.db.SnoozedEntity
import app.sterna.core.data.db.SnoozedListRow
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.ServerContactDao
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.data.db.caughtUp
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.pgp.PgpEngine
import app.sterna.core.data.pgp.SmimeCertificates
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.data.rethrowIfCancelled
import app.sterna.core.data.search.SearchHistoryRepository
import app.sterna.core.data.settings.OutgoingDateZones
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.DownloadLimits
import app.sterna.core.jmap.BearerAuth
import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.jmapDiscoveryClient
import app.sterna.core.jmap.WindowWalk
import app.sterna.core.imap.CryptoEnvelope
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.isSmime
import app.sterna.core.imap.ImapUidValidityChanged
import app.sterna.core.imap.inlineArmorKind
import app.sterna.core.imap.inlineDisplayText
import app.sterna.core.imap.inlineVerifyBytes
import app.sterna.core.imap.decodeMailboxPath
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.data.pgp.PgpDecrypted
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import app.sterna.core.jmap.model.EmailHeader
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.PushSubscription
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.core.jmap.model.VacationResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.UUID

/** Cap on changes applied incrementally before falling back to a full query. Also this file's
 *  SQLite bound-variable bound: every `in (...)` id list is chunked at it, under SQLite's 999. */
internal const val MAX_CHANGES = 200

/** How long a locally mutated id is protected from sync eviction (ms). Must outlast the server's
 *  read-after-write delay on a move-back, or the next reconcile prunes the message. */
private const val RECENT_MUTATION_MS = 45_000L

internal const val PAGE_SIZE = 50

/** The per-id SetError type meaning the id does not exist in the account (RFC 8620 §5.3). Action
 *  paths receiving it prune the cached row. */
private const val SET_ERROR_NOT_FOUND = "notFound"

/** Per-APPEND fill target for the conversation list: rows are threads, so one network page of
 *  messages can add almost no visible rows. Keep fetching until this many new representatives... */
private const val APPEND_THREAD_TARGET = 10

/** ...but never more than this many pages per APPEND: one giant thread must not chain unbounded
 *  fetches. */
private const val MAX_APPEND_FILL_PAGES = 4

/** How many of the inbox's newest messages to prefetch (bodies) into the cache per sync. */
private const val PREFETCH_COUNT = 20

/** Max cached message bodies kept per account (LRU); bounds on-device storage. */
private const val BODY_CACHE_CAP = 100

/** Largest body row we will write (chars of JSON, body + inline images). A row past SQLite's 2 MB
 *  cursor window can be inserted but never read back. */
internal const val MAX_CACHED_BODY_CHARS = 1_000_000

/** Tolerant JSON for the on-disk body cache (schema may add fields across versions). */
private val bodyCacheJson = Json { ignoreUnknownKeys = true }

/** A cached body row read back into its [Email], through the same `Email.serializer()` as a fresh fetch. */
internal fun decodeCachedEmail(bodyJson: String): Email =
    bodyCacheJson.decodeFromString(Email.serializer(), bodyJson)

/** Whether a body row of this size may be written without becoming unreadable later. */
internal fun fitsBodyCache(bodyJson: String, inlineImagesJson: String): Boolean =
    bodyJson.length + inlineImagesJson.length <= MAX_CACHED_BODY_CHARS

/** [cached]'s `$seen`, `$flagged`, `$answered` and `$forwarded` taken from the `emails` [row]: a
 *  cached body's keywords are frozen at write time (#148, #232, #259). Null [row] means unknown. A
 *  flag the row does not hold removes its key, as `toggleFlag` does; `$seen` alone is kept as false. */
internal fun reconcileCachedFlags(cached: MessageBody, row: ReadAndStar?): MessageBody {
    if (row == null) return cached
    val email = cached.email
    val labels = EmailLabels.decode(row.labelsJson)
    if (
        email.isSeen == row.seen && email.isFlagged == row.flagged &&
        email.isAnswered == row.answered && email.isForwarded == row.forwarded &&
        EmailLabels.of(email.keywords) == labels
    ) {
        return cached
    }
    val keywords = email.keywords.filterKeys { !it.startsWith(EmailLabels.PREFIX) } +
        ("\$seen" to row.seen) + labels.map { EmailLabels.PREFIX + it to true }
    return cached.copy(
        email = email.copy(
            keywords = keywords
                .withKeyword("\$flagged", row.flagged)
                .withKeyword("\$answered", row.answered)
                .withKeyword("\$forwarded", row.forwarded),
        ),
    )
}

private fun Map<String, Boolean>.withKeyword(keyword: String, held: Boolean): Map<String, Boolean> =
    if (held) this + (keyword to true) else this - keyword

/** The row [read] reports, or null when it cannot be read, the "unknown" [reconcileCachedFlags]
 *  answers by handing the body back. A locked database must not cost the open. */
internal suspend fun rowOrUnknown(read: suspend () -> ReadAndStar?): ReadAndStar? = runCatching { read() }.getOrNull()

/** Folders an IMAP search walks, in [MailboxDao.searchOrder]'s order. Trash and Junk are left out.
 *  The role decides, never the name, so it holds in every language. */
internal fun searchableFolderIds(folders: List<MailboxIdRole>): List<String> =
    folders.filterNot { it.role?.trim()?.lowercase() in NOT_SEARCHED_ROLES }.map { it.id }

/** The complement of [searchableFolderIds]: JMAP (`inMailboxOtherThan`) and the FTS crawl need the
 *  ids. One [NOT_SEARCHED_ROLES] source, so they cannot disagree. */
internal fun excludedSearchFolderIds(folders: List<MailboxIdRole>): List<String> =
    folders.filter { it.role?.trim()?.lowercase() in NOT_SEARCHED_ROLES }.map { it.id }

/** The roles a search never walks, as a server or a name table names them. */
internal val NOT_SEARCHED_BASE_ROLES = setOf("trash", "junk", "spam")

/** What a role carries when its folder CLAIMED it and another folder of the same account was
 *  elected to it. Only the roles of [NOT_SEARCHED_BASE_ROLES] are marked; it lives on the cache row
 *  only, while the fact of the loss crosses as [isLostRoleClaim] (#174). */
internal const val UNELECTED_ROLE_MARK = "~"

/** The stored role as the search filters may not act on it: a marked role answers as no role at
 *  all. Shared by [MailboxEntity.toMailbox] and [folderRoleMap]. */
internal fun unmarkedRole(role: String?): String? =
    role?.takeUnless { it.startsWith(UNELECTED_ROLE_MARK) }

/** Whether [role], as the cache stores it, says "this folder claimed a role and lost the election".
 *  A visibility question, never a routing one: it names no role. */
internal fun isLostRoleClaim(role: String?): Boolean =
    role != null && role.startsWith(UNELECTED_ROLE_MARK)

internal val NOT_SEARCHED_ROLES =
    NOT_SEARCHED_BASE_ROLES + NOT_SEARCHED_BASE_ROLES.map { UNELECTED_ROLE_MARK + it }

/** What an action that moved nothing does with the search-index row, see [noOpEvictionFor]. */
internal enum class NoOpEviction {
    /** Drop the cached row, leave the index row: the message is still in a searched folder. */
    SPARE_INDEX_ROW,

    /** Take both rows: no search looks at the folder, and no re-seed can ever clear a spared row. */
    TAKE_INDEX_ROW,
}

/** Which eviction an action that moved nothing must use: the index row may stay only where a search
 *  looks. Decided through [excludedSearchFolderIds], so it cannot drift from a search. */
internal fun noOpEvictionFor(mailboxId: String, role: String?): NoOpEviction =
    if (excludedSearchFolderIds(listOf(MailboxIdRole(mailboxId, role))).isEmpty()) {
        NoOpEviction.SPARE_INDEX_ROW
    } else {
        NoOpEviction.TAKE_INDEX_ROW
    }

/** Which of [ids] the cache says were already in [mailboxId], the server cannot be asked, an
 *  `Email/set` into the folder a message is in succeeds like any other. [rows] are read before the
 *  move, within the acting account (#31); an id with no cached row is not a no-op. */
internal fun idsAlreadyIn(rows: List<EmailEntity>, ids: Set<String>, mailboxId: String): List<String> =
    rows.filter { it.id in ids && it.mailboxId == mailboxId }.map { it.id }

/** The push pass's rows with the opening lines the cache already holds copied back onto them
 *  (#187): `@Upsert` replaces the whole row, so `preview = null` would blank the column. */
internal fun keepingCachedPreviews(rows: List<EmailEntity>, cached: Map<String, String>): List<EmailEntity> =
    rows.map { row -> if (row.preview != null) row else cached[row.id]?.let { row.copy(preview = it) } ?: row }

/** IMAP search hits with the opening line the cache holds for each, asked of [cachedPreviews] once
 *  per numbering: a line cached under another `uidValidity` belongs to another message (#99). */
internal suspend fun withCachedSearchPreviews(
    hits: List<EmailEntity>,
    cachedPreviews: suspend (ids: List<String>, uidValidity: Long?) -> List<CachedPreview>,
): List<EmailEntity> {
    val filled = hits.groupBy { it.uidValidity }.flatMap { (numbering, rows) ->
        val cached = byIdsChunked(rows.map { it.id }) { chunk -> cachedPreviews(chunk, numbering) }
        keepingCachedPreviews(rows, cached.associate { it.id to it.preview })
    }.associateBy { it.id }
    return hits.map { filled[it.id] ?: it }
}

/** How a confirmed batch `UID MOVE` splits, see [imapMoveOutcome]. */
internal data class ImapMoveOutcome(
    /** The ids the session can prove left the source folder: credit them, drop their rows. */
    val gone: List<String>,
    /** The ids it cannot: not "they stayed", but "nothing said". They fail. */
    val unproven: List<String>,
)

/** Which ids of a batch IMAP move really left the source folder, told from the ids nothing said
 *  anything about; both lists keep the arrival order. An empty [confirmedGone] means "nothing is
 *  confirmed", never "nothing moved". */
internal fun imapMoveOutcome(uidToId: Map<Long, String>, confirmedGone: Set<Long>): ImapMoveOutcome {
    val (proved, rest) = uidToId.entries.partition { it.key in confirmedGone }
    return ImapMoveOutcome(gone = proved.map { it.value }, unproven = rest.map { it.value })
}

/** The seen state of each message an IMAP undo brought home, keyed by the id its target carries:
 *  the move back renumbered it, so [fetched] holds ids no target knows. [messages] is (uid in the
 *  folder it left, id). A message the re-fetch did not return is absent, never guessed. */
internal fun seenOfRestoredImapRows(
    messages: List<Pair<Long, String>>,
    moved: app.sterna.core.imap.ImapMoved,
    fetched: List<EmailEntity>,
): Map<String, Boolean> {
    val seenByNewUid = fetched.mapNotNull { row -> ImapMailService.uidOf(row.id)?.let { it to row.seen } }.toMap()
    return messages.mapNotNull { (uid, id) -> moved.uids[uid]?.let { seenByNewUid[it] }?.let { id to it } }.toMap()
}

/** The id each message an IMAP undo brought home carries now, keyed by the id its target carries,
 *  paired as [seenOfRestoredImapRows] pairs them. A message the re-fetch did not return is absent:
 *  the cache holds no row for it, and an id is never built from a uid nothing re-read. */
internal fun idsOfRestoredImapRows(
    messages: List<Pair<Long, String>>,
    moved: app.sterna.core.imap.ImapMoved,
    fetched: List<EmailEntity>,
): Map<String, String> {
    val idByNewUid = fetched.mapNotNull { row -> ImapMailService.uidOf(row.id)?.let { it to row.id } }.toMap()
    return messages.mapNotNull { (uid, id) -> moved.uids[uid]?.let { idByNewUid[it] }?.let { id to it } }.toMap()
}

/** Whether a search answer may be presented as a total rather than as a floor. [scopeCoversAccount]
 *  is a fact about the account's folder LIST, never a list derived from it. */
internal fun searchAnswerIsTotal(
    scopeCoversAccount: Boolean,
    scanComplete: Boolean,
    found: Int,
    limit: Int,
): Boolean = scopeCoversAccount && scanComplete && found < limit

/** [searchAnswerIsTotal] as the IMAP walk asks it: with [knownFolders] empty the walk falls back to
 *  the inbox alone, so what came back describes one folder rather than the account. */
internal fun imapSearchComplete(
    knownFolders: List<String>,
    walkComplete: Boolean,
    found: Int,
    limit: Int,
): Boolean = searchAnswerIsTotal(
    scopeCoversAccount = knownFolders.isNotEmpty(),
    scanComplete = walkComplete,
    found = found,
    limit = limit,
)

/** [searchAnswerIsTotal] as a whole-account JMAP query asks it: with nothing cached the search
 *  spreads to the whole account, Trash and Junk included. [cachedFolders] is the raw folder list,
 *  never the exclusion list derived from it. */
internal fun jmapSearchComplete(
    cachedFolders: List<MailboxIdRole>,
    matchedIds: Int?,
    fetched: Int,
    limit: Int,
): Boolean = searchAnswerIsTotal(
    scopeCoversAccount = cachedFolders.isNotEmpty(),
    scanComplete = matchedIds != null && fetched >= matchedIds,
    found = matchedIds ?: fetched,
    limit = limit,
)

/** Refuse an address carrying a line break: the SMTP envelope and RFC 5322 headers are built by
 *  concatenation, so a CR or LF is an injection primitive. */
internal fun requireSingleLineAddresses(addresses: List<String>) {
    require(addresses.none { addr -> addr.any { it == '\r' || it == '\n' } }) {
        "An address contains a line break."
    }
}

/** The composer's chosen `From:` as a header string. It is string-built on the IMAP/SMTP path, so
 *  the display name must be RFC 5322-quoted or 2047-encoded here; JMAP is unaffected (#77). */
internal fun formatFromAddress(name: String?, email: String?): String? = when {
    email.isNullOrBlank() -> null
    else -> OutgoingMime.formatAddress(name, email)
}

/** The composer draft a queued outbox row reopens as (#70). Every per-message choice the row holds
 *  has to be here: a field missing from this mapping is not "not filled yet", it is cancelled. */
internal fun outboxDraftOf(
    item: OutboxEntity,
    attachments: List<EmailBodyPart>,
): MailSender.OutboxDraft = MailSender.OutboxDraft(
    to = item.recipients.split(",").joinToString(", ") { it.trim() },
    cc = item.cc?.split(",")?.joinToString(", ") { it.trim() }.orEmpty(),
    bcc = item.bcc?.split(",")?.joinToString(", ") { it.trim() }.orEmpty(),
    subject = item.subject,
    body = item.textBody,
    htmlBody = item.htmlBody,
    fromAccountId = item.accountId,
    fromEmail = item.fromEmail,
    attachments = attachments,
    inReplyTo = item.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
    references = item.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
    pgpMode = item.pgpMode,
    draftEmailId = item.draftEmailId,
    draftUidValidity = item.draftUidValidity,
    outboxId = item.id,
    requestReceipt = item.requestReceipt,
    originalMark = originalMarkOf(item.originalAccountId, item.originalEmailId, item.originalMark),
)

/** The Drafts-list row for a just-saved draft (#63). Every addressing field with a column must be
 *  here: the `@Upsert` replaces the row whole. [inReplyTo]/[references] are dropped by `toEntity`. */
internal fun savedDraftRow(
    accountId: String,
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
): EmailEntity = Email(
    id = emailId,
    accountId = accountId,
    mailboxId = draftMailboxId,
    subject = subject.ifBlank { null },
    preview = body.replace(Regex("\\s+"), " ").trim().take(256).ifBlank { null },
    receivedAt = java.time.Instant.now().toString(),
    from = listOf(from),
    to = to,
    cc = cc,
    bcc = bcc,
    inReplyTo = inReplyTo,
    references = references,
    hasAttachment = hasAttachment,
    keywords = mapOf("\$draft" to true, "\$seen" to true),
).toEntity(accountId, draftMailboxId)

/** Read a cached row through [read]; if reading it fails at all, drop it via [purge] and report a
 *  miss. SQLite throws on reading a row past its cursor window, and throws again on every retry. */
internal suspend fun <T> readCachedOrPurge(
    read: suspend () -> T?,
    purge: suspend () -> Unit,
): T? = runCatching { read() }.getOrElse {
    runCatching { purge() }
    null
}

/** The `List-Unsubscribe` / `List-Unsubscribe-Post` pair read off an IMAP raw source, in that
 *  order. Being a Map, a repeated header keeps the last occurrence, accepted. */
internal fun unsubscribeHeadersOf(raw: String): Pair<String?, String?> =
    MimeParser.headerOf(raw, "List-Unsubscribe") to MimeParser.headerOf(raw, "List-Unsubscribe-Post")

/** `Disposition-Notification-To` (RFC 8098) read off an IMAP raw source. That name and nothing
 *  else: `Return-Receipt-To` and `X-Confirm-Reading-To` are neither written nor read. */
internal fun readReceiptHeaderOf(raw: String): String? =
    MimeParser.headerOf(raw, "Disposition-Notification-To")

/** The address a relay says really wrote this message. One function for both protocols (#160). The
 *  rule is the shape of the field name, never a list of vendors: lowercased it must BEGIN with `x-`
 *  And contain `original-from`. The first occurrence, because it is deterministic. */
internal fun originalSenderHeaderOf(headers: List<Pair<String, String>>): String? =
    headers.firstOrNull { (name, _) ->
        val lower = name.lowercase()
        lower.startsWith("x-") && lower.contains("original-from")
    }
        ?.let { MimeParser.decodedHeaderValue(it.second) }
        ?.takeIf { it.isNotBlank() }

/** One best-effort read of a message's header fields (#160, #276): what it found, and whether it
 *  happened. [authenticationFailures] is null exactly when it did not. */
internal class OriginRead(val header: String?, val authenticationFailures: List<String>?, val succeeded: Boolean)

/** [headers] null is a read that failed: its verdict is unknown, never empty, or the cached body
 *  would say "nothing to report" for the life of the install. */
internal fun originReadOf(headers: List<Pair<String, String>>?, trustedNames: Set<String>): OriginRead =
    if (headers == null) {
        OriginRead(header = null, authenticationFailures = null, succeeded = false)
    } else {
        OriginRead(originalSenderHeaderOf(headers), AuthenticationResults.failuresOf(headers, trustedNames), succeeded = true)
    }

/** The `authserv-id`s this account believes: its own servers and its address domain. On JMAP both
 *  the typed host and the API host, since a `.well-known` lookup may land on another machine. */
internal fun authservNamesFor(credentials: AccountCredentials, apiUrl: String?): Set<String> {
    val hosts = if (credentials.protocol == MailProtocol.IMAP) {
        listOf(credentials.imap?.host)
    } else {
        listOf(hostOf(Jmap.sessionUrlFor(credentials.server)), apiUrl?.let(::hostOf))
    }
    val domain = credentials.username.substringAfterLast('@', missingDelimiterValue = "")
    return (hosts + domain).mapNotNull { name -> name?.trim()?.lowercase()?.removeSuffix(".")?.takeIf { it.isNotEmpty() } }.toSet()
}

private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

/** Whether an opened message's body may be written to the body cache. On JMAP a null origin means
 *  both "carries none" and "the read did not happen", and caching the second says "no origin" for
 *  ever; IMAP always caches, the header was read off the source already in hand. */
internal fun cachesBodyAfterOriginRead(protocol: MailProtocol, originReadSucceeded: Boolean): Boolean =
    protocol == MailProtocol.IMAP || originReadSucceeded

/** Whether the IMAP message whose source is [raw] asks for a read receipt, `null` for "cannot
 *  say". A blank source is unknown: `fetchSource` answers "" when the FETCH carries no body item. */
internal fun receiptRequestedInSource(raw: String): Boolean? {
    if (raw.isBlank()) return null
    return ReadReceiptHeader.recipients(readReceiptHeaderOf(raw)).isNotEmpty()
}

/** The same for JMAP, from the property answer. */
internal fun receiptRequestedInProperty(header: String?, serverRefusesProperty: Boolean): Boolean? =
    if (serverRefusesProperty) null else ReadReceiptHeader.recipients(header).isNotEmpty()

/** Whether the message source [raw] is an unknown rather than a message. A blank source is not "an
 *  empty draft": parsed it is a blank composer that looks usable, whose send destroys the server
 *  original. It judges the source, never a body. */
internal fun sourceCouldNotBeRead(raw: String): Boolean = raw.isBlank()

/** The octets to write when a message is saved as `.eml`: [raw] one byte per char, ISO-8859-1 and
 *  never UTF-8, that String is a byte container, and UTF-8 would double every octet above 0x7F. */
internal fun rawSourceBytes(emailId: String, raw: String): ByteArray {
    if (sourceCouldNotBeRead(raw)) {
        throw MessageUnavailableException("Source of $emailId came back empty; the server did not give us the message.")
    }
    return raw.toByteArray(Charsets.ISO_8859_1)
}

/** An attachment's octets never came back: zero of them. `isEmpty()`, not the blank-equivalent
 *  [sourceCouldNotBeRead] uses: a file of spaces IS a file. */
internal fun attachmentCouldNotBeRead(bytes: ByteArray): Boolean = bytes.isEmpty()

/** Refuse to hand on an attachment that [attachmentCouldNotBeRead]: a 0-byte `rapport.pdf` in
 *  Downloads carries the real name she chose, and the save path announces `message_export_saved` on
 *  the next line. `MessageUnavailableException` is reused for `readFailureStringRes` (#159). */
fun requireAttachmentBytes(emailId: String, partKey: String, bytes: ByteArray) {
    if (attachmentCouldNotBeRead(bytes)) {
        throw MessageUnavailableException("Attachment $partKey of $emailId came back empty; the server did not give us the bytes.")
    }
}

/** Run [stage], persisting an outbox item's payload after its row is inserted, and, if it throws,
 *  [rollback] the row: an orphan would be re-armed by [MailSender.unfinishedOutbox] and sent
 *  amputated (#70). */
internal suspend fun <T> stageOrRollback(rollback: suspend () -> Unit, stage: suspend () -> T): T =
    try {
        stage()
    } catch (t: Throwable) {
        rollback()
        throw t
    }

/** Arm delivery of the outbox row [id] after [holdMs], and answer [id] either way, a failure is
 *  written through [note] instead of being thrown: the row is already staged and `unfinishedOutbox`
 *  re-arms it, so a throw costs a delay, while relaying it makes a quick reply send twice. */
internal suspend fun armOrNote(
    id: Long,
    holdMs: Long,
    scheduler: OutboxScheduler?,
    note: suspend (String) -> Unit,
): Long {
    val failure = runCatching { scheduler?.schedule(id, holdMs) }
        .rethrowIfCancelled()
        .exceptionOrNull()
        ?: return id
    // Recording the reason must not become a second way to fail the queueing it reports on.
    runCatching { note(unarmedNote(failure)) }.rethrowIfCancelled()
    return id
}

/** What an unarmed row is told to carry. Diagnostic English, not a translated string: the row takes
 *  the sentinel `OutboxLogic.NOT_ARMED` and the screen translates it. Its opening is
 *  [OutboxLogic.NOT_ARMED_LEGACY_PREFIX]: rows written by 1.5.2/1.5.3 are recognised by it. */
internal fun unarmedNote(t: Throwable): String {
    val reason = t.message?.takeIf { it.isNotBlank() }
        ?: t.javaClass.simpleName.takeIf { it.isNotBlank() }
        ?: t.javaClass.name
    return "${OutboxLogic.NOT_ARMED_LEGACY_PREFIX} $reason. The message stays in the Outbox."
}

/**
 * What queueing a send does with a pre-built entity, and what it keeps in the row.
 * @property redactBody whether the body, the HTML and the attachments must be blanked in the row.
 */
internal data class OutboxPayload(
    val entityFile: String?,
    val redactBody: Boolean,
)

/** The two decisions [MailSender.enqueueSend] makes about a pre-built entity. Storing the
 *  entity depends on the entity and nothing else; blanking the row still depends on the mode, and
 *  must: only ciphertext may not be kept in the clear at rest. */
internal fun outboxPayload(prebuiltEntity: String?, pgpMode: PgpMode?): OutboxPayload =
    OutboxPayload(
        entityFile = prebuiltEntity,
        redactBody = pgpMode?.encrypts == true && prebuiltEntity != null,
    )

/** The pre-built entity of an outbox row about to be delivered: null when there is none, a hard
 *  failure when the named file cannot be read, the row's own body is blank or a shadow of the
 *  payload, so falling back would send a shell nobody wrote. Called once, before the fork. */
internal fun prebuiltEntityAt(path: String?, pgpMode: String?, readText: (String) -> String?): String? {
    // A signing or encrypting mode with no entity is a row the process died under before the entity was written:
    // its body is the plaintext the mode promised never to send as such.
    if (path == null) return if (pgpMode == null) null else error("The signed or encrypted message was never prepared.")
    return readText(path) ?: error("The prepared message payload is missing.")
}

/** The addresses a queued send teaches to the contact suggestions, all of them, or none. [learn]
 *  is false on exactly one path: a read receipt is queued on a gesture addressed to nobody. */
internal fun recipientsToLearn(recipients: List<String>, learn: Boolean): List<String> =
    if (learn) recipients else emptyList()

/** The name of the file a queued send's pre-built entity is written to. Nothing looks it up by
 *  name: [OutboxEntity.pgpEntityPath] stores the absolute path, so older rows keep working. */
internal const val PREBUILT_ENTITY_FILE = "prebuilt-entity.mime"

/** Max full-text search matches returned to the UI. */
private const val LOCAL_SEARCH_LIMIT = 100

// Header crawl. What it wants, not what it sends: every request is capped to the server's
// advertised maxObjectsInGet ([requestPageSize]).
private const val HEADER_PAGE = 500
private const val HEADER_MAX = 200_000
private const val INDEX_TTL_MS = 10 * 60 * 1000L
/** Give up a crawl pass after this many consecutive page failures (vs. skipping isolated bad pages). */
private const val MAX_CRAWL_ERRORS = 3

/** Rows per INSERT when persisting an Empty-trash snapshot (keeps one statement modest). */
private const val PURGE_SNAPSHOT_INSERT_BATCH = 500

/** Page size when resolving unread ids server-side (RFC 8620 maxObjectsInGet floor). */
private const val UNREAD_RESOLVE_PAGE = 500

/** Upper bound on the unread ids one server walk resolves (20 pages of 500). */
private const val UNREAD_RESOLVE_MAX = 10_000

/** Build the dynamic order by / where for the paged list. Mailbox ids are bound as parameters; the
 *  sort expression is a fixed whitelist. Pinning is its own entry, [SortOrder.FLAGGED_FIRST] (#111). */
private fun pagingQuery(
    // The folders this list covers, as (account id, mailbox id) pairs, see [folderScopeSql].
    scopes: List<Pair<String, String>>,
    sort: SortOrder,
    filter: ListFilter,
): SimpleSQLiteQuery = SimpleSQLiteQuery(
    pagingSql(scopes.size, sort, filter),
    scopes.flatMap { listOf(it.first, it.second) }.toTypedArray(),
)

/** The folder scope of a list query, for rows of [table]: a disjunction of (account id, mailbox id)
 *  Pairs, account id bound first. A mailbox id is unique only within its account, so a bare
 *  `mailboxId in (…)` also selects a sibling account's folder of that id (#121). */
internal fun folderScopeSql(scopeCount: Int, table: String): String =
    if (scopeCount == 0) "0" else List(scopeCount) { "($table.accountId = ? AND $table.mailboxId = ?)" }.joinToString(" OR ")

/** The flat (uncollapsed) list SQL. Bind order: [scopeCount] (account id, mailbox id) pairs,
 *  account first, see [folderScopeSql]. Spanning accounts is a matter of how many pairs are bound,
 *  never of dropping the account. */
internal fun pagingSql(
    scopeCount: Int,
    sort: SortOrder,
    filter: ListFilter,
): String {
    val orderBy = when (sort) {
        SortOrder.DATE_DESC -> "sortKey DESC"
        SortOrder.DATE_ASC -> "sortKey ASC"
        SortOrder.SUBJECT -> "LOWER(TRIM(subject)) ASC"
        SortOrder.SENDER -> "LOWER(TRIM(COALESCE(fromName, fromEmail))) ASC"
        SortOrder.UNREAD_FIRST -> "seen ASC, sortKey DESC"
        SortOrder.FLAGGED_FIRST -> "flagged DESC, sortKey DESC"
    }
    return "SELECT * FROM emails WHERE ${listRowsWhereSql(scopeCount, filter)} ORDER BY $orderBy"
}

/** Which rows the flat list holds, sort excluded. One clause, shared by the two readers that must
 *  not disagree, [pagingSql] and [selectionIdsSql], what "Select all" takes (#126). */
internal fun listRowsWhereSql(scopeCount: Int, filter: ListFilter): String {
    val scope = folderScopeSql(scopeCount, "emails")
    val seenFilter = if (filter.unreadOnly) " AND seen = 0" else ""
    val flaggedFilter = if (filter.flaggedOnly) " AND flagged = 1" else ""
    val attachmentFilter = if (filter.withAttachment) " AND hasAttachment = 1" else ""
    val notSnoozed = " AND ${notSnoozedSql("emails")}"
    return "($scope)$seenFilter$flaggedFilter$attachmentFilter$notSnoozed"
}

/** The rows of `MailboxDao.observeRoles` (#115), keyed by (account, folder), not by folder: servers
 *  number mailboxes per account (#121/#31). A role-less folder is left out rather than mapped to a
 *  blank (absent means "unknown, fall back") and so is a marked role ([unmarkedRole]). */
internal fun folderRoleMap(rows: List<AccountMailboxRole>): Map<Pair<String, String>, String> =
    rows.mapNotNull { row -> unmarkedRole(row.role)?.let { (row.accountId to row.id) to it } }.toMap()

/** The keys "Select all" may take: the rows [pagingSql] draws, as (accountId, id). In conversation
 *  view a thread is drawn only when its representative wears what [filter] asks for
 *  ([representativeIndicatorsSql]): a starred member of a thread drawn unstarred stays out (#126).
 *  Bind order: [scopeCount] pairs, once per [selectionScopeRepeats]. */
internal fun selectionIdsSql(scopeCount: Int, filter: ListFilter, conversationView: Boolean): String {
    val flat = "SELECT accountId, id FROM emails WHERE ${listRowsWhereSql(scopeCount, filter)}"
    if (!threadGatedSelection(filter, conversationView)) return flat
    val sameThread = { a: String, b: String ->
        "$a.accountId = $b.accountId AND COALESCE($a.threadId, $a.id) = COALESCE($b.threadId, $b.id)"
    }
    return "$flat AND EXISTS (SELECT 1 FROM emails r WHERE ${sameThread("r", "emails")} " +
        "AND (${folderScopeSql(scopeCount, "r")}) AND ${notSnoozedSql("r")}" +
        representativeIndicatorsSql(filter, "r") +
        " AND NOT EXISTS (SELECT 1 FROM emails s WHERE ${sameThread("s", "r")} " +
        "AND (${folderScopeSql(scopeCount, "s")}) AND ${notSnoozedSql("s")} AND s.sortKey > r.sortKey))"
}

/** Whether "Select all" must also ask the thread's representative: only in conversation view, and
 *  only for the criteria the row reads off it. The unread criterion needs no gate: a thread is drawn
 *  when any member is unread, so every unread member belongs to a drawn row. */
private fun threadGatedSelection(filter: ListFilter, conversationView: Boolean): Boolean =
    conversationView && (filter.flaggedOnly || filter.withAttachment)

/** How many times [selectionIdsSql] binds the scope pairs. */
internal fun selectionScopeRepeats(filter: ListFilter, conversationView: Boolean): Int =
    if (threadGatedSelection(filter, conversationView)) 3 else 1

internal fun selectionIdsQuery(
    scopes: List<Pair<String, String>>,
    filter: ListFilter,
    conversationView: Boolean,
): SimpleSQLiteQuery = SimpleSQLiteQuery(
    selectionIdsSql(scopes.size, filter, conversationView),
    List(selectionScopeRepeats(filter, conversationView)) { scopes.flatMap { listOf(it.first, it.second) } }
        .flatten().toTypedArray(),
)

/** The star and paperclip criteria of [filter], asked of the row [alias] a collapsed thread is drawn
 *  from. The representative's own columns, never an aggregate over the thread: the row shows
 *  `e.flagged` and `e.hasAttachment`, and a filter on "any member" would keep a row wearing neither
 *  (the #111 reasoning, applied to the filter). */
internal fun representativeIndicatorsSql(filter: ListFilter, alias: String): String =
    (if (filter.flaggedOnly) " AND $alias.flagged = 1" else "") +
        (if (filter.withAttachment) " AND $alias.hasAttachment = 1" else "")

/** The home-screen widget's read of the unified inbox, as one bounded statement. Which rows is
 *  [listRowsWhereSql] with the unread funnel off, so the pair scope (#121) and the snooze filter
 *  come free. `, id DESC` is a tie-break: rows commonly share a sortKey. */
internal fun recentUnifiedSql(scopeCount: Int): String =
    "SELECT $RECENT_EMAIL_ROW_COLUMNS FROM emails " +
        "WHERE ${listRowsWhereSql(scopeCount, ListFilter.NONE)} " +
        "ORDER BY sortKey DESC, id DESC LIMIT ?"

/** The most rows a widget read will ever ask SQLite for. A count derived from the cell's height
 *  reaches 0 on a short cell and goes negative on a bad one, and `limit -1` is not a limit at all. */
internal const val RECENT_ROWS_MAX: Int = 50

/** [RECENT_ROWS_MAX], and at least one row: see the reasoning there. */
internal fun recentRowBound(requested: Int): Int = requested.coerceIn(1, RECENT_ROWS_MAX)

/** [recentUnifiedSql], bound the way [pagingQuery] binds its scopes, plus the row bound last. */
internal fun recentUnifiedQuery(scopes: List<Pair<String, String>>, limit: Int): SimpleSQLiteQuery =
    SimpleSQLiteQuery(
        recentUnifiedSql(scopes.size),
        (scopes.flatMap { listOf<Any>(it.first, it.second) } + recentRowBound(limit)).toTypedArray(),
    )

/** What a widget read answers. [configured] is the difference between "no message" and "no answer":
 *  no scope means no account, an undecodable account blob, or an `inboxId` still null after a
 *  restore, so `configured = false` may not be drawn as "no account, add one". */
data class RecentInbox(val configured: Boolean, val rows: List<RecentEmailRow>)

internal inline fun recentUnifiedInboxFrom(
    scopes: List<Pair<String, String>>,
    read: (List<Pair<String, String>>) -> List<RecentEmailRow>,
): RecentInbox =
    if (scopes.isEmpty()) RecentInbox(configured = false, rows = emptyList())
    else RecentInbox(configured = true, rows = read(scopes))

/** The unified unread total broken down per account. A JMAP inbox contributes the unread count of
 *  its row in [rows], found by both halves of its scope (#121), and 0 while it has no row; never
 *  [storedUnread], written in the foreground only. An IMAP inbox contributes [storedUnread]. Built
 *  from [scopes], so an account whose inbox is unknown is absent, not present at 0. */
internal fun unreadByAccountFrom(
    scopes: List<Pair<String, String>>,
    rows: List<AccountMailboxUnread>,
    isImap: (String) -> Boolean,
    storedUnread: (String) -> Int,
): Map<String, Int> {
    val (imapScopes, jmapScopes) = scopes.partition { (accountId, _) -> isImap(accountId) }
    return jmapScopes.associate { (accountId, inboxId) ->
        accountId to (rows.firstOrNull { it.accountId == accountId && it.id == inboxId }?.unreadEmails ?: 0)
    } + imapScopes.associate { (accountId, _) -> accountId to storedUnread(accountId) }
}

/** Build the conversation-collapsed paged query: one row per thread, its count covering the view's
 *  members plus the Sent-role replies in [sentMailboxes] so the chip always equals the expansion.
 *  The account-wide total only keeps the row expandable. */
internal fun conversationQuery(
    scopes: List<Pair<String, String>>,
    sort: SortOrder,
    filter: ListFilter,
    // The account a folder view is pinned to. It no longer filters the rows; it selects which Sent
    // folders count.
    accountId: String?,
    // Each account's Sent folder as an (accountId, mailboxId) pair: bare Sent ids across accounts
    // would let a colliding mailbox id inflate that account's chip. NO default, deliberately.
    sentMailboxes: List<Pair<String, String>>,
): SimpleSQLiteQuery {
    // Bind order matches the clauses left-to-right, and every clause binds the same shape:
    // (accountId, mailboxId) per scope, account first; the account-wide total binds nothing.
    val sent = ConversationScope.sentFolders(sentMailboxes, accountId)
    val perClause = scopes.flatMap { listOf(it.first, it.second) }
    val chipClause = perClause + sent.flatMap { listOf(it.first, it.second) }
    val args = perClause + chipClause + perClause
    return SimpleSQLiteQuery(
        conversationSql(scopes.size, sort, filter, sent.size),
        args.toTypedArray(),
    )
}

/** The conversation-grouping SQL. Threads are grouped by the pair (accountId, thread key), never
 *  the key alone: a bare `group by tkey` collapses two accounts' conversations into one row. Bind
 *  order: `g` takes [scopeCount] pairs, account first; `c` the same pairs then one pair per
 *  [sentMailboxCount] Sent folder; the outer where binds like `g`; `t` takes none. */
internal fun conversationSql(scopeCount: Int, sort: SortOrder, filter: ListFilter, sentMailboxCount: Int = 0): String {
    val scope = folderScopeSql(scopeCount, "emails")
    val scopeOuter = folderScopeSql(scopeCount, "e")
    val sentAlternatives = " OR (accountId = ? AND mailboxId = ?)".repeat(sentMailboxCount)
    val notSnoozed = notSnoozedSql("emails")
    val notSnoozedOuter = notSnoozedSql("e")
    val having = if (filter.unreadOnly) " HAVING MIN(seen) = 0" else ""
    val indicators = representativeIndicatorsSql(filter, "e")
    val orderBy = when (sort) {
        SortOrder.DATE_DESC -> "e.sortKey DESC"
        SortOrder.DATE_ASC -> "e.sortKey ASC"
        SortOrder.SUBJECT -> "LOWER(TRIM(e.subject)) ASC"
        SortOrder.SENDER -> "LOWER(TRIM(COALESCE(e.fromName, e.fromEmail))) ASC"
        SortOrder.UNREAD_FIRST -> "g.threadUnread ASC, e.sortKey DESC"
        // e.flagged, the representative row's star, and not MAX(flagged) over the thread: sorting
        // on "any message is starred" pins a row wearing an empty star tapping cannot dislodge (#111).
        SortOrder.FLAGGED_FIRST -> "e.flagged DESC, e.sortKey DESC"
    }
    return """
        SELECT e.*, c.threadCount AS threadCount, t.threadTotal AS threadTotal, g.threadUnread AS threadUnread
        FROM emails e
        JOIN (
            SELECT accountId AS gacc, COALESCE(threadId, id) AS tkey, MAX(sortKey) AS maxKey, MIN(seen) AS threadUnread
            FROM emails
            WHERE ($scope) AND $notSnoozed
            GROUP BY gacc, tkey$having
        ) g ON COALESCE(e.threadId, e.id) = g.tkey AND e.accountId = g.gacc AND e.sortKey = g.maxKey
        JOIN (
            SELECT accountId AS cacc, COALESCE(threadId, id) AS ckey, COUNT(*) AS threadCount
            FROM emails
            WHERE (($scope)$sentAlternatives) AND $notSnoozed
            GROUP BY cacc, ckey
        ) c ON c.ckey = g.tkey AND c.cacc = g.gacc
        JOIN (
            SELECT accountId AS tacc, COALESCE(threadId, id) AS tkey2, COUNT(*) AS threadTotal
            FROM emails
            WHERE $notSnoozed
            GROUP BY tacc, tkey2
        ) t ON t.tkey2 = g.tkey AND t.tacc = g.gacc
        WHERE ($scopeOuter) AND $notSnoozedOuter$indicators
        GROUP BY g.gacc, g.tkey
        ORDER BY $orderBy
    """.trimIndent()
}

/** The predicate that hides messages snoozed into the future, for rows of [table]. Correlated on
 *  accountId as well as the email id: snoozes are keyed per account (#31). */
internal fun notSnoozedSql(table: String): String =
    "NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = $table.id " +
        "AND snoozed.accountId = $table.accountId AND snoozed.until > " +
        "(CAST(strftime('%s','now') AS INTEGER) * 1000))"

/** One row in the paged list. [threadCount] counts this view's members plus their Sent replies;
 *  [threadExpandable] marks a thread with 2+ cached messages account-wide, so the row unfolds when
 *  the others sit outside this view. */
data class InboxRow(
    val email: Email,
    val threadCount: Int,
    val unread: Boolean,
    val threadExpandable: Boolean = threadCount > 1,
)

/** An account-qualified message key. Same-server accounts can cache colliding email ids, so an
 *  action on id X would hit both. [accountId] is null only for an email never from the cache. */
data class EmailKey(val accountId: String?, val emailId: String)

/** One line of the per-sender screen. [email] is the grouping key (case-insensitively) and the
 *  value a filter rule is written on. [total]/[unread] count messages cached in one account. */
data class SenderVolume(
    val email: String,
    val name: String?,
    val total: Int,
    val unread: Int,
    val latest: Long,
)

fun Email.emailKey(): EmailKey = EmailKey(accountId, id)

data class MailboxMeta(
    val accountName: String,
    val mailboxId: String,
    val mailboxName: String,
    val unreadCount: Int,
)

data class AccountInboxMeta(
    val accountId: String,
    val accountName: String,
    val mailboxId: String,
    val mailboxName: String,
    val unreadCount: Int,
)

/** Outcome of a unified refresh across every account (#65/#92): the inboxes that synced, plus the
 *  per-account failures, so "I am offline" can be told from "one account is unreachable". */
data class UnifiedRefreshResult(
    val metas: List<AccountInboxMeta>,
    val failures: List<Throwable>,
) {
    /** Only when at least one account failed and none synced: one good account proves the link is
     *  up. All failing is the VPN-killswitch case the #65 banner exists for. */
    val isConnectivityFailure: Boolean get() = metas.isEmpty() && failures.isNotEmpty()
}

data class FolderRefresh(
    val mailboxId: String,
    val name: String,
    val role: String?,
    val emails: List<Email>,
    /** The ids this folder is to remember, or NULL when this refresh cannot say what the folder
     *  holds. An empty list cannot carry that refusal: `NewMailNotifier.seed` replaces, so an
     *  unknown taken as empty makes the next pass announce the whole day's mail. */
    val baselineIds: List<String>?,
    /** The ids the server said left this folder during the refresh; the notifier cancels exactly
     *  these banners (#134). Always empty on IMAP: that path replaces the cache with what it read,
     *  so inferring departure would cancel the banners of live mail. */
    val departedIds: List<String>,
    /** Where the opening line of each of these messages could be read from, keyed by cache id.
     *  Always empty on JMAP: a JMAP envelope brings `preview` with it. */
    val previewSources: Map<String, PreviewSource>,
)

/** What one notification pass read from the cache for one folder. [emails] are hydrated, so capped
 *  ([NOTIFY_CANDIDATE_MAX]); [baselineIds] are bounded by the time floor alone, because
 *  `NewMailNotifier.seed` replaces the baseline and a capped one forgets whatever the cap shed. */
data class NotifyRead(val emails: List<Email>, val baselineIds: List<String>)

/** Outcome of loading an account's server-side filter rules. */
sealed interface FilterRulesState {
    /** Not offered: a JMAP server without the Sieve capability, or an IMAP account signed in with
     *  OAuth or without an IMAP host. */
    data object Unsupported : FilterRulesState
    data class Loaded(
        /** The form rules of the opened script, the ones a sender block is checked against. */
        val rules: List<FilterRule>,
        /** True if a sender block must be refused: the opened script cannot be cut into blocks, or on
         *  JMAP the active script is the server's auto-reply, which a save would stop. */
        val foreignActiveScript: Boolean = false,
        /** The same fact under the name the filters screen asks it by. */
        val scriptUnreadable: Boolean = false,
        /** The script opened, see `chosenScriptName`; null when there is none, and a save creates `sterna`. */
        val scriptName: String? = null,
        /** Its text as the server returned it, null when [scriptName] is. */
        val scriptText: String? = null,
        /** Every script of the account, as the read listed them. */
        val scripts: List<ListedScript> = emptyList(),
        /** Opened by its name rather than picked, see [ScriptSnapshot.chosen]. */
        val chosen: Boolean = false,
        /** See `inactiveBesideActive`. */
        val inactiveBesideActive: Boolean = false,
        /** The Sieve extensions the server announced, null when it announced none: the form then offers everything and the server decides at Save. */
        val sieveExtensions: List<String>? = null,
    ) : FilterRulesState {
        /** [scriptText] cut into blocks, null when there is none or it cannot be cut. */
        val script: SieveScript?
            get() = (scriptText?.let { SieveScript.parse(it) } as? SieveParse.Readable)?.script

        /** What the filters screen lists: form rules, Sieve text blocks, Bulwark rules it locks. */
        val scriptRules: List<ScriptRule> get() = script?.rules.orEmpty()

        val snapshot: ScriptSnapshot get() = ScriptSnapshot(scriptName, scriptText, chosen)

        /** What a write starts from: [script], or an empty one, so an unreadable script is replaced whole. */
        val scriptToEdit: SieveScript
            get() = script ?: (SieveScript.parse("") as SieveParse.Readable).script
    }
}

/** A saved responder, and on IMAP the script now holding it, which the next save goes over. */
data class SavedVacation(val response: VacationResponse, val script: ScriptSnapshot?)

/** Outcome of loading an account's server-side vacation responder. */
sealed interface VacationState {
    /** The server was asked and has no vacation-responder support: a JMAP server without the
     *  capability, or a ManageSieve server that does not list `vacation`, `date` and `relational`. */
    data object Unsupported : VacationState

    /** Not offered to this account, whose server is never asked: IMAP signed in with OAuth or
     *  without an IMAP host. Kept apart from [Unsupported] so the screen does not blame the server. */
    data object AccountUnsupported : VacationState

    data class Loaded(
        val response: VacationResponse,
        /** True if a save must be confirmed first: on IMAP the opened script cannot be cut into
         *  blocks, and a save replaces it. Always false on JMAP, whose responder is not a script
         *  this app writes. */
        val foreignActiveScript: Boolean = false,
        /** On IMAP the script the responder was read from, which a save must find unchanged. */
        val script: ScriptSnapshot? = null,
    ) : VacationState
}

/** A message body ready to render: the full [Email] plus its inline images (cid → data: URI). */
data class MessageBody(
    val email: Email,
    val inlineImages: Map<String, String>,
    /** OpenPGP state, or null for ordinary mail. */
    val crypto: MessageCrypto? = null,
)

/** OpenPGP or S/MIME state of an opened message. */
sealed interface MessageCrypto {
    /** Crypto content detected but not yet decrypted/verified. */
    data class Locked(val kind: CryptoKind) : MessageCrypto

    /** Decrypted and/or signature-verified; plaintext lives only in memory. */
    data class Decrypted(
        val signature: PgpSignatureState,
        val signatureUserId: String?,
        /** Null for S/MIME, whose signer is a certificate, not a key id. */
        val signatureKeyId: Long?,
        val wasEncrypted: Boolean,
        /** The verdict is on an S/MIME certificate, and the reader words it for one. */
        val smime: Boolean = false,
    ) : MessageCrypto
}

/** A device-flow sign-in was refused, cancelled, or expired. [failure] carries the parsed error and
 *  (for Microsoft) the AADSTS code, so the UI can map it to a specific, actionable message. */
class OAuthDeniedException(val failure: DeviceTokenResult.Failed) :
    Exception(failure.description.ifBlank { failure.error })

/** The window every message list pages in. Top-level rather than a member of [MailRepository]: the
 *  refresh key in `RefreshAnchor.kt` is only correct because `enablePlaceholders` is false here. */
internal fun pagingConfig() = PagingConfig(
    pageSize = PAGE_SIZE,
    initialLoadSize = PAGE_SIZE * 3,
    prefetchDistance = PAGE_SIZE,
    enablePlaceholders = false,
)

class MailRepository(
    private val client: JmapClient,
    private val emailDao: EmailDao,
    private val emailFtsDao: EmailFtsDao,
    private val emailBodyDao: EmailBodyDao,
    private val mailboxDao: MailboxDao,
    private val imap: ImapMailService,
    scheduledSendDao: ScheduledSendDao,
    private val snoozedDao: SnoozedDao,
    recentContactDao: RecentContactDao,
    private val accountStore: AccountStorage,
    outboxDao: OutboxDao,
    /** The frozen destroy list of a confirmed Empty trash (#99). */
    private val purgeSnapshotDao: PurgeSnapshotDao,
    localDraftDao: LocalDraftDao,
    serverContactDao: ServerContactDao,
    mailboxUidValidityDao: MailboxUidValidityDao,
    outboxFilesDir: java.io.File,
    localDraftFilesDir: java.io.File,
    /** Defaulted beside [localDraftFilesDir] for the JVM tests that build a repository. */
    scheduledFilesDir: java.io.File = java.io.File(localDraftFilesDir.absoluteFile.parentFile, "scheduled"),
    private val oauthClient: OAuthClient = OAuthClient(),
    /** Autodiscovery's client, apart from [client] because it alone bounds how long one candidate
     *  host may take to connect; the shared bound outlives the whole discovery budget (#221). */
    private val discoveryClient: JmapClient = jmapDiscoveryClient(),
    /** OpenPGP operations (null = no provider wired; all PGP features disabled). */
    private val pgpEngine: PgpEngine? = null,
    /** S/MIME signature checks (null = none wired: signed mail shows as ordinary mail). */
    private val smimeEngine: SmimeEngine? = null,
    /** The user's S/MIME keys, to open a message encrypted for one (null = none wired: such a message says so). */
    private val smimeKeys: SmimeKeySource? = null,
    /** Where the certificates confirmed signatures bring are kept (null = none wired: nothing is kept). */
    private val smimeCertificates: SmimeCertificates? = null,
    /** App settings (null in tests): consulted for behavior toggles like mark-read-on-delete. */
    private val settings: SettingsRepository? = null,
    /** Persisted sync cursors (null in tests): deltas survive process death (issue #17). */
    private val syncStateStore: SyncCursorStore? = null,
    /** Swipes whose server write was still out, on disk (null in tests); see [OrphanedFlights]. */
    flightMarkerStore: FlightMarkerStore? = null,
    private val logInfo: (String, String, Throwable?) -> Unit = { _, _, _ -> },
    private val logWarn: (String, String, Throwable?) -> Unit = { _, _, _ -> },
    outgoingDates: OutgoingDateZones = OutgoingDateZones.DEVICE,
    /** Cleared when a linked account is revoked (null in tests). */
    searchHistory: SearchHistoryRepository? = null,
) : MailSendHost, MailDraftHost, MailFolderHost, MailAccountHost {
    /** Background scope for fire-and-forget work (body prefetch) that must outlive a sync call. */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Everything the app sends, held apart from what it reads; see [MailSender]. */
    val sending: MailSender = MailSender(
        client = client,
        imap = imap,
        mailboxDao = mailboxDao,
        emailDao = emailDao,
        accountStore = accountStore,
        outboxDao = outboxDao,
        scheduledSendDao = scheduledSendDao,
        recentContactDao = recentContactDao,
        outboxFilesDir = outboxFilesDir,
        scheduledFilesDir = scheduledFilesDir,
        host = this,
        outgoingDates = outgoingDates,
        logWarn = logWarn,
    )

    /** Every draft, on the phone and on the server, held apart from what the app reads; see [MailDrafts]. */
    val drafts: MailDrafts = MailDrafts(
        client = client,
        imap = imap,
        mailboxDao = mailboxDao,
        emailDao = emailDao,
        accountStore = accountStore,
        localDraftDao = localDraftDao,
        mailboxUidValidityDao = mailboxUidValidityDao,
        localDraftFilesDir = localDraftFilesDir,
        host = this,
    )

    /** Every folder of an account: created, renamed, deleted, re-listed; see [MailFolders]. */
    val folders: MailFolders = MailFolders(
        client = client,
        imap = imap,
        mailboxDao = mailboxDao,
        emailDao = emailDao,
        accountStore = accountStore,
        host = this,
    )

    /** Every account: found, proved, created, reconciled, with its identities and settings; see [MailAccounts]. */
    val accounts: MailAccounts = MailAccounts(
        client = client,
        discoveryClient = discoveryClient,
        imap = imap,
        emailDao = emailDao,
        emailFtsDao = emailFtsDao,
        emailBodyDao = emailBodyDao,
        mailboxDao = mailboxDao,
        snoozedDao = snoozedDao,
        serverContactDao = serverContactDao,
        accountStore = accountStore,
        oauthClient = oauthClient,
        bgScope = bgScope,
        host = this,
        logWarn = logWarn,
        searchHistory = searchHistory,
    )

    class JmapAccountSession(
        val credentials: AccountCredentials,
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val rolesToMailboxId: Map<String, String>,
        // This account's own mailboxes: the global cache holds only the last-synced account.
        val mailboxes: List<Mailbox> = emptyList(),
    )

    @Volatile
    private var cachedSession: JmapAccountSession? = null

    override fun cachedJmapSession(): JmapAccountSession? = cachedSession

    /**
     * App-layer teardown for a folder the server has renumbered (#99). Without it the next push pass diffs
     */
    var onMailboxRenumbered: ((String, String) -> Unit)?
        get() = imap.onMailboxRenumbered
        set(value) { imap.onMailboxRenumbered = value }

    private val tokenRefresher = OAuthTokenRefresher(oauthClient, accountStore)

    private val cacheJson = bodyCacheJson
    private val inlineImagesSerializer = MapSerializer(String.serializer(), String.serializer())

    /**
     * Where an IMAP message was moved (for undo): emailId → (destination folder, new UID, and that folder's
     * numbering as COPYUID stated it). In memory only, like the Undo itself.
     */
    private val lastImapMove = java.util.concurrent.ConcurrentHashMap<String, ImapLoc>()

    /**
     * Per-mailbox JMAP state for incremental sync, written through to [syncStateStore] so cursors survive
     * process death (#17).
     */
    private val syncStates = SyncCursors(syncStateStore)

    private val flights = OrphanedFlights(
        store = flightMarkerStore,
        configuredAccountIds = { if (accountStore.accountsUnreadable()) null else accountStore.accounts().map { it.id } },
        dropCursors = { dropSyncCursors(it) },
    )

    /** Every touch of [flights] may read or `commit()` a preferences file. */
    private suspend fun <T> flightsOffMain(block: OrphanedFlights.() -> T): T =
        withContext(Dispatchers.IO) { flights.block() }

    private fun putSyncState(key: String, state: SyncState) = syncStates.put(key, state)

    private fun dropSyncState(key: String) = syncStates.drop(key)

    // Not settled here: `advanceEmailState` reads through this on the main thread. A cursor it
    // advances before the settle is dropped by that settle, which `syncMailbox` runs before resuming.
    private fun loadSyncState(key: String): SyncState? = syncStates.load(key)

    /**
     * Advance the affected mailboxes' stored emailState to what a local mutation returned, so the next
     */
    private fun advanceEmailState(newState: String?, accountId: String, vararg mailboxIds: String?) {
        val s = newState ?: return
        mailboxIds.filterNotNull().distinct().forEach { mb ->
            val k = syncKey(accountId, mb)
            loadSyncState(k)?.let { putSyncState(k, it.copy(emailState = s)) }
        }
    }

    // Keyed by (account, mailbox), not mailbox alone: same-server accounts can share a mailbox id.
    private fun syncKey(accountId: String, mailboxId: String) = "$accountId$mailboxId"

    // Ids whose flag/seen we just changed locally, with the time: a delta run from the pre-change
    // queryState can report such a row `removed`. Keyed "$accountId:$emailId", ids collide (#31).
    private val recentlyMutated = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun mutationKey(accountId: String, emailId: String) = "$accountId:$emailId"

    /** Ids the app itself just moved between folders, for the notifier's diff filter (#50). For IMAP
     *  the marked id is the message's id at its destination, since an IMAP move changes the id. */
    val recentLocalMoves = RecentLocalMoves()

    private fun markRecentlyMutated(accountId: String, emailId: String) {
        recentlyMutated[mutationKey(accountId, emailId)] = System.currentTimeMillis()
    }
    private fun isRecentlyMutated(accountId: String, emailId: String): Boolean =
        isRecentlyMutatedKey(mutationKey(accountId, emailId))
    private fun isRecentlyMutatedKey(key: String): Boolean {
        val at = recentlyMutated[key] ?: return false
        if (System.currentTimeMillis() - at > RECENT_MUTATION_MS) {
            recentlyMutated.remove(key)
            return false
        }
        return true
    }

    private fun recentlyMutatedIds(accountId: String): List<String> {
        val prefix = "$accountId:"
        return recentlyMutated.keys
            .filter { it.startsWith(prefix) && isRecentlyMutatedKey(it) }
            .map { it.removePrefix(prefix) }
    }

    /**
     * What one [syncMailbox] pass brought back: [fetchedIds], spared by the retention prune (#110), and
     * [departedIds], the only ground for cancelling a banner (#134).
     */
    data class MailboxSync(
        val fetchedIds: List<String>,
        val departedIds: List<String>,
    )

    /** Both paths report what they fetched: an id fetched and not reported is deleted by the
     *  retention prune in the same cycle (#110). See [MailboxSync]. */
    override suspend fun syncMailbox(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        mailboxId: String,
        sizing: FolderSyncSizing,
        // Local StoredAccount id that tags cached rows (distinct from the JMAP [accountId]).
        localAccountId: String,
    ): MailboxSync {
        // Same belt as [refresh] (#121): a blank local account id strands every row this writes.
        require(localAccountId.isNotBlank()) {
            "syncMailbox() needs a real account id: caching mail under a blank one strands it (#121)."
        }
        // And one notch further: an id that WAS an account and is not one any more. It only refuses
        // to start, every branch below asks again immediately before each of its writes.
        checkAccountStillConfigured(localAccountId, accountStore.accounts().map { it.id })
        val key = syncKey(localAccountId, mailboxId)
        // Before the cursor read: an orphan's cursors must be gone before any of them is resumed from.
        flightsOffMain { settle() }
        val stored = loadSyncState(key)
        if (stored != null) {
            val queryChanges = client.emailQueryChanges(session, accountId, mailboxId, stored.queryState, MAX_CHANGES, auth)
            val changes = client.emailChanges(session, accountId, stored.emailState, MAX_CHANGES, auth)
            val canApply = queryChanges.calculated && changes.calculated &&
                !changes.hasMoreChanges && queryChanges.newQueryState != null && changes.newState != null
            if (canApply) {
                // Asked again: two round-trips have passed since the entry check, and all below writes.
                checkAccountStillConfigured(localAccountId, accountStore.accounts().map { it.id })
                // A reorder shows up in both removed and added: only genuinely-gone ids are removed.
                val added = queryChanges.added.toSet()
                // Never evict an id we just flagged/read locally: a delta from the pre-mutation
                // query state can report it removed. Memoised so eviction and log cannot disagree.
                val protectionVerdict = HashMap<String, Boolean>()
                val isProtected: (String) -> Boolean = { id ->
                    protectionVerdict.getOrPut(id) { isRecentlyMutated(localAccountId, id) }
                }
                val toRemove = deltaEvictions(queryChanges.removed, added, changes.destroyed, isProtected)
                // "Gone from THIS folder" is not "gone": `deleteByIds` names no mailbox, so evicting
                // a row that has already moved deletes the message from the folder it is now in.
                val inThisMailbox =
                    if (toRemove.isEmpty()) emptySet() else emailDao.idsForMailbox(localAccountId, mailboxId).toSet()
                val toDelete = mailboxScopedEvictions(toRemove, inThisMailbox)
                if (toDelete.isNotEmpty()) emailDao.deleteByIds(localAccountId, toDelete)
                // Every eviction the spare skipped; a `destroy` here is the one-shot loss [deltaEvictions] documents.
                val spared = sparedEvictions(queryChanges.removed, added, changes.destroyed, isProtected)
                if (spared.isNotEmpty()) {
                    logInfo(
                        "MailSync",
                        "spared $localAccountId/$mailboxId: ${spared.size} not evicted " +
                            "(locally mutated < ${RECENT_MUTATION_MS}ms ago): " +
                            spared.joinToString { (id, reason) -> "$id ${reason.log}" },
                        null,
                    )
                }
                val cachedIds = emailDao.idsForMailbox(localAccountId, mailboxId).toSet()
                val toFetch = deltaFetches(added, cachedIds, changes.updated)
                var elsewhere = 0
                if (toFetch.isNotEmpty()) {
                    val fetched = client.getEmailsWithMailboxIds(session, accountId, toFetch, auth)
                    checkAccountStillConfigured(localAccountId, accountStore.accounts().map { it.id })
                    // A move landing between the two change requests above is `updated` and not `removed` (#252).
                    val filed = stillFiledIn(mailboxId, fetched)
                    elsewhere = fetched.size - filed.size
                    emailDao.upsertAll(sparingInFlight(filed.map { it.toEntity(localAccountId, mailboxId) }))
                }
                putSyncState(key, SyncState(queryChanges.newQueryState!!, changes.newState!!))
                // Ghost sweep: a destroy can reach us through neither delta (Stalwart omits a
                // delegated account's), or be eaten by the recently-mutated spare while the cursors
                // advance past it. [shouldSweepGhosts] gates it, these states are account-wide (#107).
                val stateAdvanced = queryChanges.newQueryState != stored.queryState ||
                    changes.newState != stored.emailState
                val vanishedFromMailbox = queryChanges.removed.any { it !in added }
                val claim = ghostSweeps.claim(localAccountId, mailboxId, stateAdvanced, vanishedFromMailbox)
                logInfo(
                    "MailSync",
                    "incremental $localAccountId/$mailboxId: +${toFetch.size} -${toDelete.size} " +
                        "moved=${toRemove.size - toDelete.size} spared=${spared.size} sweep=${claim.reason} elsewhere=$elsewhere",
                    null,
                )
                val swept = if (claim.sweep) {
                    val ghosts = pruneGhostRows(session, accountId, auth, mailboxId, localAccountId)
                    // A failed sweep (null) pruned nothing: give its once-per-process credit back.
                    // It reports NO departure, an unanswered question is not "the message is gone".
                    if (ghosts == null) ghostSweeps.releaseFailed(localAccountId, mailboxId, claim)
                    ghosts.orEmpty()
                } else {
                    emptyList()
                }
                // What this delta asked for, written or not, is mostly ids the cache never held, so the
                // prune must spare it like a full query's page (#110). Beside it, `toRemove`, not
                // `queryChanges.removed` (a reorder) and not `toDelete` (folder-bounded), #134.
                return MailboxSync(fetchedIds = toFetch, departedIds = toRemove + swept)
            }
        }
        // Cold cache, or the server can't compute changes, full query.
        // One call, many writes, one delete: pages are upserted as they land and the DELETE runs
        // once at the end. [fullQueryWriteThrough] enforces that nothing is deleted unless the walk
        // ran to completion. Sized on the account, not by its caller: this branch replaces the folder.
        val full = fullQuerySizing(
            accountStore.account(localAccountId)?.syncWindow?.limit,
            sizing.windowTarget,
            session.getBatchSize(),
        )
        val walked = fullQueryWriteThrough<List<Email>, WindowWalk>(
            folderIds = { emailDao.idsForMailbox(localAccountId, mailboxId).toSet() },
            walk = { onPage -> client.queryEmailsWindow(session, accountId, mailboxId, full.windowTarget, full.pageSize, auth, onPage) },
            writePage = { fresh ->
                checkAccountStillConfigured(localAccountId, accountStore.accounts().map { it.id })
                emailDao.upsertAll(sparingInFlight(fresh.map { it.toEntity(localAccountId, mailboxId) }))
                markWalked(localAccountId, mailboxId, fresh)
            },
            keepIds = { reconcilableWindowIds(it) },
            spareIds = { recentlyMutatedIds(localAccountId) },
            reconcile = { _, keepIds, spare, evictable -> emailDao.reconcileMailbox(localAccountId, mailboxId, keepIds, spare, evictableIds = evictable) },
        )
        // Read whole only on the verdict `keepIds` reconciled with: a resumed or muted walk may have
        // skipped the very row an orphan is waiting for.
        if (reconcilableWindowIds(walked) != null) flightsOffMain { reread(localAccountId, mailboxId) }
        logInfo(
            "MailSync",
            "full query $mailboxId: ${walked.ids.size} emails " +
                "(window ${full.windowTarget} in pages of ${full.pageSize}, " +
                "asked for ${sizing.windowTarget} in pages of ${sizing.pageSize}) " +
                "reconcile=${fullQueryReconcileReason(walked)}",
            null,
        )
        val queryState = walked.queryState
        val emailState = walked.emailState
        // An orphan this walk did not settle keeps the folder off the delta: `settle` drops cursors
        // once per process life, and a delta never fetches a row the cache lost while the server
        // still files it, so resuming here hides the message until the process dies.
        val orphanWaits = flightsOffMain { awaitsReread(localAccountId, mailboxId) }
        if (queryState != null && emailState != null && !orphanWaits) {
            putSyncState(key, SyncState(queryState, emailState))
        } else {
            dropSyncState(key)
        }
        // NO departures from a full query: it knows what the folder holds, not what left it (#134).
        return MailboxSync(fetchedIds = walked.ids, departedIds = emptyList())
    }

    /**
     * Apply an account's sync window once a refresh has landed: drop what falls outside it, EXCEPT [freshIds]
     */
    private suspend fun pruneRetention(
        accountId: String,
        mailboxId: String,
        cutoffMillis: Long,
        freshIds: Set<String>?,
        keepNewest: Int,
        spareUnread: Boolean,
    ) {
        val cached = emailDao.retentionRows(accountId, mailboxId)
        val spareIds = recentlyMutatedIds(accountId).toSet()
        val gone = retentionEvictions(cached, cutoffMillis, freshIds, spareIds, keepNewest, spareUnread)
        val offWalk = unreadSparedOffWalk(cached, cutoffMillis, freshIds, spareIds, keepNewest, spareUnread)
        offWalk.chunked(MAX_CHANGES).forEach { emailDao.markOffWalk(accountId, it) }
        if (gone.isEmpty() && offWalk.isEmpty()) return
        // The index survives this, hence `evictFromCacheKeepingIndex`: every id here belongs to a
        // message the server still has. Chunked because the ids go into an `in (...)`.
        gone.chunked(MAX_CHANGES).forEach { emailDao.evictFromCacheKeepingIndex(accountId, it) }
        logInfo(
            "MailSync",
            "retention $accountId/$mailboxId: pruned ${gone.size}, " +
                "spared ${freshIds?.size ?: -1} fetched + floor $keepNewest + ${offWalk.size} unread",
            null,
        )
    }

    /**
     * When each (account, mailbox) may be existence-swept again: once on the first sync of a process, then at
     * most once per [GHOST_SWEEP_MIN_INTERVAL_MS].
     */
    private val ghostSweeps = GhostSweepSchedule()

    /** Any failure prunes nothing and returns NULL, which must stay distinct from an empty list.
     *  What it did prune may be reported as departed (#134). */
    private suspend fun pruneGhostRows(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        mailboxId: String,
        localAccountId: String,
    ): List<String>? {
        val cached = emailDao.idsForMailbox(localAccountId, mailboxId)
        if (cached.isEmpty()) return emptyList()
        // null = the check never answered, which is what [ghostEvictions] prunes nothing for.
        val notFound: Set<String>? = runCatching {
            // Chunked for the server's maxObjectsInGet. A chunk that throws fails the whole attempt.
            cached.chunked(MAX_CHANGES).flatMapTo(mutableSetOf()) { chunk ->
                client.missingEmailIds(session, accountId, chunk, auth)
            }
        }.getOrElseUnlessCancelled { null }
        val ghosts = ghostEvictions(cached, notFound)
        if (ghosts.isNotEmpty()) pruneServerGone(localAccountId, ghosts)
        if (notFound == null) {
            logInfo("MailSync", "ghost sweep $localAccountId/$mailboxId: failed over ${cached.size} cached", null)
            return null
        }
        logInfo(
            "MailSync",
            "ghost sweep $localAccountId/$mailboxId: -${ghosts.size} of ${cached.size} cached",
            null,
        )
        return ghosts
    }

    /** The only ground on which the notification layer may take a banner down (#134): a delta cannot
     *  tell a departure from a keyword change. A failed read confirms nothing and does not throw. */
    suspend fun confirmDepartures(
        credentials: AccountCredentials,
        mailboxId: String,
        emailIds: List<String>,
    ): List<String> {
        if (emailIds.isEmpty() || credentials.protocol == MailProtocol.IMAP) return emptyList()
        val confirmed = confirmedDepartures(emailIds, mailboxId) { ids ->
            // Inside the supplier: a pass with nothing to confirm never even opens a session.
            val ctx = jmapSession(credentials)
            client.mailboxIdsOf(ctx.session, ctx.accountId, ids, ctx.auth)
        }
        if (confirmed.size != emailIds.size) {
            logInfo(
                "MailSync",
                "banner departures ${credentials.id}/$mailboxId: ${confirmed.size} of " +
                    "${emailIds.size} confirmed gone by the server",
                null,
            )
        }
        return confirmed
    }

    /** [budgetMs] is the only bound and has no default: the block it reaches is blocking and has no
     *  suspension point, so a caller's `withTimeout` would cancel the coroutine while the thread
     *  stayed on the socket. */
    suspend fun notificationPreview(
        credentials: AccountCredentials,
        mailboxId: String,
        source: PreviewSource,
        budgetMs: Int,
    ): String? = imap.fetchPreview(credentials, mailboxId, source.uid, source.part, budgetMs)

    /**
     * The UIDVALIDITY this folder was last observed under, or null when none was ever recorded for it.
     */
    suspend fun recordedNumbering(accountId: String, mailboxId: String): Long? =
        imap.recordedUidValidity(accountId, mailboxId)

    /** Only for an explicit per-id `notFound`. The index delete is guarded: an index that cannot be
     *  written (#71) must never abort the removal of the cached row. */
    private suspend fun pruneServerGone(localAccountId: String, emailIds: List<String>) {
        // A message the server no longer holds has no way back. A swipe holding its row in flight
        // loses it here, or a write refused later in the same gesture would put a dead row back.
        forfeitInFlight(localAccountId, emailIds)
        // Chunked: every id goes into an `in (...)`, and SQLite refuses more than 999 bindings.
        emailIds.chunked(MAX_CHANGES).forEach { chunk ->
            val ids = emailDao.emailsByIds(localAccountId, chunk).map { it.id }
            if (ids.isEmpty()) return@forEach
            emailDao.deleteByIds(localAccountId, ids)
            runCatching { emailFtsDao.deleteByIds(localAccountId, ids) }
            ids.forEach { runCatching { emailBodyDao.deleteById(localAccountId, it) } }
        }
    }

    /** The overload below, over the stored accounts. An account whose inbox is unknown is absent
     *  from the map, never 0. */
    fun observeUnifiedInboxUnreadByAccount(): Flow<Map<String, Int>> = UnifiedInbox.unreadByAccount(
        accountStore.accountsFlow,
    ) { scopes -> observeUnifiedInboxUnreadByAccount(scopes) }

    /**
     * "All inboxes (N)" as one number, [observeUnifiedInboxUnreadByAccount] totalled, so nothing is observed
     * a second time. The addition is [UnifiedInbox.total], executed by a test.
     */
    fun observeUnifiedInboxUnread(): Flow<Int> =
        observeUnifiedInboxUnreadByAccount().map(UnifiedInbox::total)

    /** The read behind the "latest messages" widget, sharing the list's predicate [listRowsWhereSql]
     *  (the same (account, folder) pair scope, #121). The list's sort and filters do not apply. */
    suspend fun recentUnifiedInbox(limit: Int): RecentInbox =
        recentUnifiedInboxFrom(accountStore.allInboxScopes()) { scopes ->
            emailDao.recentUnified(recentUnifiedQuery(scopes, limit))
        }

    /** [recentUnifiedInbox] observed, so the widget follows the mail (#112). The emitted value is a
     *  trigger, not a snapshot: `RecentMailWidgetDraw.read` also reads the setting and the app lock. */
    fun observeRecentUnifiedInbox(limit: Int): Flow<RecentInbox> = UnifiedInbox.recentRows(
        accountStore.accountsFlow,
    ) { scopes -> emailDao.observeRecentUnified(recentUnifiedQuery(scopes, limit)) }

    /** Unread across the inboxes of [scopes], one entry per account, re-emitted when a folder row
     *  changes. The decision is [unreadByAccountFrom]. */
    fun observeUnifiedInboxUnreadByAccount(scopes: List<Pair<String, String>>): Flow<Map<String, Int>> =
        mailboxDao.observeUnread().map { rows ->
            unreadByAccountFrom(
                scopes = scopes,
                rows = rows,
                isImap = ::isImapAccount,
                storedUnread = { accountId -> accountStore.account(accountId)?.unread ?: 0 },
            )
        }

    /**
     * Cached mailboxes of [accountId], updated reactively. Each folder carries the unread count its
     * cache row holds, on both protocols: a count of its cached messages is zero until it is opened.
     */
    fun observeMailboxes(accountId: String): Flow<List<Mailbox>> =
        mailboxDao.observeAll(accountId).map { rows -> rows.map { it.toMailbox() } }

    /**
     * Paged list of cached emails for [scopes], the unified inbox's (account id, inbox id) pairs. Pairs, not
     */
    fun pagedMailbox(
        scopes: List<Pair<String, String>>,
        sort: SortOrder,
        filter: ListFilter,
        conversationView: Boolean,
        // Each account's Sent-role folder as an (accountId, mailboxId) pair, the conversation chip
        // counts the thread's Sent replies. NO default: an omission is a chip that counts too few.
        sentMailboxes: List<Pair<String, String>>,
    ): Flow<PagingData<InboxRow>> {
        if (scopes.isEmpty()) return flowOf(PagingData.empty())
        return if (conversationView) {
            Pager(
                config = pagingConfig(),
                pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.conversationPagingSource(ShownReplyMarksQuery(conversationQuery(scopes, sort, filter, accountId = null, sentMailboxes = sentMailboxes)))) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        } else {
            Pager(
                config = pagingConfig(),
                pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.pagingSource(ShownReplyMarksQuery(pagingQuery(scopes, sort, filter)))) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        }
    }

    /**
     * Paged view of a single folder, backed by a [RemoteMediator]: scrolling past the cached rows fetches the
     * next older page, so a large folder doesn't stop at the sync window.
     */
    @OptIn(ExperimentalPagingApi::class)
    fun pagedFolder(
        credentials: AccountCredentials,
        mailboxId: String,
        sort: SortOrder,
        filter: ListFilter,
        conversationView: Boolean,
        // The account's Sent-role folder as an (accountId, mailboxId) pair, see [pagedMailbox]. NO default.
        sentMailboxes: List<Pair<String, String>>,
    ): Flow<PagingData<InboxRow>> {
        val scopes = listOf(credentials.id to mailboxId)
        return if (conversationView) {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId, conversationView = true),
                pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.conversationPagingSource(ShownReplyMarksQuery(conversationQuery(scopes, sort, filter, credentials.id, sentMailboxes)))) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        } else {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId, conversationView = false),
                pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.pagingSource(ShownReplyMarksQuery(pagingQuery(scopes, sort, filter)))) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        }
    }

    @OptIn(ExperimentalPagingApi::class)
    private fun <V : Any> folderMediator(
        credentials: AccountCredentials,
        mailboxId: String,
        conversationView: Boolean,
    ): RemoteMediator<Int, V> {
        return object : RemoteMediator<Int, V>() {
            // The cache is populated by refresh()/sync; only extend it on scroll.
            override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, V>,
            ): MediatorResult {
                if (loadType != LoadType.APPEND) {
                    return MediatorResult.Success(endOfPaginationReached = loadType == LoadType.PREPEND)
                }
                return try {
                    val (added, total, cached) = if (credentials.protocol == MailProtocol.IMAP) {
                        val offset = emailDao.walkedCountForMailbox(credentials.id, mailboxId)
                        val (entities, exists) = imap.fetchOlderPage(credentials, mailboxId, offset, PAGE_SIZE, listPreviewCache(credentials.id))
                        if (entities.isNotEmpty()) imapFolderWrites(credentials.id).writePage(entities)
                        Triple(entities.size, exists, emailDao.walkedCountForMailbox(credentials.id, mailboxId))
                    } else {
                        val ctx = jmapSession(credentials)
                        // Anchor on the oldest walked representative rather than an absolute offset:
                        // the anchor doesn't shift when new mail arrives, so no page is skipped or
                        // duplicated. It overlaps at worst, which upsert-only insertion makes harmless.
                        // Every cache read of this branch leaves out the rows `WALKED_SQL` leaves out.
                        val anchorId = emailDao.oldestRepresentativeEmailId(credentials.id, mailboxId)
                        val before = emailDao.walkedCountForMailbox(credentials.id, mailboxId)
                        val repsBefore = if (conversationView) {
                            emailDao.representativeCountForMailbox(credentials.id, mailboxId)
                        } else {
                            0
                        }
                        var page = try {
                            client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                calculateTotal = true,
                                anchorId = anchorId,
                                anchorOffset = if (anchorId != null) 1 else 0,
                            )
                        } catch (e: JmapException) {
                            // The anchor can have dropped out of the folder; fall back once to an
                            // absolute position at the walked row count.
                            if (e.errorType != "anchorNotFound") throw e
                            client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                position = before, calculateTotal = true,
                            )
                        }
                        if (page.emails.isNotEmpty()) {
                            emailDao.upsertAll(sparingInFlight(page.emails.map { it.toEntity(credentials.id, mailboxId) }))
                            markWalked(credentials.id, mailboxId, page.emails)
                        }
                        // The collapsed list's rows are threads: keep fetching at the window edge
                        // until enough new rows land, bounded.
                        val maxFetches = if (conversationView) MAX_APPEND_FILL_PAGES else 2
                        val fillTarget = if (conversationView) APPEND_THREAD_TARGET else 1
                        var fetches = 1
                        while (page.emails.isNotEmpty() && fetches < maxFetches) {
                            val cachedNow = emailDao.walkedCountForMailbox(credentials.id, mailboxId)
                            val gained = if (conversationView) {
                                emailDao.representativeCountForMailbox(credentials.id, mailboxId) - repsBefore
                            } else {
                                cachedNow - before
                            }
                            if (gained >= fillTarget) break
                            val serverTotal = page.total
                            if (serverTotal != null && cachedNow >= serverTotal) break
                            // Chain each follow-up on the page just fetched, never on the cached row
                            // count: the cache is not a contiguous prefix, so a gap would never heal.
                            page = client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                calculateTotal = true,
                                anchorId = page.emails.last().id,
                                anchorOffset = 1,
                            )
                            if (page.emails.isNotEmpty()) {
                                emailDao.upsertAll(sparingInFlight(page.emails.map { it.toEntity(credentials.id, mailboxId) }))
                                markWalked(credentials.id, mailboxId, page.emails)
                            }
                            fetches++
                        }
                        Triple(page.emails.size, page.total, emailDao.walkedCountForMailbox(credentials.id, mailboxId))
                    }
                    val reachedEnd = added == 0 || (total != null && cached >= total)
                    MediatorResult.Success(endOfPaginationReached = reachedEnd)
                } catch (t: Throwable) {
                    MediatorResult.Error(t)
                }
            }
        }
    }


    /** All cached (account, id) keys for the given (account, mailbox) scopes, the whole folder,
     *  filters included, so it drives cache eviction and not "select all" (see [selectableIds]). */
    suspend fun cachedIds(scopes: List<Pair<String, String>>): List<EmailKey> =
        scopes.flatMap { (accountId, mailboxId) ->
            emailDao.idsForMailbox(accountId, mailboxId).map { EmailKey(accountId, it) }
        }

    /**
     * The keys "Select all" may take outside a search: the rows the flat list is paging, through
     */
    suspend fun selectableIds(
        scopes: List<Pair<String, String>>,
        filter: ListFilter,
        conversationView: Boolean,
    ): List<EmailKey> =
        emailDao.keysForSelection(selectionIdsQuery(scopes, filter, conversationView))
            .map { EmailKey(it.accountId, it.id) }

    /**
     * The keys of the unread messages this phone holds in the given scopes, capped by UNREAD_RESOLVE_MAX.
     */
    suspend fun cachedUnreadKeys(scopes: List<Pair<String, String>>): List<EmailKey> {
        return scopes.flatMap { (accountId, mailboxId) ->
            emailDao.unreadKeys(accountId, mailboxId, UNREAD_RESOLVE_MAX)
        }.map { EmailKey(it.accountId, it.id) }
    }

    /**
     * What one new-mail notification pass over [mailboxId] reads: the rows it may announce, and a baseline.
     */
    suspend fun notifyRead(accountId: String, mailboxId: String): NotifyRead {
        val since = notifyCandidateFloor(System.currentTimeMillis())
        return NotifyRead(
            emails = emailDao.receivedSince(accountId, mailboxId, since, NOTIFY_CANDIDATE_MAX).map { it.toEmail() },
            baselineIds = emailDao.idsReceivedSince(accountId, mailboxId, since),
        )
    }

    /** Cached emails for account-qualified keys (drives bulk actions on a selection). Each id is
     *  resolved only inside its own account; a null accountId falls back to an unscoped lookup. */
    suspend fun cachedEmailsByIds(keys: Collection<EmailKey>): List<Email> {
        if (keys.isEmpty()) return emptyList()
        return keys.groupBy({ it.accountId }, { it.emailId }).flatMap { (accountId, ids) ->
            // Chunked ([byIdsChunked]): a select-all enters the bulk paths through this read.
            byIdsChunked(ids) { chunk ->
                if (accountId != null) emailDao.emailsByIds(accountId, chunk) else emailDao.emailsByIds(chunk)
            }
        }.map { it.toEmail() }
    }

    /**
     * What this phone holds from each sender, for one account. A one-shot read on [Dispatchers.IO]: it is a
     * full scan of `emails`, and a reactive version would replay it on every sync write.
     */
    suspend fun senderVolumes(accountId: String): List<SenderVolume> = withContext(Dispatchers.IO) {
        emailDao.senderVolumes(accountId).map {
            SenderVolume(email = it.email, name = it.name, total = it.total, unread = it.unread, latest = it.latest)
        }
    }

    /**
     * The ids [senderVolumes] counted for [email] in [accountId], the set a per-sender delete may act on,
     * produced by the same scope clause as the count itself.
     */
    suspend fun senderMessageIds(accountId: String, email: String): List<String> =
        withContext(Dispatchers.IO) { emailDao.senderMessageIds(accountId, email) }

    /**
     * Every unread message id in [mailboxId], resolved server-side (uncollapsed Email/query on `notKeyword
     */
    suspend fun unreadIds(credentials: AccountCredentials, mailboxId: String): List<String> {
        // Bounded by the same [UNREAD_RESOLVE_MAX] as the server walk below: one question, one size.
        suspend fun cached() =
            emailDao.unreadKeys(credentials.id, mailboxId, UNREAD_RESOLVE_MAX).map { it.id }
        if (credentials.protocol == MailProtocol.IMAP) return cached()
        return runCatching {
            val ctx = jmapSession(credentials)
            val ids = mutableListOf<String>()
            while (ids.size < UNREAD_RESOLVE_MAX) {
                val page = client.queryEmailIds(
                    ctx.session, ctx.accountId, mailboxId, UNREAD_RESOLVE_PAGE, ctx.auth,
                    position = ids.size, calculateTotal = true, unseenOnly = true,
                )
                if (page.ids.isEmpty()) break
                ids += page.ids
                val total = page.total
                if (total != null && ids.size >= total) break
            }
            ids
        }.getOrElse { cached() }
    }

    /** Caches the unread mail no folder sync brought in, over IMAP through [ImapUnreadBackfill]. Over
     *  JMAP it also files back the rows a folder sync left outside [scopes] ([unreadBackfillPlan]).
     *  A server failure reaches the caller, and what was written before it stays. */
    suspend fun backfillUnread(credentials: AccountCredentials, scopes: List<Pair<String, String>>) {
        if (credentials.protocol == MailProtocol.IMAP) return backfillUnreadImap(credentials, scopes)
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        // Folder ids are numbered per account: only this account's half of the scopes names its folders.
        val inScope = scopes.filter { it.first == credentials.id }.mapTo(HashSet()) { it.second }
        if (inScope.isEmpty()) return
        val outOfScope = mailboxDao.idsForAccount(credentials.id).filterNot { it in inScope }
        val ctx = jmapSession(credentials)
        var walked = 0
        while (walked < UNREAD_RESOLVE_MAX) {
            val page = client.queryUnreadEmailIds(
                ctx.session, ctx.accountId, outOfScope, UNREAD_RESOLVE_PAGE, ctx.auth,
                position = walked, calculateTotal = true,
            )
            if (page.ids.isEmpty()) break
            walked += page.ids.size
            val cachedFolders = byIdsChunked(page.ids) { chunk -> emailDao.cachedFoldersOf(credentials.id, chunk) }
                .associate { it.id to it.mailboxId }
            val plan = unreadBackfillPlan(page.ids, cachedFolders, inScope) { id ->
                isRecentlyMutated(credentials.id, id) || inFlightRemovals.containsKey(flightKey(credentials.id, id))
            }
            val wanted = plan.absent + plan.misfiled.keys
            if (wanted.isNotEmpty()) {
                val filed = client.getEmailsWithMailboxIds(ctx.session, ctx.accountId, wanted, ctx.auth).mapNotNull { email ->
                    val filedInScope = email.mailboxIds.filterValues { it }.keys.intersect(inScope)
                    rankedMailboxPick(credentials.id, filedInScope)?.let { email.toEntity(credentials.id, it) }
                }
                val (misfiled, rows) = filed.partition { it.id in plan.misfiled }
                val fresh = sparingInFlight(rows)
                val refiled = sparingInFlight(misfiled)
                checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
                val caught = caughtUp(fresh, staleBefore = System.currentTimeMillis() - NOTIFY_HORIZON_MS)
                // Insert-only, never an upsert: ignoring the conflict settles the race with a folder
                // sync writing the same id since the read above, and a row cached under a folder of
                // the scope never moves, it is listed where it is.
                emailDao.insertAllIgnoring(caught)
                // Only a row cached outside the scope is re-filed, the folder and the mark alone and from
                // the folder the plan read. It leaves that folder's cached list until that folder's next sync.
                // Silent whatever its age: it was cached already, and filed under the inbox it would pass
                // for new mail, a second banner and an unarchive-on-reply written to the server.
                refiled.forEach { emailDao.refile(credentials.id, it.id, plan.misfiled.getValue(it.id), it.mailboxId) }
            }
            val total = page.total
            if (total != null && walked >= total) break
        }
    }

    private suspend fun backfillUnreadImap(credentials: AccountCredentials, scopes: List<Pair<String, String>>) {
        val stillConfigured: suspend () -> Unit = {
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        }
        stillConfigured()
        val cache = imapUnreadCache(
            emailDao = emailDao,
            accountId = credentials.id,
            unreadCap = UNREAD_RESOLVE_MAX,
            stillConfigured = stillConfigured,
            heldLocally = { id -> isRecentlyMutated(credentials.id, id) || inFlightRemovals.containsKey(flightKey(credentials.id, id)) },
            sparingInFlight = ::sparingInFlight,
        )
        ImapUnreadBackfill(
            accountId = credentials.id,
            cache = cache,
            onFolder = { mailboxId, block -> imap.onUnseenOf(credentials, mailboxId, block) },
            budget = UNREAD_RESOLVE_MAX,
            now = System::currentTimeMillis,
        ).run(scopes)
    }

    /**
     * Instant coverage floor for the search index: re-seed the rows in the display cache, without clearing
     * crawled-only rows. The whole-mailbox crawl ([syncSearchIndex]) runs separately.
     */
    suspend fun seedIndexFromCache() = emailFtsDao.seedFromEmails(NOT_SEARCHED_ROLES)

    private val lastIndexAt = mutableMapOf<String, Long>()

    /**
     * Crawl the whole mailbox's headers into the local search index so as-you-type search covers all mail
     */
    suspend fun syncSearchIndex(
        credentials: AccountCredentials,
        force: Boolean = false,
        onPage: (suspend () -> Unit)? = null,
    ) {
        if (credentials.protocol == MailProtocol.IMAP) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastIndexAt[credentials.id] ?: 0L) < INDEX_TTL_MS) return
        val ctx = runCatching { jmapSession(credentials) }.getOrNull() ?: return
        // Don't crawl Trash/Junk into the index: same role source as searchableFolderIds, excluded
        // server-side.
        val excluded = excludedSearchFolderIds(mailboxDao.searchOrder(credentials.id))
        // Bound once for "what to ask for", "how far to skip a failed page" and "was that page
        // short?": a hardcoded 500 in all three refused every page on a server admitting less.
        val pageSize = requestPageSize(HEADER_PAGE, ctx.session.getBatchSize())
        var position = 0
        var failed = false
        var consecutiveErrors = 0
        while (position < HEADER_MAX) {
            val page = try {
                client.crawlHeaders(ctx.session, ctx.accountId, position, pageSize, ctx.auth, excluded)
            } catch (e: CancellationException) {
                throw e // search closed / VM cleared: bail without stamping so we resume next time
            } catch (e: Exception) {
                // A page error must not hide every older mail behind it: skip it and keep crawling.
                failed = true
                if (++consecutiveErrors >= MAX_CRAWL_ERRORS) break
                position += pageSize
                continue
            }
            consecutiveErrors = 0
            // Paginate on Email/query's id count, not Email/get's: a short get must not stop the walk.
            if (page.queryCount == 0) break
            if (page.emails.isNotEmpty()) {
                // Guarded like the two folder walks: an account signed out mid-crawl would keep
                // handing its subjects and senders to local search under an unowned id (#121).
                checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
                emailFtsDao.upsert(page.emails.map { it.toFts(credentials.id) })
                onPage?.invoke()
            }
            if (page.queryCount < pageSize) break
            position += page.queryCount
        }
        // Only throttle once the crawl finished cleanly; a partial run must stay retryable.
        if (!failed) lastIndexAt[credentials.id] = now
    }

    /** Local search over [syncSearchIndex]'s index, capped at [limit], by [ftsMatch] and [query]'s flags and dates, in the accounts of [scope]; its [SearchQuery.recipient] is not in the index. [NOT_SEARCHED_ROLES]
     *  only sees rows labelled with an excluded folder; `EmailDao` un-indexes the rest. */
    suspend fun searchIndex(query: SearchQuery, scope: Collection<String>, limit: Int = LOCAL_SEARCH_LIMIT): List<Email> {
        val match = ftsMatch(query)
        val accountIds = localSearchAccounts(accountStore.accounts().map { it.id }, scope) ?: return emptyList()
        val hits = if (match != null) {
            emailFtsDao.search(match, query.unread, query.flagged, query.hasAttachment, query.afterMillis, query.beforeMillis, NOT_SEARCHED_ROLES, accountIds, accountIds.size, limit)
        } else {
            emailFtsDao.searchWithoutWords(query.unread, query.flagged, query.hasAttachment, query.afterMillis, query.beforeMillis, NOT_SEARCHED_ROLES, accountIds, accountIds.size, limit)
        }
        return hits.map { it.toEmail() }
    }

    /**
     * Refresh every account's inbox into the cache. Per-account failures are skipped so one bad account
     * doesn't sink the unified view. Returns metadata for the accounts that synced.
     */
    suspend fun refreshAllInboxes(accounts: List<AccountCredentials>, limit: Int = 50): UnifiedRefreshResult {
        val results = mutableListOf<AccountInboxMeta>()
        val failures = mutableListOf<Throwable>()
        for (credentials in accounts) {
            try {
                if (credentials.protocol == MailProtocol.IMAP) {
                    // Same write-through as the single-account refresh, and sized on the account's
                    // window, not the 50 passed here.
                    val load = imapWriteThrough(credentials, mailboxId = null, limit = limit)
                    mailboxDao.replaceAllKeepingUnread(credentials.id, load.mailboxes, load.unreadStated)
                    results += AccountInboxMeta(
                        credentials.id, load.accountName, load.targetMailboxId, load.targetName, load.unread,
                    )
                    continue
                }
                val resolved = resolveJmapAccount(credentials)
                val inbox = resolved.mailboxes.firstOrNull { it.role == "inbox" }
                    ?: resolved.mailboxes.firstOrNull()
                    ?: continue
                syncMailbox(
                    resolved.session, resolved.accountId, resolved.auth, inbox.id,
                    folderSyncSizing(limit, resolved.session.getBatchSize()), credentials.id,
                )
                // Folder counters after the row sync so badge and list move together.
                mailboxDao.replaceAll(credentials.id, resolved.mailboxes.toEntities(credentials))
                val name = resolved.session.accounts[resolved.accountId]?.name ?: credentials.username
                results += AccountInboxMeta(credentials.id, name, inbox.id, inbox.name, inbox.unreadEmails)
                bgScope.launch { runCatching { prefetchInboxBodies(credentials, inbox.id) } }
            } catch (gone: AccountGoneException) {
                // Above the cancellation catch, and it must stay there. This account was signed out
                // mid-pass: not a failure (nothing goes into [failures], so no offline banner for a
                // deliberate gesture) and not a reason to abandon the accounts after it. Letting the
                // cancellation out would leave `InboxViewModel.refresh` spinning for ever.
                logInfo(
                    "MailRepository",
                    "unified refresh: ${accountGoneCause(credentials.id, accountStore.accountsUnreadable(), passKind = "walk")}, skipped",
                    gone,
                )
            } catch (c: CancellationException) {
                // A superseding refresh cancelled us: propagate, so the caller records no false success (#65).
                throw c
            } catch (t: Throwable) {
                // One account failing must not sink the unified view, nor vanish without trace (#92).
                failures += t
                logWarn("MailRepository", "unified refresh: account ${credentials.id} failed", t)
            }
        }
        return UnifiedRefreshResult(results, failures)
    }

    sealed interface DiscoveryResult {
        /** A server host responded and authenticated; store this as the account `server`. */
        data class Found(val server: String) : DiscoveryResult
        /** A server was reached but rejected the credentials (HTTP 401/403). */
        data object BadCredentials : DiscoveryResult
        /** No candidate host responded as a JMAP server. */
        data object NotFound : DiscoveryResult
    }

    /**
     * Build JMAP auth for [credentials], the token half of the session seam [jmapAccountIdFor] completes:
     * Bearer for API-token accounts (token in the password slot), OAuth refreshed within 60 s of expiry, Basic otherwise.
     */
    override suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth {
        if (credentials.authType == AuthType.API_TOKEN) return BearerAuth(credentials.password)
        val token = tokenRefresher.freshAccessToken(credentials)
            ?: return BasicAuth(credentials.username, credentials.password)
        return BearerAuth(token)
    }

    override suspend fun firstSync(credentials: AccountCredentials): MailboxMeta = refresh(credentials)

    suspend fun refresh(
        credentials: AccountCredentials,
        mailboxId: String? = null,
        limit: Int = 50,
        // Prune cached messages older than this epoch-millis cutoff; null prunes nothing. When set,
        // [limit] is also the window's retention floor, so the two must come from one SyncWindow.
        pruneBeforeMillis: Long? = null,
    ): MailboxMeta {
        // The belt behind ConnectViewModel's braces (#121): a blank credentials.id files every row
        // under an account that does not exist. Throw rather than skip, so a fourth path is caught.
        require(credentials.id.isNotBlank()) {
            "refresh() needs a real account id: caching mail under a blank one strands it (#121)."
        }
        if (credentials.protocol == MailProtocol.IMAP) return refreshImap(credentials, mailboxId, limit, pruneBeforeMillis)
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = jmapAccountIdFor(credentials, session)

        val mailboxes = client.getMailboxes(session, accountId, auth)
        cachedSession = JmapAccountSession(
            credentials = credentials,
            session = session,
            accountId = accountId,
            auth = auth,
            rolesToMailboxId = jmapRolesToMailboxId(mailboxes, credentials.specialFolders),
            mailboxes = mailboxes,
        )

        val target = mailboxId?.let { id -> mailboxes.firstOrNull { it.id == id } }
            ?: mailboxes.firstOrNull { it.role == "inbox" }
            ?: mailboxes.firstOrNull()
            ?: error("No mailboxes found.")

        // The window's two halves, computed once: the request is capped to what this server accepts
        // in one go, the retention floor is not (capping both is #110 reopened).
        val sizing = folderSyncSizing(limit, session.getBatchSize())
        // The folder rows must land even when the message sync throws: otherwise the drawer keeps
        // stale folders although the list we already fetched is good. The prune stays success-only.
        val sync = runCatching { syncMailbox(session, accountId, auth, target.id, sizing, credentials.id) }
        val syncError = sync.exceptionOrNull()
        // …but a signed-out account is not a server hiccup, and this `runCatching` would absorb its
        // refusal. Carrying on would write the whole folder LIST under an id no account owns any
        // more, after the sign-out's orphan sweep ran, #121's ghost by another door.
        if (syncError is AccountGoneException) throw syncError
        // The prune spares whatever this very sync fetched. Passing `getOrNull()` through unmapped
        // keeps the second lock in place: a null there prunes nothing rather than everything (#110).
        if (syncError == null && pruneBeforeMillis != null) {
            // `sizing.retentionFloor`, never `sizing.pageSize`: the floor is the user's window, and
            // the server's per-request limit has no business shortening it.
            pruneRetention(credentials.id, target.id, pruneBeforeMillis, sync.getOrNull()?.fetchedIds?.toSet(), sizing.retentionFloor, spareUnread = true)
        }
        // Folder counters land after the email rows, so a concurrent optimistic nudge survives.
        mailboxDao.replaceAll(credentials.id, mailboxes.toEntities(credentials))
        if (syncError != null) throw syncError
        // Warm the body cache for the top of the inbox. Fire-and-forget so it never delays the list.
        if (target.role == "inbox") {
            bgScope.launch { runCatching { prefetchInboxBodies(credentials, target.id) } }
        }

        val accountName = session.accounts[accountId]?.name ?: credentials.username
        return MailboxMeta(accountName, target.id, target.name, target.unreadEmails)
    }

    /** The folder reload [MailDrafts] owes after an APPEND, which returns no id to key a row on. */
    override suspend fun refreshMailbox(credentials: AccountCredentials, mailboxId: String) {
        refresh(credentials, mailboxId)
    }

    /** Null means "read no opening lines at all" (#187). It answers id and value: `@Upsert` replaces
     *  the whole row, so dropping the cached values here blanks the column. */
    private suspend fun listPreviewCache(accountId: String): (suspend (List<String>, Long?) -> Map<String, String>)? {
        val prefs = settings ?: return null
        val wanted = runCatching {
            previewWantedInList(prefs.previewLines.first(), prefs.notificationContent.first())
        }.getOrElseUnlessCancelled { false }
        if (!wanted) return null
        return { ids, numbering -> emailDao.cachedPreviews(accountId, ids, numbering).associate { it.id to it.preview } }
    }

    /** Many writes, at most one delete, and none when the walk cannot vouch for what it read.
     *  [limit] does not size this: the window is read off the account. `folderIds` (#165) is passed
     *  NULL, never `emptySet()`. */
    private suspend fun imapWriteThrough(
        credentials: AccountCredentials,
        mailboxId: String?,
        limit: Int,
    ): ImapFolderLoad {
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        val window = fullQueryWindowTarget(accountStore.account(credentials.id)?.syncWindow?.limit, limit)
        val writes = imapFolderWrites(credentials.id)
        return fullQueryWriteThrough<List<EmailEntity>, ImapFolderLoad>(
            folderIds = { mailboxId?.let { emailDao.idsForMailbox(credentials.id, it).toSet() } },
            walk = { onPage -> imap.loadFolder(credentials, mailboxId, window, onlySubscribed = accountStore.showOnlySubscribedFolders(credentials.id), onPage = onPage, cachedPreviewsFor = listPreviewCache(credentials.id), delta = writes.delta) },
            writePage = { page -> writes.writePage(page) },
            keepIds = { load -> reconcilableIds(load, credentials.id) },
            spareIds = { recentlyMutatedIds(credentials.id) },
            reconcile = { load, keepIds, spare, evictable ->
                emailDao.reconcileMailbox(
                    credentials.id, load.targetMailboxId, keepIds, spare,
                    evictableIds = evictable.takeIf { mailboxId != null && mailboxId == load.targetMailboxId },
                )
                writes.markKeptWalked(load.targetMailboxId, keepIds)
                imap.rememberModSeq(credentials.id, load, writes.everyRowWritten)
                flightsOffMain { reread(credentials.id, load.targetMailboxId) }
            },
        )
    }

    private suspend fun imapFolderWrites(accountId: String): ImapFolderWrites {
        return ImapFolderWrites(
            wantsPreviews = listPreviewCache(accountId) != null,
            idsForMailbox = { mailboxId -> emailDao.idsForMailbox(accountId, mailboxId) },
            idsWithPreviewForMailbox = { mailboxId -> emailDao.idsWithPreviewForMailbox(accountId, mailboxId) },
            stillConfigured = {
                checkAccountStillConfigured(accountId, accountStore.accounts().map { it.id })
            },
            heldLocally = { recentlyMutatedIds(accountId) },
            setReadAndStar = { ids, seen, flagged, answered, forwarded ->
                emailDao.setReadAndStar(accountId, ids, seen, flagged, answered, forwarded)
            },
            sparingInFlight = ::sparingInFlight,
            upsertAll = emailDao::upsertAll,
            markWalked = { mailboxId, ids -> emailDao.markWalked(accountId, mailboxId, ids) },
            marksOf = { ids -> emailDao.catchUpOf(accountId, ids) },
        )
    }

    private suspend fun refreshImap(
        credentials: AccountCredentials,
        mailboxId: String?,
        limit: Int,
        pruneBeforeMillis: Long?,
    ): MailboxMeta {
        val load = imapWriteThrough(credentials, mailboxId, limit)
        mailboxDao.replaceAllKeepingUnread(credentials.id, load.mailboxes, load.unreadStated)
        // Spares the window the read just listed, whole, whether it was walked or read as a delta
        // (#110). The same decision the reconcile took, called again rather than restated.
        if (pruneBeforeMillis != null) {
            pruneRetention(
                credentials.id,
                load.targetMailboxId,
                pruneBeforeMillis,
                reconcilableIds(load, credentials.id),
                limit,
                spareUnread = false,
            )
        }
        return MailboxMeta(load.accountName, load.targetMailboxId, load.targetName, load.unread)
    }

    /**
     * Fetch a single message (with body). Marks it read when [markRead]; the message pager passes false and
     * marks the entry read once it settles, not while it is flicked past.
     */
    suspend fun openEmail(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): Email {
        if (credentials.protocol == MailProtocol.IMAP) return openEmailImap(credentials, emailId, markRead)
        val ctx = jmapSession(credentials)
        val email = client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)
        if (markRead && !email.isSeen) {
            // Through setRead, never inline: it also nudges the drawer count, protects the id from
            // the next reconcile and advances the mailbox emailState.
            runCatching { setRead(credentials, emailId, seen = true) }
        }
        return email
    }

    /** Best effort, and called from [openMessage] so a failed read is visible where the decision to
     *  cache is taken. Not `headers` in `EMAIL_BODY_PROPERTIES`: one refused property fails the get. */
    private suspend fun originReadFor(
        credentials: AccountCredentials,
        emailId: String,
        email: Email,
    ): OriginRead {
        if (credentials.protocol == MailProtocol.IMAP) {
            return OriginRead(email.originalSender, email.authenticationFailures, succeeded = true)
        }
        return try {
            val ctx = jmapSession(credentials)
            val headers = client.getEmailHeaders(ctx.session, ctx.accountId, emailId, ctx.auth)
                .map { it.name to it.value }
            // Autocrypt rides on the read already happening: no second round trip.
            importAutocryptKey(headers, email.from.firstOrNull()?.email, { email.receivedAt })
            originReadOf(headers, authservNamesFor(credentials, ctx.session.apiUrl))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logWarn("MailRepository", "origin header of $emailId unreadable", failure)
            originReadOf(headers = null, trustedNames = emptySet())
        }
    }

    /** Fire and forget on [bgScope]: a binder call into a foreign process can hang, and call sites
     *  sit on a path someone is waiting on. [receivedAt] is a producer, so an IMAP date is fetched
     *  inside the launch; it is also the clock a key more than 24 h ahead is refused against. */
    private fun importAutocryptKey(
        headers: List<Pair<String, String>>,
        fromAddress: String?,
        receivedAt: suspend () -> String?,
    ) {
        bgScope.launch {
            runCatching {
                importAutocryptPeer(pgpEngine, headers, fromAddress, receivedAt(), System.currentTimeMillis())
            }
        }
    }

    /** The server's own `INTERNALDATE`, never `envelope.receivedAt`: on IMAP that is the message's
     *  own `Date:`, so a forged one would file an attacker's key under the impersonated peer with a
     *  date no authentic key can beat. Null, without asking the server, when there is no header. */
    private suspend fun autocryptInternalDate(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        headers: List<Pair<String, String>>,
    ): String? {
        if (headers.count { (name, _) -> name.trim().equals("Autocrypt", ignoreCase = true) } != 1) return null
        val millis = try {
            imap.fetchInternalDate(credentials, mailboxId, uid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logWarn("MailRepository", "INTERNALDATE unreadable; no Autocrypt key imported", failure)
            null
        }
        return millis?.let { java.time.Instant.ofEpochMilli(it).toString() }
    }

    /**
     * The same read for many messages, keyed by id: one grouped `Email/get`, best effort, cancellation
     */
    private suspend fun originalSendersOf(ctx: JmapAccountSession, emails: List<Email>): Map<String, OriginRead>? = try {
        val trusted = authservNamesFor(ctx.credentials, ctx.session.apiUrl)
        val byId = client.getEmailsHeaders(ctx.session, ctx.accountId, emails.map { it.id }, ctx.auth)
        for (email in emails) {
            val headers = byId[email.id] ?: continue
            importAutocryptKey(headers.map { it.name to it.value }, email.from.firstOrNull()?.email, { email.receivedAt })
        }
        byId.mapValues { (_, headers) -> originReadOf(headers.map { it.name to it.value }, trusted) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        logWarn("MailRepository", "origin headers of ${emails.size} prefetched messages unreadable", failure)
        // null, never emptyMap(): an empty map would be cached as "none of them carries one".
        null
    }

    /** The envelope comes from the cache, or from the server when there is none (#159). No row of
     *  `emails` is written here: it would reach `reconcileMailbox`, the counts and the FTS index. */
    private suspend fun openEmailImap(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): Email {
        // First, before the cache is even read: the sanction for an id that is not an IMAP message.
        // A `local-draft:` id sent down this path is the one copy of a text that exists nowhere else.
        val uid = ImapMailService.uidOf(emailId) ?: error("Not an IMAP message.")
        val cached = emailDao.emailsByIds(credentials.id, listOf(emailId)).firstOrNull()?.toEmail()
        val envelope = imapEnvelope(credentials.id, emailId, cached, { imap.fetchById(credentials, emailId) }, ::forgetGoneImapMessage)
        val mailboxId = envelope.mailboxId ?: error("Unknown mailbox for message.")
        val raw = imap.fetchSource(credentials, mailboxId, uid)
        // Before the parse, because parsing "" succeeds: it yields a body the composer paints as a
        // usable blank draft. Raising sets editingDraftLossyOnOpen, so `performSend` destroys nothing.
        if (sourceCouldNotBeRead(raw)) {
            throw MessageUnavailableException("Source of $emailId came back empty; the server did not give us the message.")
        }
        val body = MimeParser.parseBody(raw)
        if (body.tooLarge) {
            throw ContentTooLargeException(
                "Message source is ${raw.length} characters, over the ${MimeParser.MAX_BODY_CHARS} parse limit.",
                bytes = raw.length.toLong(),
                maxBytes = MimeParser.MAX_BODY_CHARS.toLong(),
            )
        }
        if (markRead && !envelope.isSeen) {
            runCatching { setRead(credentials, emailId, seen = true) }
        }
        // The cache holds the thread key but not the threading headers; lift them from the source so
        // a reply carries In-Reply-To/References. The unsubscribe headers ride along for free.
        val unsubscribe = unsubscribeHeadersOf(raw)
        val headers = MimeParser.rawHeaders(raw)
        // Autocrypt (the receiving half): the correspondent's key, off the source already in hand.
        // A key arrives only from a message whose whole source had to be fetched anyway. What keeps
        // it off a message nothing touched is `MessagePaging.needsLoad`'s `settled` argument.
        importAutocryptKey(headers, envelope.from.firstOrNull()?.email, { autocryptInternalDate(credentials, mailboxId, uid, headers) })
        return envelope.withBody(body).copy(
            messageId = headerIds(MimeParser.headerOf(raw, "Message-ID")),
            references = headerIds(MimeParser.headerOf(raw, "References")),
            listUnsubscribe = unsubscribe.first,
            listUnsubscribePost = unsubscribe.second,
            dispositionNotificationTo = readReceiptHeaderOf(raw),
            // Read through `rawHeaders` rather than `headerOf`: that one is a Map where the last
            // occurrence wins, which for a relay header names the outermost alias, not the hop.
            originalSender = originalSenderHeaderOf(headers),
            authenticationFailures = AuthenticationResults.failuresOf(headers, authservNamesFor(credentials, apiUrl = null)),
        )
    }

    /** No `emails` row to drop: [imapEnvelope] asks the server only on a cache miss. */
    private suspend fun forgetGoneImapMessage(accountId: String, emailId: String) {
        emailFtsDao.deleteById(accountId, emailId)
        emailBodyDao.deleteById(accountId, emailId)
    }

    private fun headerIds(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val bracketed = Regex("<[^<>]+>").findAll(value).map { it.value }.toList()
        return bracketed.ifEmpty { value.trim().split(Regex("\\s+")).map { "<$it>" } }
    }

    // ---- Body cache + prefetch -------------------------------------------------------------

    /**
     * Open a message for display, body cache first: a cached body renders with no network round-trip, a miss
     * fetches and persists it. Inline images are resolved before returning. Marks read when [markRead].
     */
    suspend fun openMessage(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): MessageBody {
        // A message decrypted earlier this process renders instantly (memory only, never persisted).
        decryptedCache.get(cryptoKey(credentials.id, emailId))?.let { entry ->
            // Read the row before the marking below is launched: this open reports the read state as
            // it stood on opening. Guarded, since a database refusing to answer must not error out.
            val body = reconcileCachedFlags(
                cached = entry.body,
                row = rowOrUnknown { emailDao.readAndStarOf(credentials.id, emailId) },
            )
            if (markRead) bgScope.launch { runCatching { setRead(credentials, emailId, true) } }
            return body
        }
        cachedMessage(credentials.id, emailId)?.let { cached ->
            if (markRead) bgScope.launch { runCatching { setRead(credentials, emailId, true) } }
            // A prefetched body can hold ciphertext: route it to the decrypt flow, not to armor.
            cryptoKindOf(cached.email)?.let { kind ->
                return cached.copy(crypto = MessageCrypto.Locked(kind))
            }
            return ensureInlineImages(credentials, emailId, cached)
        }
        val email = openEmail(credentials, emailId, markRead)
        cryptoKindOf(email)?.let { kind ->
            // Never persist crypto message bodies: the plaintext must not land in the Room cache.
            return MessageBody(email, emptyMap(), crypto = MessageCrypto.Locked(kind))
        }
        // Who a forwarding service says really wrote this (#160), read here and not in `openEmail`:
        // only this caller shows the line, and only it can act on a failed read, by not caching.
        val origin = originReadFor(credentials, emailId, email)
        val shown = email.copy(originalSender = origin.header, authenticationFailures = origin.authenticationFailures)
        val inline = fetchInlineImages(credentials, shown, emailId)
        // The cache write is CONDITIONAL: a body cached after a failed read would say "no origin"
        // for the life of the install, since this cache is served before any network call.
        if (cachesBodyAfterOriginRead(credentials.protocol, origin.succeeded)) {
            persistBody(credentials.id, emailId, shown, inline)
        }
        return MessageBody(shown, inline)
    }

    /**
     * The raw header fields of a message, in original order with duplicates kept (#60). Fetched on demand for
     * "view headers": JMAP uses the cheap `headers` property, IMAP parses the source.
     */
    suspend fun rawHeaders(credentials: AccountCredentials, emailId: String): List<EmailHeader> {
        if (credentials.protocol == MailProtocol.IMAP) {
            val email = cachedEmail(credentials.id, emailId) ?: openEmail(credentials, emailId, markRead = false)
            val raw = fetchRawSource(credentials, email, emailId)
            return MimeParser.rawHeaders(raw).map { (name, value) -> EmailHeader(name, value) }
        }
        val ctx = jmapSession(credentials)
        return client.getEmailHeaders(ctx.session, ctx.accountId, emailId, ctx.auth)
    }

    /** The raw RFC 5322 source, for "Save as .eml". Never the clear text of an OpenPGP message: an
     *  encrypted message is saved as received (README: "Decrypted content is never written to
     *  disk"). JMAP needs the `blobId`, which the cache has no column for, hence [openEmail]. */
    suspend fun rawSource(credentials: AccountCredentials, emailId: String, frozen: FrozenNumbering = FrozenNumbering.NothingFrozen): ByteArray {
        val email = if (credentials.protocol == MailProtocol.IMAP) {
            cachedEmail(credentials.id, emailId) ?: openEmail(credentials, emailId, markRead = false)
        } else {
            openEmail(credentials, emailId, markRead = false)
        }
        return rawSourceBytes(emailId, fetchRawSource(credentials, email, emailId, frozen))
    }

    // ---- OpenPGP read path -------------------------------------------------------------------

    /** Decrypted message bodies + their raw decrypted MIME entity, memory only, small LRU. */
    private class DecryptedEntry(val body: MessageBody, val decryptedEntity: String?)

    // Both crypto caches are keyed "$accountId:$emailId" (see [cryptoKey]): same-server accounts can
    // cache colliding email ids (#31), and a bare-id key would serve the sibling account's message.
    private val decryptedCache = MemoryLru<DecryptedEntry>(8)

    /** Raw sources of crypto messages being decrypted (avoids refetching on interaction retries). */
    private val rawSourceCache = MemoryLru<String>(4)

    /** The body a reply to [original] quotes when the reader decrypted it this process (see
     *  [quotableClear]), else null. Memory only: no network, no OpenPGP provider, nothing marked read. */
    fun decryptedOriginalToQuote(accountId: String, original: Email): Email? =
        quotableClear(accountId, original) { decryptedCache.get(it)?.body }

    /**
     * Structural check for OpenPGP content on an already-fetched [Email]. Public because the reader's cache
     */
    fun cryptoKindOf(email: Email): CryptoKind? {
        val parts = email.attachments
        if (parts.any { it.type == "application/pgp-encrypted" }) return CryptoKind.PGP_ENCRYPTED
        if (parts.any { it.type == "application/pgp-signature" }) return CryptoKind.PGP_SIGNED
        return inlineArmorKind(email.textContent().orEmpty())
    }

    private suspend fun fetchRawSource(
        credentials: AccountCredentials,
        email: Email,
        emailId: String,
        frozen: FrozenNumbering = FrozenNumbering.NothingFrozen,
    ): String {
        rawSourceFromCache(credentials.protocol, frozen) { rawSourceCache.get(cryptoKey(credentials.id, emailId)) }?.let { return it }
        val raw = if (credentials.protocol == MailProtocol.IMAP) {
            val mailboxId = emailDao.mailboxOf(credentials.id, emailId) ?: email.mailboxId
                ?: error("Unknown mailbox for message.")
            val uid = ImapMailService.uidOf(emailId) ?: error("Not an IMAP message.")
            // The stamp goes on the wire: without it `onMailbox` selects under the folder's own record,
            // which a cross-account loop realigns on a renumbering. A tick that froze nothing is refused (#189).
            val numbering = when (val verdict = numberingToOppose(frozen)) {
                NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mailboxId, null)
                is NumberingToOppose.Select -> verdict.stamp
            }
            imap.fetchSource(credentials, mailboxId, uid, numbering)
        } else {
            val blobId = email.blobId ?: error("Message has no blob id.")
            val ctx = jmapSession(credentials)
            // ISO-8859-1, like the IMAP branch: a raw source is a byte container, one char per octet.
            client.downloadBlob(ctx.session, ctx.accountId, blobId, "message/rfc822", "message.eml", ctx.auth)
                .toString(Charsets.ISO_8859_1)
        }
        rawSourceCache.put(cryptoKey(credentials.id, emailId), raw)
        return raw
    }

    /** Decrypt and/or verify an OpenPGP message via [PgpEngine], or check an S/MIME signature or open an
     *  S/MIME envelope via [SmimeEngine]; the kind read from the source picks which (see [cryptoRoute]). Nothing decrypted
     *  is persisted; [PgpResult.UserInteractionRequired] asks the caller to run the PendingIntent and retry. */
    suspend fun decryptMessage(
        credentials: AccountCredentials,
        emailId: String,
        interactionResult: android.content.Intent? = null,
        lockedKind: CryptoKind? = null,
    ): PgpResult<MessageBody> {
        if (notAvailableBeforeFetch(lockedKind, pgpEngine)) return PgpResult.NotAvailable
        decryptedCache.get(cryptoKey(credentials.id, emailId))?.let { return PgpResult.Success(it.body) }
        val email = runCatching { openEmail(credentials, emailId, markRead = false) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot fetch message") }
        val raw = runCatching { fetchRawSource(credentials, email, emailId) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot fetch message source") }
        val envelope = runCatching { MimeParser.detectCrypto(raw) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot parse message structure") }
        val sender = email.from.firstOrNull()?.email
        val route = cryptoRoute(envelope?.kind, lockedKind, pgpEngine, smimeEngine)
        val keep: (SmimeCertificateFound) -> Unit = { keepSmimeCertificate(credentials, emailId, email, it) }

        // Contain any escaping exception as an Error so the reader shows the status card (#14).
        val result = runCatching {
            when (route) {
                CryptoRoute.OpenPgpMissing -> return PgpResult.NotAvailable
                CryptoRoute.NothingFound -> return PgpResult.Error("No OpenPGP content found")
                CryptoRoute.Unverifiable -> return PgpResult.Success(unverifiedBody(credentials, emailId, email))
                is CryptoRoute.Smime -> smimeVerify(route.engine, envelope!!, sender, keep)
                is CryptoRoute.SmimeEncrypted -> smimeDecrypt(credentials, route.engine, envelope!!, sender, keep)
                is CryptoRoute.OpenPgp -> route.engine.let { pgp ->
                    when (envelope!!.kind) {
                        CryptoKind.PGP_ENCRYPTED -> pgp.decryptVerify(
                            envelope.encryptedArmor!!.toByteArray(Charsets.UTF_8),
                            senderAddress = sender,
                            interactionResult = interactionResult,
                        )
                        CryptoKind.PGP_INLINE -> pgp.decryptVerify(
                            inlineVerifyBytes(envelope),
                            senderAddress = sender,
                            interactionResult = interactionResult,
                        )
                        // ISO-8859-1, not UTF-8: only this reading hands the verifier the octets the sender hashed.
                        CryptoKind.PGP_SIGNED -> pgp.decryptVerify(
                            canonicalizeCrlf(envelope.signedEntityRaw!!).toByteArray(Charsets.ISO_8859_1),
                            senderAddress = sender,
                            detachedSignature = envelope.signatureArmor!!.toByteArray(Charsets.UTF_8),
                            interactionResult = interactionResult,
                        )
                        CryptoKind.SMIME_SIGNED, CryptoKind.SMIME_OPAQUE_SIGNED, CryptoKind.SMIME_ENCRYPTED ->
                            error("S/MIME is routed apart")
                    }
                }
            }
        }.getOrElse { return PgpResult.Error(it.message ?: it.javaClass.simpleName) }
        return when (result) {
            is PgpResult.Success -> {
                val entry = runCatching { buildDecrypted(email, envelope!!, result.value) }
                    .getOrElse { return PgpResult.Error(it.message ?: "Cannot rebuild decrypted body") }
                decryptedCache.put(cryptoKey(credentials.id, emailId), entry)
                rawSourceCache.remove(cryptoKey(credentials.id, emailId))
                PgpResult.Success(entry.body)
            }
            is PgpResult.UserInteractionRequired -> result
            is PgpResult.Error -> result
            PgpResult.NotAvailable -> PgpResult.NotAvailable
            is PgpResult.SmimeCertificate -> result
        }
    }

    private suspend fun smimeVerify(
        engine: SmimeEngine,
        envelope: CryptoEnvelope,
        sender: String?,
        found: (SmimeCertificateFound) -> Unit,
    ): PgpResult<PgpDecrypted> {
        val checked = withContext(Dispatchers.Default) { smimeVerified(engine, envelope, sender, wasEncrypted = false, found = found) }
        return PgpResult.Success(checked)
    }

    /** Fire and forget on [bgScope], like [importAutocryptKey]: the verdict is already on its way to the reader,
     *  and on IMAP the date takes a round trip. No date, no certificate: undated, it could not be outranked.
     *  The `runCatching` is the only guard: [bgScope] has no exception handler, and on Android a throw escaping
     *  it kills the app. */
    private fun keepSmimeCertificate(credentials: AccountCredentials, emailId: String, email: Email, found: SmimeCertificateFound) {
        val store = smimeCertificates ?: return
        bgScope.launch {
            runCatching {
                val date = serverDateOf(credentials, emailId, email) ?: return@runCatching
                store.retain(found.address, found.certificate, smimeCertificateRank(found, date))
            }.onFailure { logWarn("MailRepository", "S/MIME certificate of $emailId not kept", it) }
        }
    }

    /** The server's date for [email], in epoch millis: JMAP's `receivedAt`, IMAP's `INTERNALDATE`. Not the
     *  `receivedAt` of an IMAP envelope, which is the sender's own `Date:` (see [autocryptInternalDate]). */
    private suspend fun serverDateOf(credentials: AccountCredentials, emailId: String, email: Email): Long? {
        if (credentials.protocol != MailProtocol.IMAP) {
            return email.receivedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        }
        val mailboxId = emailDao.mailboxOf(credentials.id, emailId) ?: email.mailboxId ?: return null
        val uid = ImapMailService.uidOf(emailId) ?: return null
        return imap.fetchInternalDate(credentials, mailboxId, uid)
    }

    /** Open an S/MIME envelope with the account's own certificate; [openSmimeEnvelope] says what each answer means. */
    private suspend fun smimeDecrypt(
        credentials: AccountCredentials,
        engine: SmimeEngine,
        envelope: CryptoEnvelope,
        sender: String?,
        found: (SmimeCertificateFound) -> Unit,
    ): PgpResult<PgpDecrypted> {
        val alias = accountStore.account(credentials.id)?.smimeCertAlias.orEmpty()
        return withContext(Dispatchers.IO) {
            openSmimeEnvelope(
                envelopedData = envelope.envelopedDataDer ?: ByteArray(0),
                alias = alias,
                keys = smimeKeys,
                engine = engine,
                sender = sender,
                found = found,
            )
        }
    }

    /** What `openMessage` shows of ordinary mail, minus the cache write: a signed message is asked
     *  again at each open, and a failed image fetch must not turn into a status card. */
    private suspend fun unverifiedBody(credentials: AccountCredentials, emailId: String, email: Email): MessageBody {
        val origin = originReadFor(credentials, emailId, email)
        val shown = email.copy(originalSender = origin.header, authenticationFailures = origin.authenticationFailures)
        val inline = try {
            fetchInlineImages(credentials, shown, emailId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logWarn("MailRepository", "inline images of $emailId unreadable", failure)
            emptyMap()
        }
        return MessageBody(shown, inline)
    }

    private fun buildDecrypted(
        email: Email,
        envelope: CryptoEnvelope,
        decrypted: PgpDecrypted,
    ): DecryptedEntry {
        val crypto = MessageCrypto.Decrypted(
            signature = decrypted.signature,
            signatureUserId = decrypted.signatureUserId,
            signatureKeyId = decrypted.signatureKeyId,
            wasEncrypted = decrypted.wasEncrypted || envelope.kind == CryptoKind.PGP_ENCRYPTED,
            smime = envelope.kind.isSmime,
        )
        return when (envelope.kind) {
            // An envelope's plaintext is what `clearOfEnvelope` chose to show: the signed entity when it held one.
            CryptoKind.SMIME_OPAQUE_SIGNED, CryptoKind.SMIME_ENCRYPTED -> {
                val entity = decrypted.plaintext.toString(Charsets.ISO_8859_1)
                val shown = displayOfEntity(email, entity)
                DecryptedEntry(MessageBody(shown.email, shown.inlineImages, crypto), entity)
            }
            CryptoKind.PGP_ENCRYPTED -> {
                // ISO-8859-1: UTF-8 would lose the octets of any attachment inside.
                val entity = decrypted.plaintext.toString(Charsets.ISO_8859_1)
                val shown = displayOfEntity(email, entity)
                val display = shown.email.run {
                    copy(
                        // RFC 9788 protected headers (#128): this copy stays in memory, never the Room row,
                        // the index, a notification or the widget, and nothing outgoing reads its subject.
                        subject = decryptedSubject(subject, entity),
                    )
                }
                DecryptedEntry(MessageBody(display, shown.inlineImages, crypto), entity)
            }
            CryptoKind.PGP_INLINE -> {
                val text = inlineDisplayText(envelope, decrypted.plaintext)
                val display = email.copy(
                    htmlBody = emptyList(),
                    textBody = listOf(EmailBodyPart(partId = "text")),
                    bodyValues = mapOf("text" to EmailBodyValue(value = text)),
                )
                DecryptedEntry(MessageBody(display, emptyMap(), crypto), null)
            }
            CryptoKind.PGP_SIGNED, CryptoKind.SMIME_SIGNED -> {
                val entity = envelope.signedEntityRaw!!
                val shown = displayOfEntity(email, entity)
                DecryptedEntry(MessageBody(shown.email, shown.inlineImages, crypto), entity)
            }
        }
    }

    private fun pgpAttachmentBytes(accountId: String, emailId: String, part: EmailBodyPart): ByteArray {
        val section = part.partId?.removePrefix("pgp:")
            ?: error("Not a decrypted attachment part.")
        val entity = decryptedCache.get(cryptoKey(accountId, emailId))?.decryptedEntity
            ?: error("Message is no longer decrypted — reopen it first.")
        val (cte, encoded) = MimeParser.partAt(entity, section)
            ?: error("Attachment not found in the decrypted message.")
        return MimeParser.decodeBytes(encoded, cte)
    }

    /** [accountId]'s cached body for [emailId], or null if not yet fetched. No network. The read is
     *  inside the guard, not just the decoding: a row past SQLite's cursor window throws on every read. */
    suspend fun cachedMessage(accountId: String, emailId: String): MessageBody? {
        val cached = readCachedOrPurge(
            read = {
                val row = emailBodyDao.byId(accountId, emailId) ?: return@readCachedOrPurge null
                MessageBody(
                    email = decodeCachedEmail(row.bodyJson),
                    inlineImages = cacheJson.decodeFromString(inlineImagesSerializer, row.inlineImagesJson),
                )
            },
            purge = { emailBodyDao.deleteById(accountId, emailId) },
        ) ?: return null
        return reconcileCachedFlags(
            cached = cached,
            row = rowOrUnknown { emailDao.readAndStarOf(accountId, emailId) },
        )
    }

    /** Drop every cached message body of every account; called once after an upgrade. */
    suspend fun clearCachedBodies() {
        emailBodyDao.deleteAll()
    }

    private suspend fun ensureInlineImages(
        credentials: AccountCredentials,
        emailId: String,
        cached: MessageBody,
    ): MessageBody {
        if (cached.email.inlineImageParts().isEmpty() || cached.inlineImages.isNotEmpty()) return cached
        val inline = fetchInlineImages(credentials, cached.email, emailId)
        if (inline.isNotEmpty()) persistBody(credentials.id, emailId, cached.email, inline)
        return cached.copy(inlineImages = inline)
    }

    /**
     * Inline images as `data:` URIs by Content-ID; one past the size ceiling is skipped (see
     * [oversizedInlineImages]).
     */
    private suspend fun fetchInlineImages(
        credentials: AccountCredentials,
        email: Email,
        emailId: String,
    ): Map<String, String> {
        val parts = email.inlineImageParts()
        if (parts.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (part in parts) {
            val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() } ?: continue
            runCatching {
                val bytes = downloadAttachment(
                    credentials, part, emailId, DownloadLimits.INLINE_IMAGE_MAX_BYTES,
                )
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                map[cid] = "data:${part.type ?: "image/jpeg"};base64,$base64"
            }
        }
        return map
    }

    /** Persist a fetched body, then LRU-prune the account. A row bigger than SQLite's cursor window
     *  can be written but never read back, so an oversized body is not cached at all. */
    private suspend fun persistBody(
        accountId: String,
        emailId: String,
        email: Email,
        inlineImages: Map<String, String>,
    ) {
        runCatching {
            val bodyJson = cacheJson.encodeToString(Email.serializer(), email)
            val inlineJson = cacheJson.encodeToString(inlineImagesSerializer, inlineImages)
            if (!fitsBodyCache(bodyJson, inlineJson)) return
            emailBodyDao.upsert(
                EmailBodyEntity(
                    id = emailId,
                    accountId = accountId,
                    bodyJson = bodyJson,
                    inlineImagesJson = inlineJson,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            emailBodyDao.pruneForAccount(accountId, BODY_CACHE_CAP)
        }
    }

    /** Prefetch the newest [PREFETCH_COUNT] inbox bodies. JMAP only; best-effort, never throws. */
    private suspend fun prefetchInboxBodies(credentials: AccountCredentials, mailboxId: String) {
        if (credentials.protocol == MailProtocol.IMAP) return
        runCatching {
            // Bounded in the statement, not with a `take`: `runCatching` does not catch an OutOfMemoryError.
            val newest = emailDao.newestIds(credentials.id, mailboxId, PREFETCH_COUNT)
            if (newest.isEmpty()) return
            val already = emailBodyDao.cachedIds(credentials.id, newest).toSet()
            val missing = newest.filter { it !in already }
            if (missing.isEmpty()) return
            val ctx = jmapSession(credentials)
            val emails = client.getEmailsWithBody(ctx.session, ctx.accountId, missing, ctx.auth)
            // The origin header (#160) is posed here too, not only in `openEmail`. `?: return`, a window
            // whose origin read failed is not persisted, or those bodies would say "no origin" for good.
            val origins = originalSendersOf(ctx, emails) ?: return
            for (email in emails) {
                val origin = origins[email.id]
                val withOrigin = email.copy(originalSender = origin?.header, authenticationFailures = origin?.authenticationFailures)
                persistBody(credentials.id, email.id, withOrigin, emptyMap())
            }
        }
    }

    suspend fun setRead(credentials: AccountCredentials, emailId: String, seen: Boolean) {
        markRecentlyMutated(credentials.id, emailId)
        val mailboxId = emailDao.mailboxOf(credentials.id, emailId)
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Seen", seen) }
            val flipped = emailDao.setSeen(credentials.id, emailId, seen)
            if (flipped > 0) adjustFolderUnreadOnRead(credentials.id, mailboxId, seen)
            return
        }
        val ctx = jmapSession(credentials)
        val newState = try {
            client.setSeen(ctx.session, ctx.accountId, emailId, seen, ctx.auth)
        } catch (e: JmapException) {
            // notFound = destroyed server-side: prune the zombie row; any other failure keeps it untouched.
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return
        }
        val changed = emailDao.setSeen(credentials.id, emailId, seen)
        if (changed > 0) adjustFolderUnreadOnRead(credentials.id, mailboxId, seen)
        advanceEmailState(newState, credentials.id, mailboxId)
    }

    /** Mark many messages read/unread. JMAP: one `Email/set` per server-sized chunk, applied only to
     *  ids the server confirmed. IMAP flags are per-message, so that branch stays on [setRead]. */
    suspend fun setReadAll(credentials: AccountCredentials, emailIds: List<String>, seen: Boolean) {
        if (emailIds.isEmpty()) return
        if (credentials.protocol == MailProtocol.IMAP) {
            emailIds.forEach { runCatching { setRead(credentials, it, seen) } }
            return
        }
        val ctx = jmapSession(credentials)
        // The chunk is the server's own limit, so [JmapClient.move] never has to re-split it.
        emailIds.chunked(ctx.session.setBatchSize()).forEach { chunk ->
            // Read for each row's folder only: its seen state may be stale by the answer. Chunked again
            // on purpose: the network limit above must not push this SELECT past SQLite's 999 bindings.
            val rows = byIdsChunked(chunk) { slice -> emailDao.emailsByIds(credentials.id, slice) }
                .associateBy { it.id }
            val result = client.setSeenAll(ctx.session, ctx.accountId, chunk, seen, ctx.auth)
            val gone = chunk.filter { result.failed[it] == SET_ERROR_NOT_FOUND }
            if (gone.isNotEmpty()) pruneServerGone(credentials.id, gone)
            val done = chunk.filter { it in result.done }
            val flippedIds = done.filter { id ->
                markRecentlyMutated(credentials.id, id)
                emailDao.setSeen(credentials.id, id, seen) > 0
            }
            flippedIds.mapNotNull { rows[it] }
                .groupBy { it.mailboxId }
                .forEach { (mailboxId, group) ->
                    val delta = if (seen) -group.size else group.size
                    accountStore.adjustInboxUnread(credentials.id, mailboxId, delta)
                    mailboxDao.adjustCounts(credentials.id, mailboxId, totalDelta = 0, unreadDelta = delta)
                }
            advanceEmailState(result.newState, credentials.id, *done.mapNotNull { rows[it]?.mailboxId }.toTypedArray())
        }
    }

    /** `setRead` calls this only once a conditional `EmailDao.setSeen` changed the row (`setReadAll` keeps the
     *  same rule inline), so two racing marks count once (#46); `delete`'s mark-read-on-delete calls it unguarded. */
    private suspend fun adjustFolderUnreadOnRead(accountId: String, mailboxId: String?, seen: Boolean) {
        if (mailboxId == null) return
        val delta = if (seen) -1 else 1
        accountStore.adjustInboxUnread(accountId, mailboxId, delta)
        mailboxDao.adjustCounts(accountId, mailboxId, totalDelta = 0, unreadDelta = delta)
    }

    /** Read off the stored account, for a caller that holds an id and no credentials. */
    private fun isImapAccount(accountId: String): Boolean =
        accountStore.account(accountId)?.protocol == MailProtocol.IMAP

    /** Can a drawer folder row of [accountId] carry an unread count at all? Both protocols
     *  can, IMAP included. Kept as the one place to say a protocol has no per-row count: the
     *  drawer folds a folder on the strength of the badge it would show (#185). */
    fun folderRowsBadgeUnread(accountId: String): Boolean = true

    suspend fun setFlagged(credentials: AccountCredentials, emailId: String, flagged: Boolean) {
        markRecentlyMutated(credentials.id, emailId)
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Flagged", flagged) }
            emailDao.setFlagged(credentials.id, emailId, flagged)
            return
        }
        val ctx = jmapSession(credentials)
        val mb = emailDao.mailboxOf(credentials.id, emailId)
        val newState = try {
            client.setKeyword(ctx.session, ctx.accountId, emailId, "\$flagged", flagged, ctx.auth)
        } catch (e: JmapException) {
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return
        }
        emailDao.setFlagged(credentials.id, emailId, flagged)
        advanceEmailState(newState, credentials.id, mb)
    }

    suspend fun setLabel(credentials: AccountCredentials, emailId: String, id: String, set: Boolean) {
        check(credentials.protocol == MailProtocol.JMAP) { "Labels are a JMAP keyword" }
        markRecentlyMutated(credentials.id, emailId)
        val ctx = jmapSession(credentials)
        val mb = emailDao.mailboxOf(credentials.id, emailId)
        val newState = try {
            client.setKeyword(ctx.session, ctx.accountId, emailId, EmailLabels.PREFIX + id, set, ctx.auth)
        } catch (e: JmapException) {
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return
        }
        // After the ack only: the reader's take-back reads this row, and a label written ahead of a refusal would stay held here and not on the server.
        emailDao.labelsJsonOf(credentials.id, emailId)?.let { raw ->
            emailDao.setLabels(credentials.id, emailId, EmailLabels.encode(EmailLabels.with(EmailLabels.decode(raw), id, set)))
        }
        advanceEmailState(newState, credentials.id, mb)
    }

    suspend fun cachedLabel(accountId: String, emailId: String, id: String): Boolean? =
        emailDao.labelsJsonOf(accountId, emailId)?.let { id in EmailLabels.decode(it) }

    fun observeAccountLabels(accountId: String): Flow<List<String>> =
        emailDao.labelsOfAccount(accountId).map { EmailLabels.union(it) }

    private fun imapTarget(emailId: String): Pair<String, Long>? = ImapMailService.targetOf(emailId)

    // ---- rows held in flight (the swipe that drops its line at the finger) --------------

    /** A row [beginRemoval] took out of the cache before its server write went out, named by the
     *  pair that identifies it. The row itself stays here: two accounts of one server share
     *  message ids (#31), and nothing outside may hold the last copy of a message. */
    data class InFlightRemoval(val accountId: String, val emailId: String)

    // The held rows, whole, because the cache was their only copy: `restoreAll` and `undoDelete`
    // put a row back by re-fetching it, and the write a swipe has to survive is the one that had
    // no network to begin with.
    private val inFlightRemovals = java.util.concurrent.ConcurrentHashMap<String, EmailEntity>()

    private fun flightKey(accountId: String, emailId: String) = "$accountId:$emailId"

    /** Take [emailId] out of the list now, holding its row, so a swipe drops its line when the
     *  finger leaves and not when the server answers, seconds later on a phone. The index row is
     *  spared: the mover's own [EmailDao.deleteById] takes it on the ack, and nothing could
     *  rebuild it for a put-back. Null when nothing was cached, and then nothing was evicted. */
    suspend fun beginRemoval(accountId: String, emailId: String): InFlightRemoval? {
        // Before this flight's own marker exists, or it would be read back as an orphan.
        flightsOffMain { settle() }
        val row = emailDao.emailsByIds(accountId, listOf(emailId)).firstOrNull() ?: return null
        var evictionSent = false
        var decounted = false
        // Marked before the eviction, never after: in between, a sync page would write it back.
        inFlightRemovals[flightKey(accountId, emailId)] = row
        try {
            // On disk before the eviction too: killed past it, the process leaves the marker as the only
            // record that this row is missing while the server still files the message.
            flightsOffMain { begin(FlightMarker(accountId, emailId, row.mailboxId)) }
            // Set before the call: a write cancelled on its way back may have landed, and rewriting
            // the held row is harmless where crediting a folder never decounted is not.
            evictionSent = true
            emailDao.evictFromCacheKeepingIndex(accountId, listOf(emailId))
            // Not cancellable, so that `decounted` is exact: the nudge is two writes, and a cut
            // between them would leave no way to credit back only the half that happened.
            withContext(NonCancellable) { adjustCountsForRemoval(listOf(row), destMailboxId = null) }
            decounted = true
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) {
            // All or nothing: a caller this call never returned to holds no flight it could end.
            withContext(NonCancellable) {
                runCatching { closeFlight(accountId, emailId, rewrite = row.takeIf { evictionSent }, credit = row.takeIf { decounted }) }
                    .exceptionOrNull()?.let { if (it !== e) e.addSuppressed(it) }
            }
            // A caller launched on `viewModelScope` has no handler, so a rethrow here would take the
            // process down over a full disk. Refusing the flight says the same thing safely: the row
            // stays in the list and the write goes out behind it.
            if (e is FlightMarkersRefused) return null
            throw e
        }
        return InFlightRemoval(accountId, emailId)
    }

    /** End the flight [beginRemoval] opened. [restore] puts the held row back where it was, with
     *  no network at all, and credits its folder again. A row the server declared gone is no
     *  longer held ([pruneServerGone]) and stays away whatever the caller asks. */
    suspend fun endRemoval(flight: InFlightRemoval, restore: Boolean) {
        val held = inFlightRemovals[flightKey(flight.accountId, flight.emailId)]?.takeIf { restore }
        closeFlight(flight.accountId, flight.emailId, rewrite = held, credit = held)
    }

    /** The one put-back of a flight: [rewrite] back into the cache, [credit]'s folder counted
     *  again, the marker erased, and the key lifted even when one of those throws. */
    private suspend fun closeFlight(accountId: String, emailId: String, rewrite: EmailEntity?, credit: EmailEntity?) =
        closeFlightAll(accountId, listOf(emailId), listOfNotNull(rewrite), listOfNotNull(credit))

    /** Put back the count nudge [beginRemoval] made. Not [nudgeCounts]: that one moves rows from
     *  one folder to another, and these rows moved nowhere, they never left the folder they are in.
     *  Grouped by folder: a batch put-back of forty rows is two writes per folder, not per row. */
    private suspend fun creditFoldersBack(rows: List<EmailEntity>) {
        rows.groupBy { it.accountId to it.mailboxId }.forEach { (where, group) ->
            val (accountId, mailboxId) = where
            val unread = group.count { !it.seen }
            accountStore.adjustInboxUnread(accountId, mailboxId, unread)
            mailboxDao.adjustCounts(accountId, mailboxId, totalDelta = group.size, unreadDelta = unread)
        }
    }

    /** The rows one bulk action took out of the list at the tap, named by the account that owns
     *  them: two accounts of one server share message ids (#31), so a ticket is never mixed.
     *  [emailIds] is what was really held, which can be less than what the caller asked for. */
    data class InFlightBatch(val accountId: String, val emailIds: List<String>)

    /** [beginRemoval] for a whole selection: its lines leave at the tap, not at the server's answer.
     *  Not a loop over [beginRemoval]: the marker store commits to disk synchronously, so a loop
     *  would stall that tap on one commit per message. Null when nothing was cached, and then
     *  nothing was evicted and there is no ticket to close. */
    suspend fun beginRemovalAll(accountId: String, emailIds: Collection<String>): InFlightBatch? {
        if (emailIds.isEmpty()) return null
        // Before this batch's own markers exist, or they would be read back as orphans.
        flightsOffMain { settle() }
        val asked = emailIds.distinct()
        val cached = byIdsChunked(asked) { chunk -> emailDao.emailsByIds(accountId, chunk) }.associateBy { it.id }
        if (cached.isEmpty()) return null
        // The ticket's order is the caller's, never the database's: `emailsByIds` has no ORDER BY,
        // so a ticket built from what it hands back would come out in an order no caller asked for.
        val ids = asked.filter { it in cached }
        val rows = ids.map { cached.getValue(it) }
        var evictionSent = false
        var decounted = false
        // Marked before the eviction, never after: in between, a sync page would write them back.
        rows.forEach { inFlightRemovals[flightKey(accountId, it.id)] = it }
        try {
            // On disk before the eviction too: killed past it, the process leaves the markers as the
            // only record that these rows are missing while the server still files the messages.
            flightsOffMain { beginAll(rows.map { FlightMarker(accountId, it.id, it.mailboxId) }) }
            // Set before the first call: a write cancelled on its way back may have landed, and
            // rewriting a held row is harmless where crediting a folder never decounted is not.
            evictionSent = true
            ids.chunked(MAX_CHANGES).forEach { emailDao.evictFromCacheKeepingIndex(accountId, it) }
            // Not cancellable, so that `decounted` is exact: the nudge is two writes per folder, and
            // a cut between them would leave no way to credit back only the half that happened.
            withContext(NonCancellable) { adjustCountsForRemoval(rows, destMailboxId = null) }
            decounted = true
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) {
            // All or nothing: a caller this call never returned to holds no ticket it could close.
            withContext(NonCancellable) {
                runCatching {
                    closeFlightAll(
                        accountId,
                        ids,
                        rewrite = if (evictionSent) rows else emptyList(),
                        credit = if (decounted) rows else emptyList(),
                    )
                }.exceptionOrNull()?.let { if (it !== e) e.addSuppressed(it) }
            }
            if (e is FlightMarkersRefused) return null
            throw e
        }
        return InFlightBatch(accountId, ids)
    }

    /** End the batch [beginRemovalAll] opened. [restore] names the ids to put back where they were,
     *  with no network at all; every other id of the ticket is let go. A row the server declared
     *  gone is no longer held ([pruneServerGone]) and stays away whatever the caller asks. */
    suspend fun endRemovalAll(batch: InFlightBatch, restore: Collection<String>) {
        val wanted = restore.toSet()
        val held = batch.emailIds
            .filter { it in wanted }
            .mapNotNull { inFlightRemovals[flightKey(batch.accountId, it)] }
        closeFlightAll(batch.accountId, batch.emailIds, rewrite = held, credit = held)
    }

    /** The one put-back of a batch: [rewrite] back into the cache, [credit]'s folders counted again,
     *  the markers erased in one store write, and every key of [emailIds] lifted even when one of
     *  those throws. */
    private suspend fun closeFlightAll(
        accountId: String,
        emailIds: List<String>,
        rewrite: List<EmailEntity>,
        credit: List<EmailEntity>,
    ) {
        try {
            rewrite.chunked(MAX_CHANGES).forEach { putBack -> emailDao.upsertAll(putBack) }
            creditFoldersBack(credit)
            // After the put-back: erased before it, a kill in between loses the rows again.
            flightsOffMain { endAll(accountId, emailIds) }
        } finally {
            // Lifted last: while a mark stands, no sync page can write that row back under us.
            // Lifted whatever failed above: a key left standing hides the message from every page
            // until the process dies.
            emailIds.forEach { inFlightRemovals.remove(flightKey(accountId, it)) }
        }
    }

    /** The rows a ticket is holding, of the ids it was asked for. The cache no longer answers for
     *  any of them: this list is the whole of what a mover, or the opt-in mark-read, can read. */
    private fun heldRows(accountId: String, emailIds: Collection<String>): List<EmailEntity> =
        emailIds.mapNotNull { inFlightRemovals[flightKey(accountId, it)] }

    /** Give a ticket's row back the decount [beginRemovalAll] took when the action turns out to
     *  move it nowhere: nothing else credits a folder a message never left, and its badge would sit
     *  short until a whole re-read. The flight ends here, so a ticket closed afterwards cannot
     *  credit twice; the row stays out of the cache, where the calling branch has just put it. */
    private suspend fun creditWhatNeverLeft(accountId: String, emailIds: Collection<String>) {
        val held = heldRows(accountId, emailIds)
        if (held.isEmpty()) return
        closeFlightAll(accountId, held.map { it.id }, rewrite = emptyList(), credit = held)
    }

    /** Take the way back away from [emailIds]: they are gone server-side. Their index rows go with
     *  them, since the ack that would have taken those ([EmailDao.deleteById]) is being skipped. */
    private suspend fun forfeitInFlight(accountId: String, emailIds: Collection<String>) {
        val held = emailIds.filter { inFlightRemovals.remove(flightKey(accountId, it)) != null }
        if (held.isNotEmpty()) deleteFromCacheAndIndex(accountId, held)
        if (held.isNotEmpty()) flightsOffMain { endAll(accountId, held) }
    }

    /** The row a mover works from. [evicted] says it comes from a flight, so its source folder was
     *  decounted at the finger and the ack owes the destination only. */
    private class MovingRow(val entity: EmailEntity?, val evicted: Boolean)

    private suspend fun movingRow(accountId: String, emailId: String): MovingRow =
        inFlightRemovals[flightKey(accountId, emailId)]?.let { MovingRow(it, evicted = true) }
            ?: MovingRow(emailDao.emailsByIds(accountId, listOf(emailId)).firstOrNull(), evicted = false)

    /** The rows a bulk mover works from. [held] come from a batch ticket, whose source folders were
     *  decounted at the tap; [cached] are the rest, read from the cache, which answers nothing for
     *  an id a ticket is holding. */
    private class MovingRows(val held: List<EmailEntity>, val cached: List<EmailEntity>) {
        val all: List<EmailEntity> = held + cached
    }

    private suspend fun movingRows(accountId: String, emailIds: List<String>): MovingRows {
        val held = heldRows(accountId, emailIds)
        val rest = if (held.isEmpty()) emailIds else emailIds - held.mapTo(mutableSetOf()) { it.id }
        return MovingRows(held, byIdsChunked(rest) { chunk -> emailDao.emailsByIds(accountId, chunk) })
    }

    /** What the opt-in mark-read-on-the-way-out reads before it fires: the held row when there is
     *  one, or the cache stops answering and the option quietly stops working on a swipe. */
    private suspend fun movingSeen(accountId: String, emailId: String): Boolean? =
        movingRow(accountId, emailId).entity?.seen

    /** Record on the held row what the opt-in mark-read just did, so the destination's badge and a
     *  put-back both see the state the server now holds (#67). */
    private fun flightMarkedSeen(accountId: String, emailId: String) {
        inFlightRemovals.computeIfPresent(flightKey(accountId, emailId)) { _, row -> row.copy(seen = true) }
    }

    private suspend fun markWalked(accountId: String, mailboxId: String, page: List<Email>) =
        page.map { it.id }.chunked(MAX_CHANGES).forEach { emailDao.markWalked(accountId, mailboxId, it) }

    /** No page read from the server may write back a row a swipe is holding: the server still
     *  holds the message until the move lands, so the page names it, and writing it would put the
     *  line back on screen a second after the finger took it off. */
    private fun sparingInFlight(rows: List<EmailEntity>): List<EmailEntity> =
        if (inFlightRemovals.isEmpty()) rows
        else rows.filterNot { inFlightRemovals.containsKey(flightKey(it.accountId, it.id)) }

    /** Archive a message. Resolution order, a real Archive folder, else All Mail (Gmail-style
     *  accounts have none), else create an Archive folder as a last resort. */
    suspend fun archive(credentials: AccountCredentials, emailId: String): String? {
        // Leaving a folder withdraws the message from any pending Empty-trash order (#99).
        unlistFromTrashPurge(credentials.id, listOf(emailId))
        // Opt-in: flag the message read on its way out. Best-effort before the move (the id changes
        // with an IMAP move) and before [row] is read, so the count nudge sees the new state (#67).
        if (settings?.markReadOnArchive?.first() == true && movingSeen(credentials.id, emailId) == false) {
            runCatching { setRead(credentials, emailId, true) }
                .onSuccess { flightMarkedSeen(credentials.id, emailId) }
        }
        val moving = movingRow(credentials.id, emailId)
        val row = moving.entity
        if (credentials.protocol == MailProtocol.IMAP) {
            val dest = imapRoleFolder(credentials, "archive", "all")
                ?: lastResortImapFolder("archive", "Archive", credentials.specialFolders)?.also { imap.createFolder(credentials, it) }
                ?: error("No archive folder, and the name Archive is designated for another role")
            var noop = false
            imapTarget(emailId)?.let { (mb, uid) ->
                if (mb == dest) { noop = true; return@let } // already in the archive/all folder
                // The numbering [mb] was last seen under, opposed to what the server states in the SELECT this
                // move goes out under: a renumbered folder refuses instead of archiving another message (#99).
                val landed = imap.move(credentials, mb, uid, dest, recordedUidValidity(credentials, mb))
                landed.uids[uid]?.let {
                    lastImapMove[emailId] = ImapLoc(dest, it, landed.destinationUidValidity)
                    recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, dest, it))
                }
            }
            if (noop) {
                evictAlreadyThere(credentials.id, dest, listOf(emailId))
                return null
            }
            emailDao.deleteById(credentials.id, emailId)
            adjustCountsForRemoval(listOfNotNull(row), dest, sourceAlreadyLeft = moving.evicted)
            return dest
        }
        val ctx = jmapSession(credentials)
        val mb = row?.mailboxId ?: emailDao.mailboxOf(credentials.id, emailId)
        val target = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: createArchiveFolder(ctx)
        if (mb == target) return null // already in the archive/all folder, nothing to do
        // The local row is dropped on the ack, unless a swipe already took it out at the finger
        // and the repository is holding it ([beginRemoval]): nothing else hides a message before
        // the server has answered for it.
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, target, ctx.auth)
        } catch (e: JmapException) {
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return null
        }
        recentLocalMoves.mark(credentials.id, emailId)
        emailDao.deleteById(credentials.id, emailId)
        adjustCountsForRemoval(listOfNotNull(row), target, sourceAlreadyLeft = moving.evicted)
        advanceEmailState(newState, credentials.id, mb)
        return target
    }

    /** Whether deleting [email] would permanently destroy it: does it already sit in the Trash? An account
     *  with no Trash is not a destroy, [delete] resolves-or-creates the bin, so `true` erases it (#23). */
    suspend fun deleteWouldDestroy(credentials: AccountCredentials, email: Email): Boolean {
        val trash = if (credentials.protocol == MailProtocol.IMAP) imapRoleFolder(credentials, "trash")
        else trashMailboxId(jmapSession(credentials))
        return deleteDestroysMessage(trash, email.mailboxId)
    }

    /** Whether this account has a Trash at all, [deleteWouldDestroy]'s half that does not depend on a
     *  message's own folder, for a caller whose row carries a `mailboxId` it cannot believe. */
    suspend fun accountHasTrash(credentials: AccountCredentials): Boolean =
        roleMailboxId(credentials, "trash") != null

    /** Whether the folder cache names a Trash for [accountId]: "no Trash" for an account never synced. */
    suspend fun hasCachedTrash(accountId: String): Boolean =
        mailboxDao.idForRole(accountId, "trash") != null

    /** The folder the cache gives the `inbox` role for this account, without a round trip. */
    suspend fun cachedInboxMailboxId(credentials: AccountCredentials): String? =
        mailboxDao.idForRole(credentials.id, "inbox")

    /** The bin off the folder cache, so no finger waits on a socket. By name on JMAP alone: the IMAP
     * cache leaves that name on the loser of the role claim, a folder a delete moves out of. Every
     * folder the names recognise comes back, root first, and the first one the account has not
     * designated for another role wins, or a delete would file into her Archive (#227). */
    suspend fun cachedTrashMailboxId(credentials: AccountCredentials): String? {
        val cached = mailboxDao.idForRole(credentials.id, "trash")
        if (cached != null) return cached
        if (credentials.protocol == MailProtocol.IMAP) return null
        val pinnedElsewhere = pinnedToAnotherRole("trash", credentials.specialFolders)
        return mailboxDao.idsForAnyName(credentials.id, TRASH_FOLDER_NAMES).firstOrNull { it !in pinnedElsewhere }
    }

    /** The IMAP numbering last observed for [mailboxId], to freeze at a user's confirmation and oppose
     *  much later. Null on JMAP and on a folder never observed: nothing to oppose destroys nothing (#99).
     *  Read at the confirmation and never again: re-read at execution the guard always passes. */
    override suspend fun recordedUidValidity(credentials: AccountCredentials, mailboxId: String): Long? =
        if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, mailboxId) else null

    /** The IMAP numbering each of [emailIds] was read under, off the cached rows (#99). The rows, not
     *  the folder: a background pass that met the renumbering has already realigned the folder's record
     *  while the rows still carry the old UIDs. An absent id reads as null; empty on JMAP. Chunked. */
    suspend fun numberingRowsWereReadUnder(
        credentials: AccountCredentials,
        emailIds: List<String>,
    ): Map<String, Long?> {
        return numberingStampsOfRows(credentials, emailIds) { chunk -> emailDao.numberingOfRows(credentials.id, chunk) }
    }

    /** Move a message to an arbitrary mailbox. Returns the destination it ended up in (null = a
     *  no-op, already there), so the caller can offer an Undo that also reverses the count nudge. */
    suspend fun moveToMailbox(credentials: AccountCredentials, emailId: String, targetMailboxId: String): String? {
        // Rescue path of #99: filing a message elsewhere during the undo window takes it off the list.
        unlistFromTrashPurge(credentials.id, listOf(emailId))
        markReadOnMoveOutOfInbox(
            credentials, listOf(emailId), targetMailboxId,
            alsoInHand = listOfNotNull(movingRow(credentials.id, emailId).entity),
        )
        // Captured before the local row is dropped, to nudge the drawer's cached counts (INV-count),
        // and read after the opt-in above, so the nudge sees the state the server now holds (#67).
        val moving = movingRow(credentials.id, emailId)
        val moved = moving.entity
        if (moved != null && moved.mailboxId == targetMailboxId) return null
        // Report-spam / not-spam reach this path without a flight and never restore the row on
        // failure, so for them a failed move must leave the cache exactly as it found it.
        if (credentials.protocol == MailProtocol.IMAP) {
            val already = imapTarget(emailId)?.let { (mb, uid) ->
                if (mb == targetMailboxId) return@let true
                // Same guard as [archive] and [delete]: the source folder's recorded numbering (#99).
                val landed = imap.move(credentials, mb, uid, targetMailboxId, recordedUidValidity(credentials, mb))
                landed.uids[uid]?.let {
                    lastImapMove[emailId] = ImapLoc(targetMailboxId, it, landed.destinationUidValidity)
                    recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, targetMailboxId, it))
                }
                false
            } ?: false
            if (already) {
                evictAlreadyThere(credentials.id, targetMailboxId, listOf(emailId))
                return null
            }
            emailDao.deleteById(credentials.id, emailId)
            adjustCountsForRemoval(listOfNotNull(moved), targetMailboxId, sourceAlreadyLeft = moving.evicted)
            return targetMailboxId
        }
        val ctx = jmapSession(credentials)
        val mb = moved?.mailboxId ?: emailDao.mailboxOf(credentials.id, emailId)
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, targetMailboxId, ctx.auth)
        } catch (e: JmapException) {
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return null
        }
        recentLocalMoves.mark(credentials.id, emailId)
        emailDao.deleteById(credentials.id, emailId)
        adjustCountsForRemoval(listOfNotNull(moved), targetMailboxId, sourceAlreadyLeft = moving.evicted)
        advanceEmailState(newState, credentials.id, mb)
        return targetMailboxId
    }

    /** Nudge the drawer's cached counters when [rows] leave for [destMailboxId] (null when destroyed).
     *  Each row is decremented from its own mailboxId (INV-source); the next sync reconciles. */
    private suspend fun adjustCountsForRemoval(
        rows: List<EmailEntity>,
        destMailboxId: String?,
        sourceAlreadyLeft: Boolean = false,
    ) =
        rows.groupBy { it.accountId }.forEach { (accountId, group) ->
            nudgeCounts(accountId, group.map { it.mailboxId to it.seen }, destMailboxId, sourceAlreadyLeft)
        }

    /** [adjustCountsForRemoval] for a bulk path reading through a ticket: [ids] is what the server
     *  confirmed. The held rows are nudged apart, under [nudgeCounts]'s `sourceAlreadyLeft`, since
     *  their source folder lost them at the tap and a second decount takes its badge down twice.
     *  Partitioned rather than decided for the batch: one batch can hold both kinds. */
    private suspend fun adjustCountsForRemoval(moving: MovingRows, ids: Set<String>, destMailboxId: String?) {
        adjustCountsForRemoval(moving.cached.filter { it.id in ids }, destMailboxId)
        adjustCountsForRemoval(moving.held.filter { it.id in ids }, destMailboxId, sourceAlreadyLeft = true)
    }

    /** The count-nudge primitive: [sources] is (sourceMailboxId, seen) per row; an Undo reuses it in
     *  reverse. Scoped to [accountId], or a nudge from the unified inbox moves a sibling's badge.
     *  [sourceAlreadyLeft] is the swipe that evicted its row at the finger ([beginRemoval]): the
     *  source was decounted there, and decounting it again takes the message off its folder twice. */
    private suspend fun nudgeCounts(
        accountId: String,
        sources: List<Pair<String, Boolean>>,
        destMailboxId: String?,
        sourceAlreadyLeft: Boolean = false,
    ) {
        if (!sourceAlreadyLeft) {
            sources.groupBy { it.first }.forEach { (src, group) ->
                if (src == destMailboxId) return@forEach
                val unread = group.count { !it.second }
                accountStore.adjustInboxUnread(accountId, src, -unread)
                mailboxDao.adjustCounts(accountId, src, totalDelta = -group.size, unreadDelta = -unread)
            }
        }
        if (destMailboxId != null) {
            val incoming = sources.filter { it.first != destMailboxId }
            if (incoming.isNotEmpty()) {
                val unread = incoming.count { !it.second }
                accountStore.adjustInboxUnread(accountId, destMailboxId, unread)
                mailboxDao.adjustCounts(accountId, destMailboxId, totalDelta = incoming.size, unreadDelta = unread)
            }
        }
    }

    // ---- bulk (batched) actions (Codeberg #29) ----------------------------------------

    /** Which ids of a batch went through, and which failed. [dest] is the folder the succeeded ids
     *  were moved to (null = destroyed), so an undoable bulk op can reverse the count nudge. */
    class BulkResult(val succeeded: Set<String>, val failed: Set<String>, val dest: String? = null) {
        companion object { val EMPTY = BulkResult(emptySet(), emptySet()) }
    }

    /**
     * Cache+index removal chunked to [MAX_CHANGES]: `email_fts` is FTS4 with `emailId` `notindexed`, so one
     * un-index is a full scan.
     */
    private suspend fun deleteFromCacheAndIndex(accountId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.chunked(MAX_CHANGES).forEach { emailDao.deleteByIds(accountId, it) }
    }

    /** Local cleanup of an action that moved nothing. The cached row goes; the index row goes only in
     *  Trash/Junk/Spam, where a spared row is an orphan nothing brings back ([noOpEvictionFor]). */
    private suspend fun evictAlreadyThere(accountId: String, mailboxId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        when (noOpEvictionFor(mailboxId, mailboxDao.roleForId(accountId, mailboxId))) {
            NoOpEviction.SPARE_INDEX_ROW ->
                ids.chunked(MAX_CHANGES).forEach { emailDao.evictFromCacheKeepingIndex(accountId, it) }
            NoOpEviction.TAKE_INDEX_ROW -> deleteFromCacheAndIndex(accountId, ids)
        }
    }

    /** Batch-move one IMAP source folder's [ids] to [dest] with one `UID MOVE`, recording each new UID
     *  and [dest]'s numbering in [lastImapMove] so Undo can oppose it. Every id the move did not prove
     *  left [source] is failed: an `OK` says the command ran, not that any message moved.
     *  [expectedUidValidity] is [source]'s numbering as the caller froze it, with no default (#99). */
    private suspend fun imapMoveGroup(
        credentials: AccountCredentials, source: String, ids: List<String>, dest: String,
        succeeded: MutableSet<String>, failed: MutableSet<String>,
        expectedUidValidity: Long?,
    ) {
        val uidToId = ids.mapNotNull { id -> ImapMailService.uidOf(id)?.let { it to id } }.toMap()
        failed += ids.filter { ImapMailService.uidOf(it) == null }
        if (uidToId.isEmpty()) return
        // The rows a batch ticket holds, plus the cache for the rest, read before the local rows are
        // dropped, to nudge the drawer counts (INV-count).
        val moving = movingRows(credentials.id, uidToId.values.toList())
        runCatching { imap.moveBatch(credentials, source, uidToId.keys.toList(), dest, expectedUidValidity) }
            .onSuccess { landed ->
                uidToId.forEach { (uid, id) ->
                    landed.uids[uid]?.let {
                        lastImapMove[id] = ImapLoc(dest, it, landed.destinationUidValidity)
                        recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, dest, it))
                    }
                }
                // The verdict first, and nothing is dropped before it: a UID MOVE on a uid already gone is a
                // no-op the server accepts. What is unproved is unknown, and unknown fails.
                val outcome = imapMoveOutcome(uidToId, landed.confirmedGone)
                succeeded += outcome.gone
                failed += outcome.unproven
                deleteFromCacheAndIndex(credentials.id, outcome.gone)
                adjustCountsForRemoval(moving, outcome.gone.toSet(), dest)
            }
            .onFailure { failed += uidToId.values }
    }

    /** Batch-destroy one IMAP folder's [ids] with `UID STORE`+`UID EXPUNGE`. A failed command throws
     *  rather than marking the ids failed: the worker must retry a confirmed destroy, not abandon it. */
    override suspend fun imapDestroyGroup(
        credentials: AccountCredentials, source: String, ids: List<String>,
        succeeded: MutableSet<String>, failed: MutableSet<String>,
        expectedUidValidity: Long?,
    ) {
        val uidToId = ids.mapNotNull { id -> ImapMailService.uidOf(id)?.let { it to id } }.toMap()
        failed += ids.filter { ImapMailService.uidOf(it) == null }
        if (uidToId.isEmpty()) return
        val moving = movingRows(credentials.id, uidToId.values.toList())
        imap.deleteBatch(credentials, source, uidToId.keys.toList(), expectedUidValidity)
        deleteFromCacheAndIndex(credentials.id, uidToId.values)
        succeeded += uidToId.values
        adjustCountsForRemoval(moving, uidToId.values.toSet(), destMailboxId = null)
    }

    /** JMAP: move every id to [target], then drop the local rows. Only ids the server confirmed are
     *  dropped; anything else keeps its row and lands in [BulkResult.failed]. */
    private suspend fun jmapMoveAll(ctx: JmapAccountSession, emailIds: List<String>, target: String): BulkResult {
        val localAccountId = ctx.credentials.id
        val moving = movingRows(localAccountId, emailIds)
        return runCatching { client.move(ctx.session, ctx.accountId, emailIds, target, ctx.auth) }
            .map { result ->
                val moved = emailIds.filter { it in result.done }.toSet()
                moved.forEach { recentLocalMoves.mark(localAccountId, it) }
                val alreadyThere = idsAlreadyIn(moving.all, moved, target)
                evictAlreadyThere(localAccountId, target, alreadyThere)
                deleteFromCacheAndIndex(localAccountId, moved - alreadyThere.toSet())
                adjustCountsForRemoval(moving, moved, target)
                creditWhatNeverLeft(localAccountId, alreadyThere)
                // Prune the notFound rows but keep them in `failed`: they must not feed a move-Undo.
                pruneServerGone(localAccountId, emailIds.filter { result.failed[it] == SET_ERROR_NOT_FOUND })
                BulkResult(moved, emailIds.toSet() - moved, dest = target)
            }
            .getOrElse { BulkResult(emptySet(), emailIds.toSet()) }
    }

    /** JMAP: destroy every id, then drop the confirmed rows. A failed request throws rather than marking
     *  ids failed; the worker replays the list and an already-destroyed id returns `notFound`. */
    private suspend fun jmapDestroyAll(ctx: JmapAccountSession, emailIds: List<String>): BulkResult {
        val localAccountId = ctx.credentials.id
        val moving = movingRows(localAccountId, emailIds)
        val result = client.destroy(ctx.session, ctx.accountId, emailIds, ctx.auth)
        val destroyed = emailIds.filter { it in result.done }.toSet()
        deleteFromCacheAndIndex(localAccountId, destroyed)
        adjustCountsForRemoval(moving, destroyed, destMailboxId = null)
        // Already destroyed: the requested end state holds, so prune the row and report success.
        val gone = emailIds.filter { result.failed[it] == SET_ERROR_NOT_FOUND }.toSet()
        pruneServerGone(localAccountId, gone.toList())
        return BulkResult(destroyed + gone, emailIds.toSet() - destroyed - gone)
    }

    /** Opt-in mark-read-on-archive/delete for a bulk action. Must run before the mover reads its rows
     *  for the count nudge, or the unread badge is decremented twice. */
    private suspend fun markSelectionRead(
        credentials: AccountCredentials,
        emailIds: List<String>,
        alsoInHand: List<EmailEntity> = emptyList(),
    ) {
        // Account-scoped read (#31): same-server sub-accounts can hold colliding email ids.
        val cached = byIdsChunked(emailIds) { chunk -> emailDao.emailsByIds(credentials.id, chunk) }
        // [alsoInHand] is the row a swipe is holding: the cache no longer answers for it, and this
        // read is the whole of what decides whether the option fires.
        val unread = (cached + alsoInHand).distinctBy { it.id }.filter { !it.seen }.map { it.id }
        if (unread.isEmpty()) return
        runCatching { setReadAll(credentials, unread, true) }
            .onSuccess { unread.forEach { flightMarkedSeen(credentials.id, it) } }
    }

    /** Opt-in mark-read-on-move (#67), scoped to messages leaving the Inbox: a move between two other
     *  folders is left alone, and so is a move into it. Called before the movers' count-nudge read. */
    private suspend fun markReadOnMoveOutOfInbox(
        credentials: AccountCredentials,
        emailIds: List<String>,
        targetMailboxId: String,
        alsoInHand: List<EmailEntity> = emptyList(),
    ) {
        if (settings?.markReadOnMove?.first() != true) return
        val inbox = runCatching { roleMailboxId(credentials, "inbox") }.getOrNull() ?: return
        if (targetMailboxId == inbox) return
        val leaving = if (credentials.protocol == MailProtocol.IMAP) {
            emailIds.filter { ImapMailService.mailboxOf(it) == inbox }
        } else {
            // Account-scoped (issue #31): resolve source folders within this account only.
            (byIdsChunked(emailIds) { chunk -> emailDao.emailsByIds(credentials.id, chunk) } + alsoInHand)
                .distinctBy { it.id }.filter { it.mailboxId == inbox }.map { it.id }
        }
        if (leaving.isNotEmpty()) markSelectionRead(credentials, leaving, alsoInHand)
    }

    /** Archive a whole selection (one account). [expectedUidValidity] is each source folder's numbering
     *  as the caller froze it; empty means "nothing frozen", which refuses (#99). */
    suspend fun archiveAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedUidValidity: Map<String, Long?> = emptyMap(),
    ): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        unlistFromTrashPurge(credentials.id, emailIds) // leaving a folder cancels a pending purge order (#99)
        // Opt-in mark-read-on-archive: best-effort before the move (a \Seen store keeps the UID).
        if (settings?.markReadOnArchive?.first() == true) {
            markSelectionRead(credentials, emailIds, heldRows(credentials.id, emailIds))
        }
        if (credentials.protocol == MailProtocol.IMAP) {
            val dest = imapRoleFolder(credentials, "archive", "all")
                ?: lastResortImapFolder("archive", "Archive", credentials.specialFolders)?.also { imap.createFolder(credentials, it) }
                ?: error("No archive folder, and the name Archive is designated for another role")
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null -> failed += ids
                    // Already in the archive: nothing moved, and [evictAlreadyThere] decides the index row.
                    source == dest -> {
                        evictAlreadyThere(credentials.id, dest, ids)
                        creditWhatNeverLeft(credentials.id, ids)
                        succeeded += ids
                    }
                    else -> imapMoveGroup(credentials, source, ids, dest, succeeded, failed, expectedUidValidity[source])
                }
            }
            return BulkResult(succeeded, failed, dest = dest)
        }
        val ctx = jmapSession(credentials)
        val target = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: createArchiveFolder(ctx)
        return jmapMoveAll(ctx, emailIds, target)
    }

    /** #50 (opt-out): when a genuinely-new reply lands in the Inbox, pull the thread's archived members
     *  back. JMAP only, server-first. Guarded three times against a sign-out landing mid-pass (#121);
     *  none of the three closes the race, they narrow it. */
    suspend fun unarchiveThreadsOnReply(credentials: AccountCredentials, threadIds: Set<String>): List<Email> {
        if (threadIds.isEmpty() || credentials.protocol == MailProtocol.IMAP) return emptyList()
        // Below the early return (which writes nothing anywhere), above the session (#121).
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        val ctx = jmapSession(credentials)
        val inbox = ctx.rolesToMailboxId["inbox"] ?: return emptyList()
        val archive = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: return emptyList()
        if (archive == inbox) return emptyList()
        val members = emailDao.threadMembersInMailbox(credentials.id, archive, threadIds.toList())
        if (members.isEmpty()) return emptyList()
        // Uniform rule (#99): whatever moves leaves the destroy list, exceptionless.
        unlistFromTrashPurge(credentials.id, members.map { it.id })
        // Protect the rows before the server call, so a sync firing mid-move can't evict them.
        members.forEach { markRecentlyMutated(credentials.id, it.id) }
        // The widest window of the three: a sign-out landing inside it un-archives the thread on the
        // account with no Undo. Outside the runCatching, so its refusal is not read as a failed move.
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        val result = runCatching {
            client.move(ctx.session, ctx.accountId, members.map { it.id }, inbox, ctx.auth)
        }.getOrNull() ?: return emptyList()
        val moved = members.filter { it.id in result.done }
        if (moved.isEmpty()) return emptyList()
        // Self-moves too: a concurrent pass on another watched folder must not see them as fresh.
        moved.forEach { recentLocalMoves.mark(credentials.id, it.id) }
        val refiled = moved.map { it.copy(mailboxId = inbox) }
        // Ask again: only a check here stands between a sign-out and rows written under an id no
        // account owns. Outside the `runCatching` above on purpose.
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        emailDao.upsertAll(refiled)
        adjustCountsForRemoval(moved, inbox)
        return refiled.map { it.toEmail() }
    }

    /** Move a whole selection (one account) to [targetMailboxId]. [expectedUidValidity]: see
     *  [archiveAll], the source folders' frozen numbering, by folder. */
    suspend fun moveAllToMailbox(
        credentials: AccountCredentials,
        emailIds: List<String>,
        targetMailboxId: String,
        expectedUidValidity: Map<String, Long?> = emptyMap(),
    ): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        unlistFromTrashPurge(credentials.id, emailIds) // bulk rescue out of the Trash (#99)
        markReadOnMoveOutOfInbox(credentials, emailIds, targetMailboxId, heldRows(credentials.id, emailIds))
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null -> failed += ids
                    // Already in the destination: nothing moved; [evictAlreadyThere] decides the index row.
                    source == targetMailboxId -> {
                        evictAlreadyThere(credentials.id, targetMailboxId, ids)
                        creditWhatNeverLeft(credentials.id, ids)
                        succeeded += ids
                    }
                    else -> imapMoveGroup(credentials, source, ids, targetMailboxId, succeeded, failed, expectedUidValidity[source])
                }
            }
            return BulkResult(succeeded, failed, dest = targetMailboxId)
        }
        return jmapMoveAll(jmapSession(credentials), emailIds, targetMailboxId)
    }

    /** Delete a whole selection (one account): move everything to Trash. Like [delete] it never destroys
     *  inline (the caller routes that subset through [destroyAll], #23) and it resolves or creates the bin. */
    suspend fun deleteAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedUidValidity: Map<String, Long?> = emptyMap(),
    ): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        unlistFromTrashPurge(credentials.id, emailIds) // see [archiveAll] (#99)
        // Opt-in mark-read-on-delete: best-effort before the move (a \Seen store keeps the UID).
        if (settings?.markReadOnDelete?.first() == true) {
            markSelectionRead(credentials, emailIds, heldRows(credentials.id, emailIds))
        }
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            // Resolve or create, as [delete] does. Offline it throws and the batch goes down, creating nothing.
            val trash = imapRoleFolder(credentials, "trash")
                ?: lastResortImapFolder("trash", "Trash", credentials.specialFolders)?.also { imap.createFolder(credentials, it) }
                ?: error("No trash folder, and the name Trash is designated for another role")
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null -> failed += ids
                    // Already in Trash: drop the row locally, in neither set, and un-indexed (an orphan otherwise).
                    source == trash -> {
                        deleteFromCacheAndIndex(credentials.id, ids)
                        creditWhatNeverLeft(credentials.id, ids)
                    }
                    else -> imapMoveGroup(credentials, source, ids, trash, succeeded, failed, expectedUidValidity[source])
                }
            }
            return BulkResult(succeeded, failed, dest = trash)
        }
        val ctx = jmapSession(credentials)
        // Role, then name, then a creation; the id is written back into the cached session, so one bin per batch.
        val trash = trashMailboxId(ctx) ?: createTrashFolder(ctx)
        return jmapMoveAll(ctx, emailIds, trash)
    }

    /** Permanently destroy a whole selection, the held-back bulk destroy (#23/#29). Throws on a
     *  transport failure so the worker retries; [BulkResult.failed] carries per-id rejections and the
     *  ids this refused. [expectedMailboxId] is the folder the ids were in when the user confirmed,
     *  verified on JMAP (#122); IMAP uses [expectedUidValidity]. Unverifiable destroys nothing. */
    override suspend fun destroyAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedMailboxId: String?,
        expectedUidValidity: Long?,
    ): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            // The net under the numbering the caller froze: with nothing to oppose, an expunge would go out
            // against whatever the folder is numbered now (#99). Refused ids go to `failed`, never done.
            val plan = UidValidity.imapDestroyPlan(emailIds, expectedUidValidity)
            failed += plan.refused
            plan.byFolder.forEach { (source, ids) ->
                imapDestroyGroup(credentials, source, ids, succeeded, failed, expectedUidValidity)
            }
            return BulkResult(succeeded, failed)
        }
        val ctx = jmapSession(credentials)
        val stillThere = idsStillOnlyIn(ctx, emailIds, expectedMailboxId)
        val spared = emailIds.toSet() - stillThere.toSet()
        // One line for the whole wave: a server that never reports `mailboxIds` spares everything for
        // ever, in silence, and that is indistinguishable from a guard doing its job.
        if (spared.isNotEmpty()) {
            logInfo(
                "MailSync",
                "destroy spared ${credentials.id}/$expectedMailboxId: ${spared.size} of " +
                    "${emailIds.size} not in that folder alone: ${spared.joinToString()}",
                null,
            )
        }
        if (stillThere.isEmpty()) return BulkResult(emptySet(), spared)
        val result = jmapDestroyAll(ctx, stillThere)
        return BulkResult(result.succeeded, result.failed + spared)
    }

    /** The ids of [emailIds] the server reports in [expectedMailboxId] and nowhere else, the only
     *  ones a destroy may touch (#122). Deliberately unguarded: a failed location read throws and the
     *  worker retries; falling back on [emailIds] would bring the data loss back whole. */
    private suspend fun idsStillOnlyIn(
        ctx: JmapAccountSession,
        emailIds: List<String>,
        expectedMailboxId: String?,
    ): List<String> {
        val located = client.mailboxIdsOf(ctx.session, ctx.accountId, emailIds, ctx.auth)
        return TrashPurge.destroyableIds(emailIds, expectedMailboxId, located)
    }

    /** Report a whole selection as spam (move to Junk) in one batch. [expectedUidValidity] is not
     *  optional in practice: an empty map refuses the whole batch on IMAP (#99). */
    suspend fun reportSpamAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedUidValidity: Map<String, Long?> = emptyMap(),
    ): BulkResult {
        val junk = roleMailboxId(credentials, "junk") ?: return BulkResult(emptySet(), emailIds.toSet())
        return moveAllToMailbox(credentials, emailIds, junk, expectedUidValidity)
    }

    /** Move a whole selection out of Junk back to the Inbox. [expectedUidValidity]: see [reportSpamAll]. */
    suspend fun notSpamAll(
        credentials: AccountCredentials,
        emailIds: List<String>,
        expectedUidValidity: Map<String, Long?> = emptyMap(),
    ): BulkResult {
        val inbox = roleMailboxId(credentials, "inbox") ?: return BulkResult(emptySet(), emailIds.toSet())
        return moveAllToMailbox(credentials, emailIds, inbox, expectedUidValidity)
    }

    /** The cached role of an account's mailbox (e.g. "junk", "inbox"), or null. [accountId] null
     *  falls back to the current account, same-server accounts can share a bare mailbox id. */
    suspend fun mailboxRole(accountId: String?, mailboxId: String?): String? {
        if (mailboxId == null) return null
        val account = accountId ?: accountStore.currentId() ?: return null
        return mailboxDao.roleForId(account, mailboxId)
    }

    /** Move a message to the Junk folder (Report spam). */
    suspend fun reportSpam(credentials: AccountCredentials, emailId: String) {
        val junk = roleMailboxId(credentials, "junk") ?: error("This account has no Junk folder.")
        moveToMailbox(credentials, emailId, junk)
    }

    /** Move a message out of Junk back to the Inbox (Not spam). */
    suspend fun notSpam(credentials: AccountCredentials, emailId: String) {
        val inbox = roleMailboxId(credentials, "inbox") ?: error("This account has no Inbox.")
        moveToMailbox(credentials, emailId, inbox)
    }

    /** A role's mailbox id for a specific account. On JMAP it comes from that account's own connection
     *  session, not the folder cache, which holds nothing for an account never listed. */
    private suspend fun roleMailboxId(credentials: AccountCredentials, role: String): String? =
        if (credentials.protocol == MailProtocol.IMAP) imapRoleFolder(credentials, role)
        else jmapSession(credentials).rolesToMailboxId[role]

    /** The first matching [roles] in a specific IMAP account, by listing it (see [roleMailboxId]). */
    private suspend fun imapRoleFolder(credentials: AccountCredentials, vararg roles: String): String? {
        val folders = imap.listMailboxes(credentials)
        for (role in roles) folders.firstOrNull { it.role == role }?.let { return it.id }
        return null
    }

    // ---- snooze ----

    /** Snooze a message until [until] (hidden from lists; re-appears at that time). */
    suspend fun snooze(emailId: String, accountId: String, until: Long) =
        snoozedDao.upsert(SnoozedEntity(emailId, accountId, until))

    /** Un-snooze one account's message now (re-appears in its list). */
    suspend fun unsnooze(accountId: String, emailId: String) = snoozedDao.delete(accountId, emailId)

    /** The deadline of the active snooze on [accountId]'s [emailId], or null when not snoozed (a
     *  lapsed row counts as not snoozed, same predicate as the list SQL). Keyed per account (#31). */
    suspend fun snoozedUntil(accountId: String, emailId: String): Long? =
        snoozedDao.byId(accountId, emailId)?.until?.takeIf { it > System.currentTimeMillis() }

    /** Live list of snoozed messages with their cached headers, for the "Snoozed" screen. */
    fun snoozedFlow(): Flow<List<SnoozedListRow>> = snoozedDao.observeAll()

    /** The ids an active snooze currently hides, account-qualified, as a live reading: read once into a
     *  snapshot, the unfolded conversation's members could not follow a snooze ending. */
    fun observeActiveSnoozed(): Flow<Set<EmailKey>> =
        snoozedDao.observeAll()
            .map { rows ->
                val now = System.currentTimeMillis()
                rows.filter { it.until > now }.mapTo(mutableSetOf()) { EmailKey(it.accountId, it.emailId) }
            }
            // The underlying query joins `emails`, so it re-emits far more often than this answer changes.
            .distinctUntilChanged()

    /** A single cached email of one account by id (e.g. to notify when a snooze fires). */
    suspend fun cachedEmail(accountId: String, emailId: String): Email? =
        emailDao.emailsByIds(accountId, listOf(emailId)).firstOrNull()?.toEmail()

    /** The account's archive folder id: by JMAP `archive` role, else by a recognised name, cached into
     *  the cached session's role map so a bulk archive resolves it once. */
    private suspend fun archiveMailboxId(ctx: JmapAccountSession): String? {
        ctx.rolesToMailboxId["archive"]?.let { return it }
        // THIS account's own folders by name, not the global mailbox cache, otherwise archiving a
        // non-current account's message would target the current account's Archive id, a silent no-op.
        val byName = archiveFolderByName(ctx.mailboxes.notPinnedToAnotherRole("archive", ctx.credentials.specialFolders)) ?: return null
        cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to byName), ctx.mailboxes)
        return byName
    }

    private suspend fun createArchiveFolder(ctx: JmapAccountSession): String {
        // Many servers reject a client-set special-use role: fall back to a plain folder, then an existing one.
        val id = runCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Archive", "archive", ctx.auth)
        }.recoverCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Archive", null, ctx.auth)
        }.getOrElse { err ->
            val mbs = runCatching { client.getMailboxes(ctx.session, ctx.accountId, ctx.auth) }.getOrDefault(emptyList())
            serverRoleHolderNotPinnedElsewhere(mbs, "archive", ctx.credentials.specialFolders)
                ?: archiveFolderByName(mbs.notPinnedToAnotherRole("archive", ctx.credentials.specialFolders))
                ?: throw err
        }
        cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to id), ctx.mailboxes)
        return id
    }

    /** The account's Trash folder id: by JMAP `trash` role, else by a recognised bin name. JMAP only.
     *  No `all` fallback: "all mail" is not an acceptable bin. */
    private suspend fun trashMailboxId(ctx: JmapAccountSession): String? {
        ctx.rolesToMailboxId["trash"]?.let { return it }
        // THIS account's own folders, not the global mailbox cache, see [archiveMailboxId].
        val byName = trashFolderByName(ctx.mailboxes.notPinnedToAnotherRole("trash", ctx.credentials.specialFolders)) ?: return null
        cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("trash" to byName), ctx.mailboxes)
        return byName
    }

    private suspend fun createTrashFolder(ctx: JmapAccountSession): String {
        // Many servers reject a client-set special-use role: plain folder, then an existing bin; else throw.
        val id = runCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Trash", "trash", ctx.auth)
        }.recoverCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Trash", null, ctx.auth)
        }.getOrElse { err ->
            val mbs = runCatching { client.getMailboxes(ctx.session, ctx.accountId, ctx.auth) }.getOrDefault(emptyList())
            serverRoleHolderNotPinnedElsewhere(mbs, "trash", ctx.credentials.specialFolders)
                ?: trashFolderByName(mbs.notPinnedToAnotherRole("trash", ctx.credentials.specialFolders))
                ?: throw err
        }
        cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("trash" to id), ctx.mailboxes)
        return id
    }

    /** One message to move back on an Undo: [emailId] returns to [sourceMailboxId] from
     *  [destMailboxId] (null when it was a destroy), which lets the undo reverse the count nudge. */
    data class RestoreTarget(val emailId: String, val sourceMailboxId: String, val destMailboxId: String?)

    /** What an Undo brought back, by the id each [RestoreTarget] carried. [unrestored] did not come
     *  home. An IMAP message that did answers to another id, the move back gave it a new uid:
     *  [renumbered] names that id for the rows re-cached under it, and [unplaced] came home with no
     *  row re-cached, so nothing can say which id they answer to. JMAP ids are stable: both empty. */
    data class Restored(
        val unrestored: Set<String>,
        val renumbered: Map<String, String> = emptyMap(),
        val unplaced: Set<String> = emptySet(),
    )

    /** Undo a batched move/delete for one account, grouped so each (current → source) route is one
     *  command (#29). An optimistic restore that sticks: restored ids are marked recently-mutated and
     *  the count nudge is reversed here, so the caller must not fire a reconciling refresh. */
    suspend fun restoreAll(credentials: AccountCredentials, targets: List<RestoreTarget>): Restored {
        if (targets.isEmpty()) return Restored(emptySet())
        // Undoing a delete pulls the message out of the Trash: it must leave the destroy list too (#99).
        unlistFromTrashPurge(credentials.id, targets.map { it.emailId })
        // Protect the restored ids from the next reconcile before any server call.
        targets.forEach { markRecentlyMutated(credentials.id, it.emailId) }
        val restored = mutableSetOf<String>()
        if (credentials.protocol == MailProtocol.IMAP) {
            val seenAfterRestore = mutableMapOf<String, Boolean>()
            val renumbered = mutableMapOf<String, String>()
            // Group by (current folder, source folder, numbering that current folder stated on landing).
            val routes = UidValidity.imapUndoRoutes(targets.map { it.emailId to it.sourceMailboxId }) { lastImapMove[it] }
            routes.forEach { route ->
                // The numbering to oppose is the one the forward move landed in, opposed from the freeze.
                // The two folders are named in the call: swapping the `String`s sends inbox mail to the Trash.
                val moved = runCatching {
                    imap.moveBatch(credentials, route.currentFolder, route.uids, route.sourceFolder, route.frozenUidValidity)
                }.getOrNull() ?: return@forEach
                // Credit only the uids the COPYUID mapping confirms moved back; the rest feed the failure toast.
                restored += route.messages.filter { it.first in moved.uids }.map { it.second }
                // The destination uids of the move back, i.e. where the mail is now: `.values`,
                // never `.keys`, which are the Trash-side uids the mail has just left.
                val newUids = moved.uids.values.toList()
                if (newUids.isNotEmpty()) {
                    runCatching { imap.fetchByUids(credentials, route.sourceFolder, newUids) }
                        .getOrDefault(emptyList())
                        .takeIf { it.isNotEmpty() }
                        ?.let { fetched ->
                            emailDao.upsertAll(fetched)
                            seenAfterRestore += seenOfRestoredImapRows(route.messages, moved, fetched)
                            renumbered += idsOfRestoredImapRows(route.messages, moved, fetched)
                            fetched.forEach { markRecentlyMutated(credentials.id, it.id); recentLocalMoves.mark(credentials.id, it.id) }
                        }
                }
            }
            restored.forEach { lastImapMove.remove(it) }
            restoreCounts(credentials.id, targets.filter { it.emailId in restored }, seenAfterRestore)
            return Restored(
                unrestored = targets.map { it.emailId }.toSet() - restored,
                renumbered = renumbered,
                unplaced = restored - renumbered.keys,
            )
        }
        // JMAP: ids are stable across moves, so one Email/set per source folder, then a re-fetch.
        val ctx = jmapSession(credentials)
        targets.groupBy { it.sourceMailboxId }.forEach { (source, group) ->
            val result = runCatching {
                client.move(ctx.session, ctx.accountId, group.map { it.emailId }, source, ctx.auth)
            }.getOrNull() ?: return@forEach
            val ids = group.map { it.emailId }.filter { it in result.done }
            if (ids.isEmpty()) return@forEach
            restored += ids
            // A self-move into the source folder: the next notifier pass must not announce them as new.
            ids.forEach { recentLocalMoves.mark(credentials.id, it) }
            val fetched = runCatching { client.getEmailsByIds(ctx.session, ctx.accountId, ids, ctx.auth) }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) {
                emailDao.upsertAll(fetched.map { it.toEntity(ctx.credentials.id, source) })
                fetched.forEach { markRecentlyMutated(credentials.id, it.id) }
            }
        }
        restoreCounts(credentials.id, targets.filter { it.emailId in restored })
        return Restored(targets.map { it.emailId }.toSet() - restored)
    }

    /** Reverse the forward removal's count nudge; a row with no re-cached seen state counts as read.
     *  [refetchedSeen] is what an IMAP undo read back, keyed by target id: the cache holds no row
     *  under an id the move back replaced, so reading it there counts every message as read. */
    private suspend fun restoreCounts(
        accountId: String,
        targets: List<RestoreTarget>,
        refetchedSeen: Map<String, Boolean>? = null,
    ) {
        val seenById = refetchedSeen
            ?: byIdsChunked(targets.map { it.emailId }) { chunk -> emailDao.emailsByIds(accountId, chunk) }
                .associate { it.id to it.seen }
        val moves = targets.mapNotNull { t ->
            val dest = t.destMailboxId ?: return@mapNotNull null // a destroy can't be undone
            (dest to (seenById[t.emailId] ?: true))
                .takeIf { dest != t.sourceMailboxId }
                ?.let { t.sourceMailboxId to it }
        }
        moves.groupBy({ it.first }, { it.second }).forEach { (source, rows) ->
            nudgeCounts(accountId, rows, destMailboxId = source)
        }
    }

    /** Move to Trash and drop from the local list. A delete here never destroys: permanent deletion is
     *  only reachable through [evictAll] + [destroyAll] and its Undo window (#23). The bin is resolved
     *  by role, then by name, then created, never All Mail. [frozen] (see [FrozenNumbering]):
     *  NothingFrozen falls back on the folder's record, `Frozen(null)` is refused (#189). */
    suspend fun delete(
        credentials: AccountCredentials,
        emailId: String,
        frozen: FrozenNumbering = FrozenNumbering.NothingFrozen,
    ): String? {
        // Same rule as every other mover (#99): an individual gesture leaves the destroy list.
        unlistFromTrashPurge(credentials.id, listOf(emailId))
        val markRead = settings?.markReadOnDelete?.first() == true && movingSeen(credentials.id, emailId) == false
        // IMAP flags first, best-effort: its move takes the UID away. JMAP flags in the move itself,
        // or a move that fails after the flag leaves the message read in the folder it never left.
        val seenByMove = markRead && credentials.protocol != MailProtocol.IMAP
        if (markRead && !seenByMove) {
            runCatching { setRead(credentials, emailId, true) }
                .onSuccess { flightMarkedSeen(credentials.id, emailId) }
        }
        if (seenByMove) markRecentlyMutated(credentials.id, emailId)
        val moving = movingRow(credentials.id, emailId)
        val row = if (seenByMove) moving.entity?.copy(seen = true) else moving.entity
        if (credentials.protocol == MailProtocol.IMAP) {
            val trash = imapRoleFolder(credentials, "trash")
                ?: lastResortImapFolder("trash", "Trash", credentials.specialFolders)?.also { imap.createFolder(credentials, it) }
                ?: error("No trash folder, and the name Trash is designated for another role")
            imapTarget(emailId)?.let { (mb, uid) ->
                // Already in the Trash: nothing moves, but the cached row and its index row go ([deleteAll]).
                if (mb == trash) return@let
                // "delete" is a MOVE, so the folder's recorded numbering is opposed to the SELECT (#99).
                // `Frozen(null)` is refused (#189); `NothingFrozen` is Select(null) and takes the `?:` below,
                // which has to stay.
                val numbering = when (val verdict = numberingToOppose(frozen)) {
                    NumberingToOppose.Refuse -> throw ImapNumberingUnconfirmed(mb, null)
                    is NumberingToOppose.Select -> verdict.stamp
                }
                val landed = imap.move(credentials, mb, uid, trash, numbering ?: recordedUidValidity(credentials, mb))
                landed.uids[uid]?.let {
                    lastImapMove[emailId] = ImapLoc(trash, it, landed.destinationUidValidity)
                    recentLocalMoves.mark(credentials.id, ImapMailService.emailId(credentials.id, trash, it))
                }
            }
            emailDao.deleteById(credentials.id, emailId)
            adjustCountsForRemoval(listOfNotNull(row), trash, sourceAlreadyLeft = moving.evicted)
            return trash
        }
        val ctx = jmapSession(credentials)
        val mb = row?.mailboxId ?: emailDao.mailboxOf(credentials.id, emailId)
        val trash = trashMailboxId(ctx) ?: createTrashFolder(ctx)
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, trash, ctx.auth, markSeen = seenByMove)
        } catch (e: JmapException) {
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(credentials.id, listOf(emailId))
            return null
        }
        // Before anything below can throw: a put-back must return the row as the server holds it.
        if (seenByMove) flightMarkedSeen(credentials.id, emailId)
        recentLocalMoves.mark(credentials.id, emailId)
        emailDao.deleteById(credentials.id, emailId)
        // A flight took the source's unread at the finger; a cached row still owes it.
        if (seenByMove && !moving.evicted) adjustFolderUnreadOnRead(credentials.id, mb, seen = true)
        adjustCountsForRemoval(listOfNotNull(row), trash, sourceAlreadyLeft = moving.evicted)
        advanceEmailState(newState, credentials.id, mb)
        return trash
    }

    /** Move [email] from [source] into [targetMailboxId] of [target], another account (#189): a copy
     *  confirmed on B, then the original trashed on A. The order is [crossAccountMove]; the copy carries
     *  on the read nothing is written on B; refused at the bin the user keeps a duplicate. */
    suspend fun moveToAccount(
        source: AccountCredentials,
        email: Email,
        target: AccountCredentials,
        targetMailboxId: String,
        frozen: FrozenNumbering = FrozenNumbering.NothingFrozen,
    ): CrossAccountMove {
        val from = withCachedFallback(email, cachedEmail(source.id, email.id))
        val steps = object : CrossAccountMoveSteps {
            var messageId: String? = null
            override fun checkSourceConfigured() = checkAccountStillConfigured(source.id, accountStore.accounts().map { it.id })
            override fun checkTargetConfigured() = checkAccountStillConfigured(target.id, accountStore.accounts().map { it.id })
            override suspend fun readSource(): ByteArray = rawSource(source, email.id, frozen).also { messageId = messageIdOf(from, it) }
            override suspend fun writeTarget(bytes: ByteArray): String? {
                if (target.protocol == MailProtocol.IMAP) {
                    val uid = imap.appendBytes(target, targetMailboxId, bytes, imapFlagsOf(from.keywords), internalDateOf(from.receivedAt))
                    return uid?.let { ImapMailService.emailId(target.id, targetMailboxId, it) }
                }
                val ctx = jmapSession(target)
                val blobId = client.uploadBlob(ctx.session, ctx.accountId, bytes, "message/rfc822", ctx.auth).blobId
                return client.importEmail(ctx.session, ctx.accountId, ctx.auth, blobId, setOf(targetMailboxId), jmapKeywordsOf(from.keywords), from.receivedAt)
            }
            override suspend fun findTarget(): String? {
                if (target.protocol != MailProtocol.IMAP) return null
                val uid = imap.findByMessageId(target, targetMailboxId, messageId ?: return null)
                return uid?.let { ImapMailService.emailId(target.id, targetMailboxId, it) }
            }
            override fun markTargetLocalMove(targetId: String) = recentLocalMoves.mark(target.id, targetId)
            override suspend fun trashSource() { delete(source, email.id, frozen) }
            override suspend fun refreshTargetFolder() { refresh(target, targetMailboxId) }
        }
        return crossAccountMove(steps)
    }

    /** A confirmed "Empty trash": the identifier of its frozen destroy list and its size. */
    data class TrashPurgeSnapshot(val purgeId: String, val messageCount: Int)

    /** Freeze what an "Empty trash" may destroy, at confirmation time (#99): the set is read here once
     *  and [purgeSnapshot] destroys exactly it. Must be called before the caller evicts the folder's
     *  cached rows, the offline fallback reads that very cache. Destroying less than asked is the safe
     *  error, so neither branch throws; a cancelled confirmation is the one failure that propagates. */
    suspend fun snapshotTrashPurge(
        credentials: AccountCredentials,
        trashMailboxId: String,
    ): TrashPurgeSnapshot {
        val now = System.currentTimeMillis()
        // Collect anything a killed process or a raced Undo left behind before adding to it.
        purgeSnapshotDao.deleteOlderThan(now - TrashPurge.SNAPSHOT_TTL_MS)
        suspend fun cached() = cachedIds(listOf(credentials.id to trashMailboxId)).map { it.emailId }
        // The numbering the frozen ids belong to (#99), read before the enumeration: a fallback on the
        // cached ids belongs to the old numbering, which a later read would stamp as current.
        val uidValidityBefore =
            if (credentials.protocol == MailProtocol.IMAP) imap.recordedUidValidity(credentials.id, trashMailboxId)
            else null
        var observedUidValidity: Long? = null
        val ids = if (credentials.protocol == MailProtocol.IMAP) {
            TrashPurge.imapSnapshotIds(
                accountId = credentials.id,
                mailboxId = trashMailboxId,
                serverUids = {
                    val snapshot = imap.snapshotUids(credentials, trashMailboxId, TrashPurge.SNAPSHOT_MAX)
                    observedUidValidity = snapshot.uidValidity
                    snapshot.uids
                },
                cached = { cached() },
            )
        } else {
            runCatching {
                val ctx = jmapSession(credentials)
                // Ids-only, paged: the purge photo needs identifiers, not bodies. Chaining an Email/get of up to
                // SNAPSHOT_MAX threw on a server enforcing maxObjectsInGet, dropping the whole thing (#99).
                val collected = mutableListOf<String>()
                while (collected.size < TrashPurge.SNAPSHOT_MAX) {
                    val page = client.queryEmailIds(
                        ctx.session, ctx.accountId, trashMailboxId, UNREAD_RESOLVE_PAGE, ctx.auth,
                        position = collected.size, calculateTotal = true,
                    )
                    if (page.ids.isEmpty()) break
                    collected += page.ids
                    // Stop on the server's total: a server clamping the limit must not end the walk early.
                    val total = page.total
                    if (total != null && collected.size >= total) break
                }
                collected.take(TrashPurge.SNAPSHOT_MAX)
            }.getOrElseUnlessCancelled {
                // The cache stands in for an unreachable server, but not for a cancelled caller: an Undo tapped
                // during this crawl would otherwise leave the withdrawn confirmation standing.
                cached()
            }
        }
        val purgeId = UUID.randomUUID().toString()
        val uidValidity = TrashPurge.snapshotUidValidity(observedUidValidity, uidValidityBefore)
        val rows = TrashPurge.snapshotRows(
            purgeId, credentials.id, trashMailboxId, ids, now, uidValidity = uidValidity,
        )
        rows.chunked(PURGE_SNAPSHOT_INSERT_BATCH).forEach { purgeSnapshotDao.insertAll(it) }
        return TrashPurgeSnapshot(purgeId, rows.size)
    }

    /** Destroy the snapshot taken by [snapshotTrashPurge], nothing else, wave by wave. An empty snapshot
     *  destroys nothing: no order. On IMAP the order also carries a numbering, checked first (#99): none
     *  drops the snapshot, and one that no longer matches aborts the purge, unretried. */
    suspend fun purgeSnapshot(credentials: AccountCredentials, purgeId: String): Int {
        var destroyed = 0
        // Set by any wave that spared ids: the confirmation evicted every cached Trash row, so no delta reports them.
        var refusedAny = false
        val head = purgeSnapshotDao.head(purgeId, credentials.id)
        val imapPurge = credentials.protocol == MailProtocol.IMAP
        if (imapPurge && head != null && !UidValidity.mayDestroy(head.uidValidity)) {
            purgeSnapshotDao.deleteSnapshot(purgeId)
            return 0
        }
        val expectedUidValidity = if (imapPurge) head?.uidValidity else null
        // The folder the confirmation was about, read off the snapshot itself, not the folder on screen
        // now, so a message rescued out of the Trash by another client is left alone (#122).
        val expectedMailboxId = head?.mailboxId
        try {
            for (wave in 1..TrashPurge.MAX_WAVES) {
                // Scoped to (purgeId, accountId): an id means something only inside its own account (#31).
                val ids = purgeSnapshotDao.wave(purgeId, credentials.id, TrashPurge.DESTROY_WAVE)
                if (ids.isEmpty()) break
                val result = destroyAll(credentials, ids, expectedMailboxId, expectedUidValidity)
                // The whole wave leaves the snapshot, spared ids included, or this loop would spin forever.
                purgeSnapshotDao.deleteIds(purgeId, credentials.id, ids)
                destroyed += result.succeeded.size
                if (result.failed.isNotEmpty()) refusedAny = true
            }
        } catch (renumbered: ImapUidValidityChanged) {
            // Not swallowed and not retried: the folder is not the one the user confirmed emptying.
            purgeSnapshotDao.deleteSnapshot(purgeId)
            folders.refreshMailboxes(credentials)
            return destroyed
        } finally {
            // In `finally` because two exits leave early: the abandoned purge, and a propagating failure.
            if (refusedAny) resetSyncState()
        }
        purgeSnapshotDao.deleteSnapshot(purgeId)
        folders.refreshMailboxes(credentials)
        return destroyed
    }

    /** Undo: withdraw the confirmation by erasing that account's Trash destroy list. The snapshot IS
     *  the order, so erasing it is what makes the undo final, even against a purge already started. */
    suspend fun discardTrashPurge(accountId: String, trashMailboxId: String) =
        purgeSnapshotDao.deleteForMailbox(accountId, trashMailboxId)

    /** Drop a snapshot by id (the purge gave up for good). */
    suspend fun discardPurgeSnapshot(purgeId: String) = purgeSnapshotDao.deleteSnapshot(purgeId)

    /** The chokepoint for a message leaving a folder: it is withdrawn from every pending "Empty trash"
     *  list of its account (#99). A JMAP id does not change on a move and nothing else removes one id
     *  from a snapshot, so a message rescued from the Trash was destroyed in its new folder. */
    private suspend fun unlistFromTrashPurge(accountId: String, emailIds: List<String>) {
        if (emailIds.isEmpty()) return
        emailIds.chunked(PURGE_SNAPSHOT_INSERT_BATCH).forEach { purgeSnapshotDao.unlistEmails(accountId, it) }
    }

    /** Structured search across the account (results are transient, not cached). IMAP sends the same
     *  criteria as `SEARCH` keys across every cached folder. The answer says whether it is whole
     *  ([MailSearchResult.complete]): a full page, a capped scan, a refusal, or a partial folder list. */
    suspend fun search(credentials: AccountCredentials, query: SearchQuery, limit: Int = 50): MailSearchResult {
        if (query.isEmpty()) return MailSearchResult(emptyList())
        if (credentials.protocol == MailProtocol.IMAP) {
            // Folders come from the cache; an account whose folders were never synced falls back to the inbox.
            val known = searchableFolderIds(mailboxDao.searchOrder(credentials.id))
            val folders = known.ifEmpty { listOfNotNull(mailboxDao.idForRole(credentials.id, "inbox")) }
            // Nothing to search at all, not even an inbox id. That is "we looked nowhere", which must
            // never be reported as the confident "No results" the empty state states as a fact.
            if (folders.isEmpty()) return MailSearchResult(emptyList(), complete = false)
            // The index's two steps are side work: a locked FTS table (#71) must not fail the search.
            val claims: Map<String, Set<Long>> = if (query.indexMayBeContradicted()) {
                indexStep(emptyMap()) {
                    indexClaimsOf(emailFtsDao.uncachedMatching(credentials.id, folders, query.unread, query.flagged), folders)
                }
            } else {
                emptyMap()
            }
            val hits = imap.search(credentials, folders, query.toImapCriteria(), query.requiresLocalScan(), limit, claims)
            val purged = indexStep(emptySet<EmailKey>()) {
                applyIndexVerdict(credentials.id, hits, emailFtsDao::deleteByIds, emailFtsDao::setFlags)
            }
            return MailSearchResult(
                emails = withCachedSearchPreviews(hits.messages) { ids, numbering ->
                    emailDao.cachedPreviews(credentials.id, ids, numbering)
                }.map { it.toEmail() },
                complete = imapSearchComplete(known, hits.complete, hits.messages.size, limit),
                purged = purged,
            )
        }
        val ctx = jmapSession(credentials)
        // Exclude Trash/Junk server-side too, so JMAP matches the IMAP walk. The raw folder list is kept:
        // an empty cache excludes nothing, and that answer must not come back wearing a total.
        val cachedFolders = mailboxDao.searchOrder(credentials.id)
        val excluded = excludedSearchFolderIds(cachedFolders)
        val hits = client.searchEmails(ctx.session, ctx.accountId, query, limit, ctx.auth, excluded)
        // A hit can live in several mailboxes: resolve its folder like [fetchThreadMembers], never the
        // server map's arbitrary first key, which could feed a search-row action a Trash/Junk folder.
        val cachedMailbox = byIdsChunked(hits.emails.map { it.id }) { chunk ->
            emailDao.emailsByIds(credentials.id, chunk)
        }.associate { it.id to it.mailboxId }
        val resolved = hits.emails.map { e ->
            val serverBoxes = e.mailboxIds.keys
            val mailbox = cachedMailbox[e.id]?.takeIf { it in serverBoxes }
                ?: rankedMailboxPick(credentials.id, serverBoxes)
            e.copy(mailboxId = mailbox ?: e.mailboxId)
        }
        // A full page, a short get, and a scope an empty folder cache chose: [jmapSearchComplete] holds all three.
        return MailSearchResult(
            resolved,
            complete = jmapSearchComplete(cachedFolders, hits.matchedIds, resolved.size, limit),
            excerpts = excerptsOf(resolved, hits.snippetPreviews),
        )
    }

    private suspend fun <T> indexStep(fallback: T, step: suspend () -> T): T =
        runCatching { step() }.getOrElse { error ->
            if (error is CancellationException) throw error
            logWarn("MailSearch", "index step of a server search skipped: ${error.javaClass.simpleName}", null)
            fallback
        }

    /** Unified search across several accounts, each [search] in parallel. A failing account is skipped,
     *  logged, and makes the answer incomplete rather than passing for the whole result. */
    suspend fun search(accounts: List<AccountCredentials>, query: SearchQuery, limit: Int = 50): MailSearchResult {
        if (query.isEmpty() || accounts.isEmpty()) return MailSearchResult(emptyList())
        if (accounts.size == 1) {
            val only = accounts.first()
            return search(only, query, limit).ownedBy(only.id)
        }
        val perAccount = coroutineScope {
            accounts.map { credentials ->
                async {
                    runCatching {
                        search(credentials, query, limit).ownedBy(credentials.id)
                    }.onFailure { error ->
                        // Cancellation is not a failure and must keep propagating, runCatching catches it too.
                        if (error is CancellationException) throw error
                        // The account id is a local UUID; this is the only trace a dropped account leaves.
                        logWarn(
                            "MailSearch",
                            "account ${credentials.id} dropped from unified search: " +
                                "${error.javaClass.simpleName}: ${error.message}",
                            null,
                        )
                    }.getOrDefault(MailSearchResult(emptyList(), complete = false))
                }
            }.awaitAll()
        }
        return mergeAccountSearches(perAccount, limit)
    }

    /** Fetch an email (with body) without marking it read, used to build replies/forwards. */
    suspend fun fetchEmail(credentials: AccountCredentials, emailId: String): Email {
        if (credentials.protocol == MailProtocol.IMAP) return openEmailImap(credentials, emailId, markRead = false)
        val ctx = jmapSession(credentials)
        return client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)
    }

    /** All emails in a conversation (lightweight, no body). */
    suspend fun threadEmails(credentials: AccountCredentials, threadId: String): List<Email> {
        val ctx = jmapSession(credentials)
        return client.getThreadEmails(ctx.session, ctx.accountId, threadId, ctx.auth)
    }

    /** Fetch a thread's full membership (JMAP Thread/get) so an inline-expanded conversation shows members
     *  outside the folder's sync window. Persists each under its real mailbox; empty on IMAP and failure. */
    suspend fun fetchThreadMembers(
        credentials: AccountCredentials,
        threadId: String,
        viewMailboxIds: List<String> = emptyList(),
    ): List<Email> {
        if (credentials.protocol == MailProtocol.IMAP) return emptyList()
        val emails = runCatching { threadEmails(credentials, threadId) }.getOrNull() ?: return emptyList()
        // Skip any member with no folder rather than invent one. The pick is deterministic, never the map's
        // arbitrary first key, which could re-key a correctly-filed row into Trash.
        val cachedMailbox = byIdsChunked(emails.map { it.id }) { chunk ->
            emailDao.emailsByIds(credentials.id, chunk)
        }.associate { it.id to it.mailboxId }
        val entities = emails.mapNotNull { e ->
            val serverBoxes = e.mailboxIds.keys
            val mailbox = cachedMailbox[e.id]?.takeIf { it in serverBoxes }
                ?: e.mailboxId
                ?: viewMailboxIds.firstOrNull { it in serverBoxes }
                ?: rankedMailboxPick(credentials.id, serverBoxes)
                ?: return@mapNotNull null
            e.toEntity(credentials.id, mailbox)
        }
        if (entities.isNotEmpty()) runCatching {
            emailDao.upsertAll(sparingInFlight(entities))
            // Guard the fresh rows through the reconcile window, or the next full re-query prunes every
            // non-representative member back out, leaving the chip at 1 with its members unreachable.
            entities.forEach { markRecentlyMutated(credentials.id, it.id) }
        }
        // Wire members carry no accountId; stamp it so no downstream key falls back to an unscoped lookup.
        return emails.map { if (it.accountId == null) it.copy(accountId = credentials.id) else it }
    }

    /** A message in several mailboxes: inbox > archive > other > junk > trash, ids sorted as tie-break. */
    private suspend fun rankedMailboxPick(accountId: String, mailboxIds: Set<String>): String? =
        mailboxIds.sorted().minByOrNull { id ->
            when (mailboxDao.roleForId(accountId, id)) {
                "inbox" -> 0
                "archive" -> 1
                "junk" -> 3
                "trash" -> 4
                else -> 2
            }
        }

    /** Cached members of a thread for inline expansion, newest-first. Live, and that is the point: the
     *  chip on the collapsed row queries the same table, so both sides read the same write. */
    fun observeThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): Flow<List<Email>> =
        if (mailboxIds.isEmpty()) flowOf(emptyList())
        else emailDao.cachedThreadEmails(accountId, mailboxIds, threadKey).map { rows -> rows.map { it.toEmail() } }

    /** One reading of [observeThreadEmails], for callers that act on a thread once rather than draw it. */
    suspend fun cachedThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): List<Email> =
        observeThreadEmails(accountId, mailboxIds, threadKey).first()

    /** The cached Sent-role folder of each of [accountIds]. No one-shot variant on purpose: two lookups
     *  are two answers, and the reader sees a chip that lies. */
    fun observeSentMailboxes(accountIds: List<String>): Flow<List<Pair<String, String>>> =
        mailboxDao.observeSentMailboxes(accountIds.distinct())
            .map { rows -> rows.map { it.accountId to it.id } }
            .distinctUntilChanged()

    /** Every known (account, folder) → role of [accountIds], reactively (#115). A folder missing from the
     *  map is unknown, not role-less: callers must fall back rather than conclude. */
    fun observeFolderRoles(accountIds: List<String>): Flow<Map<Pair<String, String>, String>> =
        mailboxDao.observeRoles(accountIds.distinct())
            .map(::folderRoleMap)
            .distinctUntilChanged()

    /** Remove [emailIds] from the cache only, decounting each row's own folder, chunk by chunk. */
    suspend fun evictAll(accountId: String, emailIds: Collection<String>) {
        if (emailIds.isEmpty()) return
        emailIds.chunked(MAX_CHANGES).forEach { chunk ->
            val rows = emailDao.emailsByIds(accountId, chunk)
            deleteFromCacheAndIndex(accountId, chunk)
            adjustCountsForRemoval(rows, destMailboxId = null)
        }
    }

    /** Drop in-memory sync bookkeeping; call after clearing the cache, or sync reads stale state. */
    override fun resetSyncState() {
        syncStates.clear()
        cachedSession = null
    }

    /** Drop one account's sync cursors; the full-query branch sizes itself on the account. */
    fun dropSyncCursors(accountId: String) {
        if (accountId.isBlank()) return
        syncStates.dropAccount(accountId, accountStore.accounts().map { it.id })
    }

    suspend fun disconnectImap(accountId: String) = imap.disconnect(accountId)

    suspend fun hasArchiveFolder(credentials: AccountCredentials): Boolean =
        archiveMailboxId(jmapSession(credentials)) != null

    private class ResolvedJmapAccount(
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val mailboxes: List<Mailbox>,
    )

    private suspend fun resolveJmapAccount(credentials: AccountCredentials): ResolvedJmapAccount {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = jmapAccountIdFor(credentials, session)
        val mailboxes = client.getMailboxes(session, accountId, auth)
        return ResolvedJmapAccount(session, accountId, auth, mailboxes)
    }

    private fun jmapAccountIdFor(credentials: AccountCredentials, session: JmapSession): String =
        credentials.jmapAccountId
            ?: session.mailAccountId()
            ?: error("This user has no JMAP mail account.")

    /** Re-key persisted watched-folder ids and persist the correction (#101). */
    private fun rekeyWatchedFolders(accountId: String, watched: Set<String>): Set<String> {
        if (watched.isEmpty()) return watched
        val corrected = watched.associateWith { decodeMailboxPath(it) }.filter { it.key != it.value }
        corrected.forEach { (stored, path) ->
            runCatching { accountStore.replaceWatchedFolder(accountId, stored, path) }
        }
        return watched.map { corrected[it] ?: it }.toSet()
    }

    /** Refresh the inbox (unless [includeInbox] is false) plus the watched [extraFolderIds] (#16). */
    suspend fun refreshAccountFolders(
        credentials: AccountCredentials,
        extraFolderIds: Set<String>,
        includeInbox: Boolean = true,
        limit: Int = 50,
        onMissing: (String) -> Unit = {},
    ): List<FolderRefresh> {
        if (credentials.protocol == MailProtocol.IMAP) {
            // Refuse to start for an account already gone: a whole background pass would be spent.
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
            val (loads, missing) = imap.loadWatchedFolders(
                credentials, rekeyWatchedFolders(credentials.id, extraFolderIds), includeInbox, limit,
            )
            missing.forEach(onMissing)
            // Ask again: nothing cancels this pass when the account goes, and seconds of network
            // sit between the entry check and this write (#121). One call, outside the loop.
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
            // Rebuilt before they are written: `@Upsert` replaces the whole row and this pass
            // reads no body, so raw rows would blank the `preview` column (#187).
            loads.forEach { load ->
                val cached = emailDao.cachedPreviews(credentials.id, load.messages.map { it.id }, load.uidValidity)
                emailDao.upsertAll(sparingInFlight(keepingCachedPreviews(load.messages, cached.associate { it.id to it.preview })))
            }
            // No departures on IMAP: re-reading a folder says what it holds, never what left (#134).
            return loads.map { load ->
                // The baseline is what this page can vouch for: nothing when it came back empty
                // without the server stating the folder empty (`watchedBaselineIds`).
                val page = load.messages.map { it.toEmail() }
                FolderRefresh(
                    load.mailboxId, load.name, load.role, page, watchedBaselineIds(load),
                    departedIds = emptyList(),
                    previewSources = load.previewSources,
                )
            }
        }
        val resolved = resolveJmapAccount(credentials)
        val inbox = resolved.mailboxes.firstOrNull { it.role == "inbox" }
            ?: resolved.mailboxes.firstOrNull()
            ?: error("No mailboxes found.")
        val byId = resolved.mailboxes.associateBy { it.id }
        // Invariant held five hops away: on JMAP `targets` is never empty, `includeInbox = false`
        // coming only from `MailFetchWorker`, whose `pollExtras` is always false on JMAP.
        val targets = buildList {
            if (includeInbox) add(inbox)
            extraFolderIds.forEach { id ->
                val mailbox = byId[id]
                when {
                    mailbox == null -> onMissing(id)
                    mailbox.id != inbox.id -> add(mailbox)
                }
            }
        }
        val refreshes = targets.map { mailbox ->
            val sync = syncMailbox(
                resolved.session, resolved.accountId, resolved.auth, mailbox.id,
                folderSyncSizing(limit, resolved.session.getBatchSize()), credentials.id,
            )
            // The notification read, not the folder: the whole cache in a worker is a crash loop.
            val read = notifyRead(credentials.id, mailbox.id)
            FolderRefresh(
                mailboxId = mailbox.id,
                name = mailbox.name,
                role = mailbox.role,
                emails = read.emails,
                baselineIds = read.baselineIds,
                // Read after the sync, so a departed id is already out of [emails] (#134).
                departedIds = sync.departedIds,
                // Nothing to convey: a JMAP envelope already carries `preview` in the cache row.
                previewSources = emptyMap(),
            )
        }
        // The JMAP side of the question the IMAP branch asks twice (#121). Narrowed, not closed.
        checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
        // Persist the fetched folder counters, after the row syncs, or the drawer badge undercounts.
        mailboxDao.replaceAll(credentials.id, resolved.mailboxes.toEntities(credentials))
        return refreshes
    }

    // ---- JMAP PushSubscription (issue #17), session-level and cache-free ----

    suspend fun createPushSubscription(credentials: AccountCredentials, subscription: PushSubscription): PushSubscription {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        return client.createPushSubscription(session, auth, subscription)
    }

    suspend fun verifyPushSubscription(credentials: AccountCredentials, subscriptionId: String, verificationCode: String) {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        client.verifyPushSubscription(session, auth, subscriptionId, verificationCode)
    }

    suspend fun renewPushSubscription(credentials: AccountCredentials, subscriptionId: String, expires: String): String {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        return client.updatePushSubscriptionExpires(session, auth, subscriptionId, expires)
    }

    /** Destroy a subscription (sign-out / endpoint rotation); already-gone is success. */
    suspend fun destroyPushSubscription(credentials: AccountCredentials, subscriptionId: String) {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        client.destroyPushSubscription(session, auth, subscriptionId)
    }

    /** The server's VAPID key (RFC 9749) when advertised; null otherwise (e.g. Stalwart). */
    suspend fun pushVapidKey(credentials: AccountCredentials): String? {
        val auth = jmapAuth(credentials)
        return client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth).vapidPublicKey()
    }

    /** Open a push connection for one account; [onChanged] on mail change, [onClosed] on drop. */
    suspend fun openAccountPush(
        credentials: AccountCredentials,
        onChanged: () -> Unit,
        onClosed: () -> Unit = {},
        // The account ids whose Email state should wake [onChanged] (issue #31); Stalwart pushes
        // nothing for an ACL-shared one. Empty = this credential's own account.
        watchedJmapAccountIds: Set<String> = emptySet(),
    ): Closeable {
        if (credentials.protocol == MailProtocol.IMAP) {
            return imap.openIdle(credentials, onChanged = onChanged, onClosed = onClosed)
        }
        val resolved = resolveJmapAccount(credentials)
        val watched = watchedJmapAccountIds.ifEmpty { setOf(resolved.accountId) }
        return client.openEventSource(
            session = resolved.session,
            auth = resolved.auth,
            onStateChange = { change -> if (watched.any { change.emailChanged(it) }) onChanged() },
            onClosed = onClosed,
        )
    }

    /** The addressing an IMAP draft carries, read for the send path (#63); [MailDrafts] owns it. */
    override suspend fun imapDraftAddressing(
        credentials: AccountCredentials,
        emailId: String,
        budgetMs: Int,
    ): Set<String>? = drafts.imapDraftAddressing(credentials, emailId, budgetMs)

    /** The draft a delivered message replaces (#63), destroyed under the numbering the row froze (#99). */
    override suspend fun destroyDraft(credentials: AccountCredentials, emailId: String, frozenUidValidity: Long?) =
        drafts.destroyDraft(credentials, emailId, frozenUidValidity)

    override fun noteOwnKeywordWrite(accountId: String, emailId: String) = markRecentlyMutated(accountId, emailId)

    /** The bytes of an attachment part; [maxBytes] is the ceiling this caller accepts. */
    suspend fun downloadAttachment(
        credentials: AccountCredentials,
        part: EmailBodyPart,
        emailId: String,
        maxBytes: Long = DownloadLimits.ATTACHMENT_MAX_BYTES,
    ): ByteArray {
        if (part.partId?.startsWith("pgp:") == true) return pgpAttachmentBytes(credentials.id, emailId, part)
        // The size is announced by the message (JMAP) or its BODYSTRUCTURE (IMAP): refuse before
        // the round-trip, and before the branch, so IMAP is held to the same ceiling as JMAP.
        DownloadLimits.enforce(part.size, maxBytes)
        if (credentials.protocol == MailProtocol.IMAP) {
            val (mb, uid) = imapTarget(emailId) ?: error("Couldn't locate the message.")
            val section = part.partId ?: error("Attachment has no section.")
            return imap.fetchAttachment(credentials, mb, uid, section, part.encoding)
        }
        val ctx = jmapSession(credentials)
        val blobId = part.blobId ?: error("Attachment has no blob.")
        return client.downloadBlob(
            ctx.session, ctx.accountId, blobId, part.type, part.name, ctx.auth, maxBytes,
        )
    }

    override suspend fun uploadAttachment(
        credentials: AccountCredentials,
        bytes: ByteArray,
        type: String?,
        name: String?,
        disposition: String,
        cid: String?,
    ): EmailBodyPart {
        val ctx = jmapSession(credentials)
        // Blobs are account-scoped, and a sub-account's outgoing Email is created under the
        // LOGIN's account (see performSend). Upload where the send will reference it.
        val uploadAccountId = ctx.session.mailAccountId() ?: ctx.accountId
        // Checked before sending: otherwise the whole file crosses the network only to be refused.
        val maxBytes = ctx.session.maxSizeUpload()
        if (maxBytes != null && bytes.size > maxBytes) {
            throw ContentTooLargeException(
                "Attachment is ${bytes.size} bytes, over the server's $maxBytes upload limit.",
                bytes = bytes.size.toLong(),
                maxBytes = maxBytes,
            )
        }
        val blob = client.uploadBlob(ctx.session, uploadAccountId, bytes, type, ctx.auth)
        return EmailBodyPart(
            blobId = blob.blobId,
            type = blob.type,
            size = blob.size,
            name = name,
            disposition = disposition,
            cid = cid,
        )
    }

    override suspend fun jmapSession(credentials: AccountCredentials): JmapAccountSession {
        cachedSession?.let { if (it.credentials == credentials) return it }
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = jmapAccountIdFor(credentials, session)
        accounts.reconcileLinkedAccounts(credentials, session, auth)
        accounts.refreshServerIdentities(session, accountId, credentials.id, auth)
        val mailboxes = client.getMailboxes(session, accountId, auth)
        val roles = jmapRolesToMailboxId(mailboxes, credentials.specialFolders)
        return JmapAccountSession(credentials, session, accountId, auth, roles, mailboxes).also { cachedSession = it }
    }
}
