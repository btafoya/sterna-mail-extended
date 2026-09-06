package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Cached email for a mailbox list view.
 *
 * The primary key is composite — `(accountId, id)` — because a JMAP email id is unique only
 * within its JMAP account (RFC 8620 §1.6.2). Hosting two accounts of one login (issue #31) can
 * therefore mint two rows with the same [id]; keying on [id] alone would make one account's sync
 * clobber the other's cached row. [accountId] is the client-side StoredAccount id, so sub-accounts
 * of the same login stay on separate partitions.
 *
 * Two indices, and the second one is the folder list itself. `(accountId, mailboxId, sortKey)` is
 * the three things every list query does with this table at once: the SCOPE — `folderScopeSql`
 * selects on (account, folder) PAIRS and never on a bare mailbox id (Codeberg #121), which is why
 * `accountId` comes first — then the ORDER, `sortKey DESC`, and finally the join that picks a
 * thread's representative row in the conversation view (`e.accountId = g.gacc AND e.sortKey =
 * g.maxKey`). Without it that join had to walk the ACCOUNT'S WHOLE PRIMARY KEY once per collapsed
 * thread — the access that made a folder switch cost more the more mail was cached — while the two
 * `GROUP BY` sub-queries reached the folder through `index_emails_mailboxId`, i.e. by the mailbox
 * id ALONE: every account's rows carrying that server-assigned id, in no useful order. Switching
 * folder with the conversation view on left the list blank for a second or two on mail already
 * cached, with no sync running.
 *
 * They did not re-scan the table, and it is worth not saying they did:
 * `ConversationIndexPlanSqlTest.withoutTheStepThePlanFallsBackToTheMailboxIdAlone` keeps the old
 * plan as a witness, and the two sub-queries are a SEARCH in it. On a single-account install the
 * mailbox id alone already selected exactly the folder, so what this index buys THERE is the
 * representative join and nothing else.
 *
 * The column ORDER is the whole of it: the same three columns in any other order is an index
 * that costs a write on every cached row and answers none of the three accesses. `Index("mailboxId")`
 * stays beside it — dropping it would need its own `DROP INDEX` step and buys nothing here.
 *
 * Changing this line without adding a migration step breaks UPDATED installs only, and at open
 * time: a fresh install builds its schema from here, an updated one from the `CREATE INDEX` of the
 * migration, and Room compares the two on every launch.
 */
@Entity(
    tableName = "emails",
    primaryKeys = ["accountId", "id"],
    indices = [Index("mailboxId"), Index(value = ["accountId", "mailboxId", "sortKey"])],
)
data class EmailEntity(
    val id: String,
    val accountId: String,
    val mailboxId: String,
    val threadId: String?,
    val subject: String?,
    val preview: String?,
    val receivedAt: String?,
    val fromName: String?,
    val fromEmail: String?,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
    /** Epoch millis derived from receivedAt, for ordering. */
    val sortKey: Long,
    /**
     * The message's `To:` recipients, JSON-encoded by [EmailRecipients] (added in schema v17).
     *
     * In Sent/Drafts a row shows who the mail went TO rather than its sender — your own name
     * teaches nothing (Codeberg #59). Those recipients used to live only in a process-lifetime
     * memo, so a row rendered from the cold cache fell back to the sender until the folder's
     * next refresh replaced it, which is what #63 saw blink. Persisted, the row is right from
     * the first frame, offline included.
     *
     * Null on rows cached before v17 and on messages with no recipient (a draft still being
     * written): both decode to an empty list, i.e. exactly the old fallback. There is no
     * backfill — the addresses aren't held locally, so old rows gain them at their next sync.
     */
    val recipientsJson: String? = null,
    /**
     * The message's `Reply-To:` addresses, JSON-encoded by [EmailRecipients] (added in schema v20).
     *
     * When a sender sets `Reply-To` it is saying "answer somewhere else than my `From`" — a
     * mailing list, a `no-reply@` doubled by a `support@`, a ticketing alias. The address has to
     * survive in the cache, otherwise a reply composed offline, or from the notification's quick
     * reply, has nothing but the sender to fall back on and goes to a mailbox nobody reads.
     *
     * Null on rows cached before v20 and on the (vast) majority of messages, which carry no
     * `Reply-To` at all: both decode to an empty list, i.e. "no instruction, answer the sender".
     * There is no backfill — the header is not held locally, so an old row gains it at its next
     * sync, exactly as [recipientsJson] did in v17.
     */
    val replyToJson: String? = null,
    /**
     * The message's `Cc:` recipients, JSON-encoded by [EmailRecipients] (added in schema v21).
     *
     * The people put in copy are part of the message's addressing, not decoration: reopening a
     * draft rebuilds its Cc field from the cached row, and "reply all" builds its recipient list
     * out of To + Cc. While the column did not exist the row answered "nobody in copy" to both,
     * so reopening a draft silently emptied its Cc — valid addresses included — and every reply
     * to all dropped the people in copy without a word on screen.
     *
     * Null on rows cached before v21 and on the many messages addressed to no one in copy: both
     * decode to an empty list, i.e. the pre-v21 answer, which for those messages is the true one.
     * There is no backfill — the addresses are not held locally, so an old row gains them at its
     * next sync, exactly as [recipientsJson] did in v17 and [replyToJson] in v20.
     */
    val ccJson: String? = null,
    /**
     * The message's `Bcc:` recipients, JSON-encoded by [EmailRecipients] (added in schema v21).
     *
     * Only ever populated on mail this account sent or is writing — a received message never
     * carries the blind copies. That is exactly the case that matters: a draft addressed in Bcc
     * and reopened used to come back with the field emptied and the Cc/Bcc row folded shut, so
     * the recipient was gone and nothing said so.
     *
     * A separate column from [ccJson] on purpose, and never merged with it for display: the
     * whole point of a blind copy is that it is not shown to the other recipients.
     *
     * Null on rows cached before v21 and on every message without blind copies; both decode to
     * an empty list. No backfill, for the same reason as [ccJson].
     */
    val bccJson: String? = null,
    /**
     * The IMAP numbering (UIDVALIDITY) this row was READ under — the numbering the UID inside
     * [id] belongs to, as the SELECT that enumerated the message stated it (added in schema v25,
     * Codeberg #99).
     *
     * A UID names a message only inside one numbering (RFC 3501 §2.3.1.1); under a new one the
     * same number names ANOTHER message. The folder's numbering was already recorded — per
     * FOLDER, in `mailbox_uidvalidity` — but the two were decoupled: a background pass that finds
     * the folder renumbered writes the NEW number there and leaves these rows alone (nothing
     * prunes them on IMAP, `MailRepository.refreshAccountFolders`), so a destroy that opposed the
     * folder's number opposed it to itself, read SAME, and expunged UIDs that now name live mail.
     * Stamped here, ids and numbering come out of the SAME server response and cannot drift
     * apart — the appariement `TrashPurge.snapshotUidValidity` already makes for a purge.
     *
     * Null on rows cached before v25, and null on every JMAP row: a JMAP id survives anything a
     * server can do to a mailbox, so there is nothing to number. Both mean "nothing to oppose",
     * which destroys NOTHING (`UidValidity.mayDestroy`) — the safe end. There is no backfill: the
     * numbering an old row was read under is not held anywhere, and inventing the folder's current
     * one would license exactly the expunge this column exists to refuse. An old row gains it at
     * its next sync, as [recipientsJson] did in v17.
     */
    val uidValidity: Long? = null,
)
