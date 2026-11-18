package app.sterna.core.data.storage

/**
 * Which cached accounts no longer exist, the decision behind
 */
object OrphanedAccountCache {

    /**
     * The cached account ids in [cachedAccountIds] that [knownAccountIds] does not contain.
     */
    fun orphans(knownAccountIds: Collection<String>, cachedAccountIds: Collection<String>): List<String> {
        if (knownAccountIds.isEmpty()) return emptyList()
        val known = knownAccountIds.toSet()
        return cachedAccountIds.distinct().filterNot { it in known }
    }

    fun sweptLine(swept: OrphanSweep): String? {
        val parts = listOfNotNull(
            swept.accountIds.takeIf { it.isNotEmpty() }
                ?.let { "the cached data of account(s) ${it.joinToString(", ")}" },
            swept.photos.takeIf { it.isNotEmpty() }
                ?.let { "${it.size} account photo file(s) no account names" },
        )
        return if (parts.isEmpty()) null else "Orphaned-cache sweep removed ${parts.joinToString(" and ")}"
    }
}
