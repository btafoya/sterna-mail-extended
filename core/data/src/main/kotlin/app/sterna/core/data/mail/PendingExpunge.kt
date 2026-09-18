package app.sterna.core.data.mail

import app.sterna.core.data.db.PendingExpungeDao
import app.sterna.core.data.db.PendingExpungeEntity
import app.sterna.core.imap.ImapPurge
import app.sterna.core.imap.ImapSession

/**
 * The UIDs Sterna set out to flag `\Deleted` in a folder of a server without UIDPLUS and has not
 * seen erased, seen from [ImapMailService]. They outlive the operation that flagged them so a later
 * purge can count them as its own, see `ImapSession.delete`.
 *
 * Keyed by UIDVALIDITY: a UID is never reused under one numbering, so a remembered UID still names
 * a message Sterna meant to purge; after a renumbering the same UID names another message, possibly
 * one another client flagged, and must not count. The accepted residue: a UID remembered for an
 * operation that failed before its `UID STORE`, then flagged by another client, is purged with ours.
 */
interface PendingExpungeStore {

    suspend fun remembered(accountId: String, mailboxId: String, uidValidity: Long): Set<Long>

    suspend fun remember(accountId: String, mailboxId: String, uidValidity: Long, uids: Collection<Long>)

    suspend fun forget(accountId: String, mailboxId: String, uidValidity: Long, uids: Collection<Long>)

    /** Every numbering of the folder: what a renumbering invalidates. */
    suspend fun forgetMailbox(accountId: String, mailboxId: String)

    suspend fun forgetAccount(accountId: String)

    object None : PendingExpungeStore {
        override suspend fun remembered(accountId: String, mailboxId: String, uidValidity: Long): Set<Long> = emptySet()
        override suspend fun remember(accountId: String, mailboxId: String, uidValidity: Long, uids: Collection<Long>) = Unit
        override suspend fun forget(accountId: String, mailboxId: String, uidValidity: Long, uids: Collection<Long>) = Unit
        override suspend fun forgetMailbox(accountId: String, mailboxId: String) = Unit
        override suspend fun forgetAccount(accountId: String) = Unit
    }
}

class MailboxPendingExpungeStore(private val dao: PendingExpungeDao) : PendingExpungeStore {

    override suspend fun remembered(accountId: String, mailboxId: String, uidValidity: Long): Set<Long> =
        dao.remembered(accountId, mailboxId, uidValidity).toSet()

    override suspend fun remember(accountId: String, mailboxId: String, uidValidity: Long, uids: Collection<Long>) {
        if (uidValidity <= 0L || uids.isEmpty()) return
        dao.remember(uids.distinct().map { PendingExpungeEntity(accountId, mailboxId, uidValidity, it) })
    }

    override suspend fun forget(accountId: String, mailboxId: String, uidValidity: Long, uids: Collection<Long>) {
        // Chunked under SQLite's historical limit of 999 bound parameters per statement.
        uids.distinct().chunked(FORGET_CHUNK).forEach { dao.forget(accountId, mailboxId, uidValidity, it) }
    }

    override suspend fun forgetMailbox(accountId: String, mailboxId: String) = dao.forgetMailbox(accountId, mailboxId)

    override suspend fun forgetAccount(accountId: String) = dao.deleteForAccount(accountId)

    private companion object {
        const val FORGET_CHUNK = 900
    }
}

/**
 * Runs a purging [act] on a server without UIDPLUS, handing it what this store holds for the folder
 * under [uidValidity]. [targets] are written down before [act] runs: a process killed between the
 * `UID STORE` and the `EXPUNGE` leaves them flagged on the server, and a record written after the
 * call would never be written at all; the over-approximation is the residue named on
 * [PendingExpungeStore]. A failure keeps the record whole; an `EXPUNGE` answered OK settles everything the purge counted as ours.
 *
 * UIDPLUS is read from what the session already knows, never asked: a `CAPABILITY` sent ahead of a
 * move fills the cache its copy fallback probes to hear whether the socket survived a refusal. A
 * record written for a UIDPLUS server not yet known as one is settled by its purge when it
 * succeeds, and never read on that server when it does not.
 */
internal suspend fun <T> PendingExpungeStore.purging(
    session: ImapSession,
    accountId: String,
    mailboxId: String,
    uidValidity: Long,
    targets: List<Long>,
    purgeOf: (T) -> ImapPurge,
    act: (flaggedEarlier: Set<Long>) -> T,
): T {
    if (uidValidity <= 0L || session.purgesByUidIfKnown() == true) return act(emptySet())
    remember(accountId, mailboxId, uidValidity, targets)
    val ours = remembered(accountId, mailboxId, uidValidity)
    val result = act(ours)
    val purge = purgeOf(result)
    val settled = if (purge.expunged) ours else targets.toSet() - purge.leftFlagged
    forget(accountId, mailboxId, uidValidity, settled)
    return result
}
