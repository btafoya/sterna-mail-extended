package app.sterna.core.data.mail

import java.util.Collections

/** A [PendingExpungeStore] held in memory, the same for every connection a test opens. */
internal class InMemoryPendingExpunges : PendingExpungeStore {

    data class Key(val accountId: String, val mailboxId: String, val uidValidity: Long)

    val rows: MutableMap<Key, MutableSet<Long>> = Collections.synchronizedMap(linkedMapOf())

    val forgottenAccounts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun remembered(accountId: String, mailboxId: String, uidValidity: Long): Set<Long> =
        rows[Key(accountId, mailboxId, uidValidity)].orEmpty().toSet()

    override suspend fun remember(accountId: String, mailboxId: String, uidValidity: Long, uids: Collection<Long>) {
        rows.getOrPut(Key(accountId, mailboxId, uidValidity)) { sortedSetOf() } += uids
    }

    override suspend fun forget(accountId: String, mailboxId: String, uidValidity: Long, uids: Collection<Long>) {
        rows[Key(accountId, mailboxId, uidValidity)]?.removeAll(uids.toSet())
    }

    override suspend fun forgetMailbox(accountId: String, mailboxId: String) {
        rows.keys.removeAll { it.accountId == accountId && it.mailboxId == mailboxId }
    }

    override suspend fun forgetAccount(accountId: String) {
        forgottenAccounts += accountId
        rows.keys.removeAll { it.accountId == accountId }
    }

    fun under(accountId: String, mailboxId: String, uidValidity: Long): Set<Long> =
        rows[Key(accountId, mailboxId, uidValidity)].orEmpty().toSet()
}
