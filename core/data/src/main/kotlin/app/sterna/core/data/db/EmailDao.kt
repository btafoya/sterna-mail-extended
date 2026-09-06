package app.sterna.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.mail.evictableCachedIds
import app.sterna.core.data.mail.reconcileEvictions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Hides messages snoozed into the future — the list's own predicate
 * ([app.sterna.core.data.mail.notSnoozedSql] for the `emails` table) as a compile-time constant,
 * which a Room `@Query` needs; `SenderVolumeSqlTest` asserts the two are the same text.
 * Correlated on `accountId` too: snoozes are keyed per account (#31).
 */
const val NOT_SNOOZED_EMAILS_SQL: String =
    "NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = emails.id " +
        "AND snoozed.accountId = emails.accountId AND snoozed.until > " +
        "(CAST(strftime('%s','now') AS INTEGER) * 1000))"

/**
 * Which cached rows the per-sender screen speaks for — the one clause behind both
 * [SENDER_VOLUMES_SQL] and [SENDER_MESSAGE_IDS_SQL], so the count shown and the set removed are
 * the same set by construction. Scoped by (account, folder) pairs, never by a bare `mailboxId`:
 * servers like Stalwart number mailboxes per account (#121). `role IS NULL OR …` is load-bearing —
 * `role NOT IN (…)` is NULL, hence false, when `role` is NULL, which would exclude the INBOX of a
 * server that types no folder.
 */
const val SENDER_VOLUME_SCOPE_SQL: String =
    "accountId = :accountId AND fromEmail IS NOT NULL AND fromEmail != '' " +
        "AND mailboxId IN (SELECT id FROM mailboxes WHERE accountId = :accountId " +
        "AND (role IS NULL OR LOWER(role) NOT IN ('sent','drafts','trash','junk','spam'))) " +
        "AND " + NOT_SNOOZED_EMAILS_SQL

/**
 * Cached mail per sender, grouped on `LOWER(fromEmail)`: the address is authoritative, the display
 * name is not, and nothing beyond case is normalised (a `+tag` is a different subscription).
 * `name` is a bare column beside a `MAX()`, so SQLite yields the most recent message's name.
 * No `LIMIT`: the header total is the sum of these rows.
 */
const val SENDER_VOLUMES_SQL: String =
    "SELECT fromEmail AS email, fromName AS name, COUNT(*) AS total, " +
        "SUM(CASE WHEN seen = 0 THEN 1 ELSE 0 END) AS unread, MAX(sortKey) AS latest " +
        "FROM emails WHERE " + SENDER_VOLUME_SCOPE_SQL +
        " GROUP BY LOWER(fromEmail) ORDER BY total DESC, latest DESC"

/** The ids behind one line of [SENDER_VOLUMES_SQL] — same rows, same clause text. */
const val SENDER_MESSAGE_IDS_SQL: String =
    "SELECT id FROM emails WHERE " + SENDER_VOLUME_SCOPE_SQL + " AND LOWER(fromEmail) = LOWER(:email)"

@Dao
interface EmailDao {

    /**
     * Paged source for the list; the query is built dynamically (MailRepository.pagingQuery)
     * because the ORDER BY and mailbox-id set vary per view.
     */
    @RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, EmailEntity>

    /** Paged source for the conversation (collapsed-thread) list, built dynamically by
     *  MailRepository.conversationQuery. */
    @RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])
    fun conversationPagingSource(query: SupportSQLiteQuery): PagingSource<Int, ConversationRow>

    /**
     * One-shot bounded read of the latest messages across every account's inbox, for the widget
     * (MailRepository.recentUnifiedSql). [RecentEmailRow], never [EmailEntity]: the entity
     * carries `preview`, and a body has no business on a lock-screen-visible surface.
     */
    @RawQuery
    suspend fun recentUnified(query: SupportSQLiteQuery): List<RecentEmailRow>

    /**
     * [recentUnified] as a Flow (#112). Both entities in [observedEntities]: the shared predicate
     * carries the snooze filter, so an expiring snooze changes these rows with no write to
     * `emails`. Room invalidates per table, so the collector de-duplicates (`RecentMailWidgetPush`).
     */
    @RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])
    fun observeRecentUnified(query: SupportSQLiteQuery): Flow<List<RecentEmailRow>>

    /** Distinct recent senders matching [q] (for recipient autocomplete). */
    @Query(
        "SELECT fromEmail AS email, fromName AS name FROM emails " +
            "WHERE fromEmail IS NOT NULL AND fromEmail != '' " +
            "AND (fromEmail LIKE '%' || :q || '%' OR fromName LIKE '%' || :q || '%') " +
            "GROUP BY LOWER(fromEmail) ORDER BY MAX(sortKey) DESC LIMIT :limit",
    )
    suspend fun suggestSenders(q: String, limit: Int): List<ContactRow>

    /**
     * Cached mail per sender ([SENDER_VOLUMES_SQL]). `suspend`, not a `Flow`: Room invalidates on
     * every write to `emails`, so a reactive version would replay a full table scan per sync page.
     */
    @Query(SENDER_VOLUMES_SQL)
    suspend fun senderVolumes(accountId: String): List<SenderVolumeRow>

    /** The ids of one sender's counted messages ([SENDER_MESSAGE_IDS_SQL]). */
    @Query(SENDER_MESSAGE_IDS_SQL)
    suspend fun senderMessageIds(accountId: String, email: String): List<String>

    // The four per-folder reads below are scoped by accountId as well as mailboxId: servers
    // (e.g. Stalwart) number mailboxes per account, so two accounts' inboxes can share an id
    // (#31 / #121). Each one that carries a LIMIT breaks its ties on `id` — `ORDER BY sortKey
    // DESC` alone leaves equal keys to the physical order, which every sync rewrites, so a row on
    // the LIMIT frontier would come and go with nothing having changed.

    /** The newest [limit] ids in one folder — the bound is the statement's, not the caller's. */
    @Query(
        "SELECT id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "ORDER BY sortKey DESC, id DESC LIMIT :limit",
    )
    suspend fun newestIds(accountId: String, mailboxId: String, limit: Int): List<String>

    /**
     * The account-qualified keys of a folder's unread messages, newest first, at most [limit].
     * `seen = 0` is the statement's, not a Kotlin filter: "mark all read" would otherwise read the
     * whole cache to discard most of it.
     */
    @Query(
        "SELECT accountId, id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND seen = 0 ORDER BY sortKey DESC, id DESC LIMIT :limit",
    )
    suspend fun unreadKeys(accountId: String, mailboxId: String, limit: Int): List<EmailKeyRow>

    /**
     * A folder's rows received at or after [since], newest first, at most [limit] — the candidates
     * a new-mail notification pass hydrates and diffs. `sortKey = 0` is admitted on purpose: it is
     * what an unparseable `receivedAt` maps to and the notifier's age floor is lenient about those
     * ([app.sterna.core.data.mail.NOTIFY_HORIZON_MS]); they sort last, so [limit] sheds them first.
     * Not what seeds the notifier's baseline — [idsReceivedSince] is.
     */
    @Query(
        "SELECT * FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND (sortKey >= :since OR sortKey = 0) ORDER BY sortKey DESC, id DESC LIMIT :limit",
    )
    suspend fun receivedSince(accountId: String, mailboxId: String, since: Long, limit: Int): List<EmailEntity>

    /**
     * The ids [receivedSince] selects from, without its row cap — what a notification pass writes
     * into the folder's baseline. `NewMailNotifier.seed` replaces a baseline, so a bound a row can
     * fall out of and back into forgets that row and then announces it; a row count is such a
     * bound, the instant [since] is not.
     */
    @Query(
        "SELECT id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND (sortKey >= :since OR sortKey = 0)",
    )
    suspend fun idsReceivedSince(accountId: String, mailboxId: String, since: Long): List<String>

    // The id-keyed reads/writes below are scoped by accountId as well: with the composite
    // (accountId, id) key (#31) an email id is not unique across accounts, and an unscoped
    // UPDATE/DELETE would hit a same-server sibling account's row.

    /** The cached mailbox an email lives in (used to advance that mailbox's sync cursor). */
    @Query("SELECT mailboxId FROM emails WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun mailboxOf(accountId: String, id: String): String?

    /**
     * The opening lines this account already holds for [ids] under the numbering [uidValidity] — id
     * and value both, because the folder read subtracts these ids from its page while both writers
     * copy these values back onto the rows they write, `@Upsert` replacing the whole row (#187).
     *
     * Bound to the numbering (#99): an IMAP row id carries no UIDVALIDITY and the rows survive a
     * renumbering, so an answer keyed by id alone hands a new message the previous one's opening
     * line, for ever. `IS`, not `=`, which is never true against the NULL a server stating no
     * UIDVALIDITY leaves here. [ids] is one page: SQLite caps host variables at 999.
     */
    @Query(
        "SELECT id, preview FROM emails WHERE accountId = :accountId AND id IN (:ids) " +
            "AND uidValidity IS :uidValidity AND preview IS NOT NULL",
    )
    suspend fun cachedPreviews(accountId: String, ids: List<String>, uidValidity: Long?): List<CachedPreview>

    @Query("SELECT seen FROM emails WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun seenOf(accountId: String, id: String): Boolean?

    @Upsert
    suspend fun upsertAll(emails: List<EmailEntity>)

    /**
     * Prune a mailbox's cached page down to [keepIds] — window eviction, not removal: the messages
     * are still in that folder on the server. Which is why this must not touch the search index,
     * unlike [deleteById]/[deleteByIds]: the index outlives the display cache, and un-indexing on
     * eviction would hollow it out on every scroll.
     */
    @Query("DELETE FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(accountId: String, mailboxId: String, keepIds: List<String>)

    /**
     * Like [deleteNotIn] but never touches [spareIds] — ids just mutated locally, protected until
     * the server reflects the change, so a stale re-query page cannot clobber an optimistic Undo.
     */
    @Query(
        "DELETE FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND id NOT IN (:keepIds) AND id NOT IN (:spareIds)",
    )
    suspend fun deleteNotInSparing(accountId: String, mailboxId: String, keepIds: List<String>, spareIds: List<String>)

    /**
     * Evict [ids] from the display cache and leave their index rows alone — [deleteNotIn]'s by-id
     * sibling. Sending them through [deleteByIds] instead un-indexes them, and offline search then
     * stops covering anything older than the sync window, irreversibly on IMAP.
     */
    suspend fun evictFromCacheKeepingIndex(accountId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        deleteRowsByIds(accountId, ids)
    }

    @Query("UPDATE emails SET seen = :seen WHERE accountId = :accountId AND id = :id")
    suspend fun setSeen(accountId: String, id: String, seen: Boolean)

    @Query("UPDATE emails SET flagged = :flagged WHERE accountId = :accountId AND id = :id")
    suspend fun setFlagged(accountId: String, id: String, flagged: Boolean)

    /**
     * Take one message out of this place: cached row and search-index row dropped together in one
     * transaction, with the recovery for a failed index write outside it. Every removal path funnels
     * through this or [deleteByIds], so the index cannot outlive the cache — contrast [deleteNotIn]
     * and [evictFromCacheKeepingIndex], which must not un-index.
     *
     * Nothing is caught inside [deleteRowAndIndexById]: SQLite aborts a `SQLITE_BUSY` statement
     * and continues its transaction, so a swallowed error would commit the cache delete with its
     * index row standing — a deleted message handed back in search for ever (#71).
     */
    suspend fun deleteById(accountId: String, id: String) {
        try {
            deleteRowAndIndexById(accountId, id)
        } catch (e: CancellationException) {
            throw e // the caller went away: commit neither half, let the next attempt do both
        } catch (e: Exception) {
            android.util.Log.w("MailSync", "deleting $id with its index row failed", e)
            deleteRowById(accountId, id)
            runCatching { unindexById(accountId, id) }.getOrElseUnlessCancelled {
                android.util.Log.w("MailSync", "un-index of $id failed; its cached row is gone anyway", it)
            }
        }
    }

    /** [deleteById]'s two halves as one all-or-nothing statement pair — see its comment for why
     *  nothing is caught in here. */
    @Transaction
    suspend fun deleteRowAndIndexById(accountId: String, id: String) {
        deleteRowById(accountId, id)
        unindexById(accountId, id)
    }

    /**
     * [deleteById] for several ids of one account. Prefer it to a loop of [deleteById]: `email_fts`
     * is FTS4 with a `notindexed` `emailId`, so every un-index scans the whole index.
     */
    suspend fun deleteByIds(accountId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        try {
            deleteRowsAndIndexByIds(accountId, ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MailSync", "deleting ${ids.size} ids with their index rows failed", e)
            deleteRowsByIds(accountId, ids)
            runCatching { unindexByIds(accountId, ids) }.getOrElseUnlessCancelled {
                android.util.Log.w("MailSync", "un-index of ${ids.size} ids failed; cached rows gone anyway", it)
            }
        }
    }

    /** [deleteByIds]' two halves as one all-or-nothing statement pair — see [deleteById]. */
    @Transaction
    suspend fun deleteRowsAndIndexByIds(accountId: String, ids: List<String>) {
        deleteRowsByIds(accountId, ids)
        unindexByIds(accountId, ids)
    }

    // The two halves of [deleteRowAndIndexById] / [deleteRowsAndIndexByIds] — and, for the cache
    // half alone, [evictFromCacheKeepingIndex]. Call those, not these.
    @Query("DELETE FROM emails WHERE accountId = :accountId AND id = :id")
    suspend fun deleteRowById(accountId: String, id: String)

    @Query("DELETE FROM emails WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun deleteRowsByIds(accountId: String, ids: List<String>)

    @Query("DELETE FROM email_fts WHERE accountId = :accountId AND emailId = :id")
    suspend fun unindexById(accountId: String, id: String)

    @Query("DELETE FROM email_fts WHERE accountId = :accountId AND emailId IN (:ids)")
    suspend fun unindexByIds(accountId: String, ids: List<String>)

    /** Cached message count for one account's mailbox (end-of-pagination check). */
    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun countForMailbox(accountId: String, mailboxId: String): Int

    /**
     * Oldest cached row that is its thread's newest within the mailbox (anchor for the next older
     * page). The cache can hold members far below the contiguous window, and anchoring on one of
     * those would skip the gap; the oldest representative stays within it.
     */
    @Query(
        "SELECT id FROM emails e WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND sortKey = (SELECT MAX(sortKey) FROM emails WHERE accountId = :accountId " +
            "AND mailboxId = :mailboxId AND COALESCE(threadId, id) = COALESCE(e.threadId, e.id)) " +
            "ORDER BY sortKey ASC LIMIT 1",
    )
    suspend fun oldestRepresentativeEmailId(accountId: String, mailboxId: String): String?

    /** Cached thread-representative row count for the mailbox (the collapsed list's length). */
    @Query(
        "SELECT COUNT(*) FROM emails e WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND sortKey = (SELECT MAX(sortKey) FROM emails WHERE accountId = :accountId " +
            "AND mailboxId = :mailboxId AND COALESCE(threadId, id) = COALESCE(e.threadId, e.id))",
    )
    suspend fun representativeCountForMailbox(accountId: String, mailboxId: String): Int

    /** All cached ids in one account's mailbox, for cache eviction. NOT what "Select all" reads:
     *  the list on screen is filtered, and an unfiltered read of the folder is #126 — see
     *  [keysForSelection]. */
    @Query("SELECT id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun idsForMailbox(accountId: String, mailboxId: String): List<String>

    /** The (account, id) keys "Select all" may take, built by MailRepository.selectionIdsQuery
     *  from the same WHERE clause the flat list pages with. */
    @RawQuery
    suspend fun keysForSelection(query: SupportSQLiteQuery): List<EmailKeyRow>

    /** Cached rows by id across accounts (unified-view selections; callers disambiguate
     *  by the returned rows' accountId). */
    @Query("SELECT * FROM emails WHERE id IN (:ids)")
    suspend fun emailsByIds(ids: List<String>): List<EmailEntity>

    /** Cached rows by id within one account (bulk actions running under its credentials). */
    @Query("SELECT * FROM emails WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun emailsByIds(accountId: String, ids: List<String>): List<EmailEntity>

    /**
     * The IMAP numbering each of [ids] was read under, for a caller that freezes it and opposes it
     * much later (the held-back destroy, #99). Per row, never per folder: the folder's record is
     * realigned by any background pass that meets the renumbering. An id with no row comes back
     * missing, not null-valued: "nothing to oppose".
     */
    @Query("SELECT id, uidValidity FROM emails WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun numberingOfRows(accountId: String, ids: List<String>): List<RowNumbering>

    /** One account's cached members of the given threads filed under [mailboxId] (#50).
     *  Thread-less rows (NULL threadId) never match. */
    @Query("SELECT * FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId AND threadId IN (:threadIds)")
    suspend fun threadMembersInMailbox(accountId: String, mailboxId: String, threadIds: List<String>): List<EmailEntity>

    /**
     * All cached messages of one thread, newest first, scoped to one account and [mailboxIds] (the
     * view's mailboxes plus Sent), so an expanded conversation never shows another folder's
     * members. [threadKey] is COALESCE(threadId, id). Observed, like the collapsed row's chip: a
     * one-shot read left the unfolded messages frozen while the chip stayed live.
     */
    @Query(
        "SELECT * FROM emails WHERE accountId = :accountId AND mailboxId IN (:mailboxIds) " +
            "AND COALESCE(threadId, id) = :threadKey ORDER BY sortKey DESC",
    )
    fun cachedThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): Flow<List<EmailEntity>>

    /**
     * Per-folder count of unread THREADS (the conversation-mode drawer badge). Mirrors the
     * collapsed list's sub-query `g` in MailRepository.conversationSql exactly, so the badge equals
     * the visible bold rows by construction. The snooze filter correlates on accountId (#31).
     */
    @Query(
        "SELECT accountId, mailboxId, COUNT(*) AS count FROM (" +
            "SELECT accountId, mailboxId, COALESCE(threadId, id) AS tk FROM emails " +
            "WHERE NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = emails.id " +
            "AND snoozed.accountId = emails.accountId AND snoozed.until > " +
            "(CAST(strftime('%s','now') AS INTEGER) * 1000)) " +
            "GROUP BY accountId, mailboxId, tk HAVING MIN(seen) = 0" +
            ") GROUP BY accountId, mailboxId",
    )
    fun observeThreadUnreadCounts(): Flow<List<MailboxUnread>>

    /**
     * Per-folder count of unread MESSAGES (the flat-mode drawer badge), with the flat list's
     * not-snoozed filter. Reactive, as [observeThreadUnreadCounts].
     */
    @Query(
        "SELECT accountId, mailboxId, COUNT(*) AS count FROM emails " +
            "WHERE seen = 0 AND NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = emails.id " +
            "AND snoozed.accountId = emails.accountId AND snoozed.until > " +
            "(CAST(strftime('%s','now') AS INTEGER) * 1000)) " +
            "GROUP BY accountId, mailboxId",
    )
    fun observeMessageUnreadCounts(): Flow<List<MailboxUnread>>

    /** Cached-message count per account, for the storage usage breakdown. */
    @Query("SELECT accountId, COUNT(*) AS messageCount FROM emails GROUP BY accountId")
    suspend fun countsByAccount(): List<AccountMessageCount>

    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    @Query("DELETE FROM emails")
    suspend fun deleteAll()

    @Query("DELETE FROM emails WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /**
     * Age (and id) of every cached row in one account's mailbox — the input the retention prune
     * decides on. No `DELETE … WHERE sortKey < cutoff` counterpart (#110): a statement that only
     * knows the cutoff cannot know the page the server just returned is inside it, and it deleted
     * the freshly-synced folder out from under the list.
     */
    @Query("SELECT id, sortKey FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun retentionRows(accountId: String, mailboxId: String): List<EmailRetentionRow>

    /**
     * Replace one account's cached contents of a mailbox with a fresh snapshot. [spareIds] survive
     * the prune even when the fresh page omits them (see [deleteNotInSparing]).
     *
     * The eviction is decided in Kotlin and applied by id in batches, not by handing the whole
     * page to a `NOT IN (:keepIds)`: SQLite binds at most 999 variables below Android 12 (#29). The
     * bound is [app.sterna.core.data.mail.MAX_CHANGES], never the sync window, which is a setting.
     */
    @Transaction
    suspend fun replaceMailbox(
        accountId: String,
        mailboxId: String,
        emails: List<EmailEntity>,
        spareIds: List<String> = emptyList(),
    ) {
        upsertAll(emails)
        reconcileMailboxRows(accountId, mailboxId, emails.mapTo(HashSet()) { it.id }, spareIds, evictableIds = null)
    }

    /**
     * The second half of [replaceMailbox] alone: hold nothing beyond [keepIds] (and [spareIds])
     * without writing first — for the full-query sync through
     * [app.sterna.core.data.mail.fullQueryWriteThrough], which writes pages as they land and can no
     * longer name the whole snapshot at write time.
     *
     * It deletes on its own, so the caller owes two things: [keepIds] must cover the whole walk,
     * not its last page, and the walk must have run to completion. `fullQueryWriteThrough` enforces
     * both; nothing here can check them.
     */
    @Transaction
    suspend fun reconcileMailbox(
        accountId: String,
        mailboxId: String,
        keepIds: Set<String>,
        spareIds: List<String> = emptyList(),
        evictableIds: Set<String>?,
    ) {
        reconcileMailboxRows(accountId, mailboxId, keepIds, spareIds, evictableIds)
    }

    /**
     * The reconcile itself — read the folder, decide the complement once against the whole [keepIds],
     * evict by batches. Not annotated `@Transaction`, deliberately: both entry points carry it and
     * Room wraps such a body in `withTransaction`, so calling one from the other would nest
     * transactions on the one path whose failure mode is deleted mail.
     *
     * [evictableIds] bounds what may be deleted — the folder as the caller saw it before its walk;
     * null means no bound and is not `emptySet()`, which evicts nothing. Applied here because this
     * body re-reads the folder after the walk (#165).
     */
    suspend fun reconcileMailboxRows(
        accountId: String,
        mailboxId: String,
        keepIds: Set<String>,
        spareIds: List<String>,
        evictableIds: Set<String>?,
    ) {
        reconcileEvictions(
            cachedIds = evictableCachedIds(idsForMailbox(accountId, mailboxId), evictableIds),
            keepIds = keepIds,
            spareIds = spareIds.toHashSet(),
        ).forEach { batch -> evictFromCacheKeepingIndex(accountId, batch) }
    }
}

/**
 * Projection for [EmailDao.retentionRows]. [sortKey] is epoch millis, and 0 on a message whose date
 * could not be parsed — undated, not ancient, and never evicted on age.
 */
data class EmailRetentionRow(
    val id: String,
    val sortKey: Long,
)

/**
 * Projection for [EmailDao.numberingOfRows]. [uidValidity] is null for a row cached before schema
 * v25 and for every JMAP row — "nothing to oppose", which destroys nothing.
 */
data class RowNumbering(
    val id: String,
    val uidValidity: Long?,
)

/**
 * Projection for [EmailDao.cachedPreviews]. [preview] is non-null because the query selects only
 * rows whose column is not null: "no opening line" is an absent entry here, never a blank one.
 */
data class CachedPreview(
    val id: String,
    val preview: String,
)

/** Projection for [EmailDao.keysForSelection]: the account-qualified key of one selectable row. */
data class EmailKeyRow(
    val accountId: String,
    val id: String,
)

/**
 * Projection for [EmailDao.senderVolumes]. [email] is the address as stored, [name] the display
 * name of the most recent message. [total] and [unread] count MESSAGES, not conversations, and
 * `seen` is set in bulk by "mark all read", so "unread" never means "never opened".
 */
data class SenderVolumeRow(
    val email: String,
    val name: String?,
    val total: Int,
    val unread: Int,
    val latest: Long,
)

/** Projection for [EmailDao.countsByAccount]. */
data class AccountMessageCount(
    val accountId: String,
    val messageCount: Int,
)

/** Per-(account, folder) unread aggregate for the drawer badge (see [EmailDao.observeThreadUnreadCounts]). */
data class MailboxUnread(
    val accountId: String,
    val mailboxId: String,
    val count: Int,
)
