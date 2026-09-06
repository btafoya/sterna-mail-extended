package app.sterna.core.data.storage

/**
 * Which cached accounts no longer exist — the decision behind
 * [StorageRepository.purgeOrphanedAccounts], kept pure so the guard below can be tested without a
 * database (#121).
 *
 * An account id is a locally minted UUID; a mailbox id comes from the server. Removing an account
 * and adding it back gives the same mail a NEW account id while the old rows keep the old one,
 * orphaned — and nothing syncs a row no account covers, so it can never be marked read, never
 * disappear and never be pruned.
 */
object OrphanedAccountCache {

    /**
     * The cached account ids in [cachedAccountIds] that [knownAccountIds] does not contain. Order is
     * the caller's, and duplicates are dropped.
     *
     * AN EMPTY [knownAccountIds] PURGES NOTHING — the whole reason this is a function and not a
     * `DELETE … WHERE accountId NOT IN (…)`. "No accounts" is also what `AccountStore.accounts()`
     * returns when the stored JSON fails to decode, and treating that as "every cached row is an
     * orphan" would delete a healthy install's entire cache on one bad read.
     */
    fun orphans(knownAccountIds: Collection<String>, cachedAccountIds: Collection<String>): List<String> {
        if (knownAccountIds.isEmpty()) return emptyList()
        val known = knownAccountIds.toSet()
        return cachedAccountIds.distinct().filterNot { it in known }
    }
}
