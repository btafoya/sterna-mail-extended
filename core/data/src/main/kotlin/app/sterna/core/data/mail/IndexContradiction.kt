package app.sterna.core.data.mail

import app.sterna.core.imap.ImapFolderHits
import app.sterna.core.imap.ImapIndexClaims
import app.sterna.core.jmap.model.SearchQuery

/** Ids per index delete: SQLite caps the bound parameters of one statement. */
private const val INDEX_PURGE_BATCH = 500

/**
 * Whether an IMAP search's answer may contradict the index row by row. Only the flags are evaluated
 * alike by the server and by the index; words, addresses, dates and attachments are not (prefix
 * terms, tokenizer, time zone, the scan cap), so a row outside the server's answer for those proves
 * nothing.
 */
internal fun SearchQuery.indexMayBeContradicted(): Boolean =
    (flagged || unread) &&
        text.isBlank() && from.isBlank() && recipient.isBlank() && subject.isBlank() &&
        afterMillis == null && beforeMillis == null && !hasAttachment

/**
 * Index ids as the walk takes them: folder path to UIDs, grouped by the folder the id names, since a
 * UID means something only there. Ids outside [folders], or not IMAP ids, are left out. [ids] come
 * from `EmailFtsDao.uncachedMatching`, which leaves out a row whose message is cached: that row is
 * the sync's to reconcile, and the eviction keeps the index on purpose.
 */
internal fun indexClaimsOf(ids: List<String>, folders: Collection<String>): Map<String, Set<Long>> {
    val searched = folders.toSet()
    return ids.mapNotNull { ImapMailService.targetOf(it) }
        .filter { (folder, _) -> folder in searched }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, uids) -> uids.toSet() }
}

/**
 * Write what a search walk proved into [accountId]'s index and return the keys it removed. Both
 * writes are scoped by account: ids collide across accounts (#31).
 */
internal suspend fun applyIndexVerdict(
    accountId: String,
    hits: ImapSearchHits,
    delete: suspend (accountId: String, ids: List<String>) -> Unit,
    setFlags: suspend (accountId: String, emailId: String, seen: Boolean, flagged: Boolean) -> Unit,
): Set<EmailKey> {
    hits.indexGone.chunked(INDEX_PURGE_BATCH).forEach { delete(accountId, it) }
    hits.indexFlags.forEach { setFlags(accountId, it.emailId, it.seen, it.flagged) }
    return hits.indexGone.mapTo(LinkedHashSet()) { EmailKey(accountId, it) }
}

/**
 * [claims] with the numbering recorded for their folder; a folder with none recorded is dropped, its
 * index ids possibly belonging to a numbering the server has since replaced.
 */
internal suspend fun claimsUnderRecordedNumbering(
    claims: Map<String, Set<Long>>,
    recorded: suspend (path: String) -> Long?,
): Map<String, ImapIndexClaims> = claims.mapNotNull { (path, uids) ->
    recorded(path)?.takeIf { it > 0L }?.let { path to ImapIndexClaims(uids, it) }
}.toMap()

/** An index row's flags as a server just stated them. */
data class IndexFlags(val emailId: String, val seen: Boolean, val flagged: Boolean)

internal fun indexGoneOf(accountId: String, folders: List<ImapFolderHits>): List<String> =
    folders.flatMap { hits -> hits.verdict?.gone.orEmpty().map { ImapMailService.emailId(accountId, hits.mailbox, it) } }

internal fun indexFlagsOf(accountId: String, folders: List<ImapFolderHits>): List<IndexFlags> =
    folders.flatMap { hits ->
        hits.verdict?.flags.orEmpty().map { (uid, flags) ->
            IndexFlags(
                ImapMailService.emailId(accountId, hits.mailbox, uid),
                seen = flags.any { it.equals("\\Seen", ignoreCase = true) },
                flagged = flags.any { it.equals("\\Flagged", ignoreCase = true) },
            )
        }
    }
