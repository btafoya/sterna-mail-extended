package app.sterna.core.data.account

/** The moves behind the user's account order, kept out of [AccountStore] for a JVM test. The order
 *  is the one of the stored list itself: there is no rank field to fall out of step with it. */

/**
 * The accounts screen's blocks, in the order it shows them: each login followed by its shared
 * mailboxes, then each shared mailbox whose login is gone, alone. A record still waiting for its
 * sign-in (`importPending`) is in none. [StoredAccount.accountsScreenRows] draws these, so a rank
 * counted here is the rank the screen shows.
 */
internal fun accountBlocks(all: List<StoredAccount>): List<List<StoredAccount>> {
    val visible = all.filter { !it.importPending }
    val delegates = visible.filter { it.isShared }
    val logins = visible.filter { !it.isShared }.map { login ->
        listOf(login) + delegates.filter { it.loginId == login.id }
    }
    val hung = logins.flatMap { it.drop(1) }.map { it.id }.toSet()
    return logins + delegates.filterNot { it.id in hung }.map { listOf(it) }
}

/** [accounts] with the block headed by [id] moved one place up or down among the blocks, or
 *  [accounts] itself when it cannot move (first going up, last going down, [id] heading no block). */
internal fun withAccountMoved(accounts: List<StoredAccount>, id: String, up: Boolean): List<StoredAccount> {
    val blocks = accountBlocks(accounts)
    val from = blocks.indexOfFirst { it.first().id == id }
    return withBlockMoved(accounts, blocks, from, if (up) from - 1 else from + 1)
}

/** [accounts] with the block headed by [id] dropped at [rank] among the blocks, as drawn, or
 *  [accounts] itself when nothing moves (dropped where it stood, a rank out of range, [id] heading
 *  no block). */
internal fun withAccountDropped(accounts: List<StoredAccount>, id: String, rank: Int): List<StoredAccount> {
    val blocks = accountBlocks(accounts)
    return withBlockMoved(accounts, blocks, blocks.indexOfFirst { it.first().id == id }, rank)
}

/**
 * [accounts] with block [from] of [blocks] moved to [to], every block written contiguous, login
 * first. The blocks are poured back into the slots the visible records held, so a record in no
 * block keeps its index: rebuilding from the blocks alone would drop every account still waiting
 * for its sign-in.
 */
private fun withBlockMoved(
    accounts: List<StoredAccount>,
    blocks: List<List<StoredAccount>>,
    from: Int,
    to: Int,
): List<StoredAccount> {
    if (from < 0 || to !in blocks.indices || to == from) return accounts
    // The screen draws every orphan after every login: a move across that line would be undone at
    // the next read, a write with nothing to show for it. Logins precede orphans in `blocks`, so
    // the kind of the block landed on decides for a drop of any distance too.
    if (blocks[to].first().isShared != blocks[from].first().isShared) return accounts
    val placed = blocks.toMutableList().apply { add(to, removeAt(from)) }.flatten()
    // A duplicated id puts a record in two blocks: poured back, they would overrun the places and
    // drop an account off the end of the blob every account lives in.
    if (placed.size != accounts.count { !it.importPending }) return accounts
    val next = placed.iterator()
    return accounts.map { if (it.importPending) it else next.next() }
}
