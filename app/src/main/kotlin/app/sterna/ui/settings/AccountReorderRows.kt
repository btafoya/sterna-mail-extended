package app.sterna.ui.settings

import app.sterna.core.data.account.AccountRowEntry
import app.sterna.core.data.account.StoredAccount
import app.sterna.ui.components.movedBlock

/**
 * One line of the accounts' reorder mode. A block is a row of [StoredAccount.accountsScreenRows]
 * that is not `underLogin`, with the indented rows under it: the unit `AccountStore.moveAccount`
 * and `AccountStore.moveAccountTo` move, and [rank] counts among. [moves] is null on a shared
 * mailbox drawn under its login, which has no place of its own.
 */
internal data class AccountReorderRow(
    val account: StoredAccount,
    val underLogin: Boolean,
    val rank: Int,
    val moves: AccountMoves?,
)

internal data class AccountMoves(val mayMoveUp: Boolean, val mayMoveDown: Boolean)

private fun blocksOf(all: List<StoredAccount>): List<List<AccountRowEntry>> {
    val blocks = mutableListOf<MutableList<AccountRowEntry>>()
    StoredAccount.accountsScreenRows(all).forEach { row ->
        if (!row.underLogin || blocks.isEmpty()) blocks.add(mutableListOf(row)) else blocks.last().add(row)
    }
    return blocks
}

/** Whether the Reorder button is offered: when one arrow of [accountReorderRows] is enabled, so a
 *  login and an orphan, two blocks the line between them keeps apart, offer none. The mode closes
 *  with the button: a share pruned by discovery can leave nothing to move while the mode is open,
 *  which would otherwise stay open with no Done to leave it by. */
internal fun mayReorderAccounts(all: List<StoredAccount>): Boolean =
    accountReorderRows(all).any { row -> row.moves?.let { it.mayMoveUp || it.mayMoveDown } == true }

/**
 * The reorder mode's lines. A block headed by a shared mailbox is an orphan, whose login is gone:
 * the screen draws every orphan after every login, so the store never moves one across that line,
 * and the arrow that would ask it to is disabled here, on both sides of the line.
 */
internal fun accountReorderRows(all: List<StoredAccount>): List<AccountReorderRow> {
    val blocks = blocksOf(all)
    fun sameSide(a: Int, b: Int) = blocks[a].first().account.isShared == blocks[b].first().account.isShared
    return blocks.flatMapIndexed { rank, block ->
        val moves = AccountMoves(
            mayMoveUp = rank > 0 && sameSide(rank, rank - 1),
            mayMoveDown = rank < blocks.lastIndex && sameSide(rank, rank + 1),
        )
        block.mapIndexed { index, row ->
            AccountReorderRow(row.account, row.underLogin, rank, moves.takeIf { index == 0 })
        }
    }
}

/** How many rows the row at [index] carries with it when dragged: itself and the shared mailboxes
 *  drawn under it. */
internal fun accountBlockSpan(rows: List<AccountReorderRow>, index: Int): Int {
    if (rows.getOrNull(index)?.moves == null) return 1
    var end = index + 1
    while (end < rows.size && rows[end].moves == null) end++
    return end - index
}

/**
 * The rank among the blocks that the block headed at [from] is written to when it lands at row
 * [to], or null when that drop is refused: nothing moves, a place between a login and its shared
 * mailboxes, or one across the line between logins and orphans. The store refuses the last one
 * too; judged here, the column never offers it while carrying.
 */
internal fun accountDrop(rows: List<AccountReorderRow>, from: Int, to: Int): Int? {
    val held = rows.getOrNull(from)?.takeIf { it.moves != null } ?: return null
    val shown = rows.movedBlock(from, accountBlockSpan(rows, from), to).map { it.account.id }
    if (shown == rows.map { it.account.id }) return null
    val blocks = rows.groupBy { it.rank }.values.toList()
    val rank = shown.filter { id -> rows.first { it.account.id == id }.moves != null }.indexOf(held.account.id)
    if (blocks[rank].first().account.isShared != held.account.isShared) return null
    val drawn = blocks.toMutableList().apply { add(rank, removeAt(held.rank)) }.flatten().map { it.account.id }
    return rank.takeIf { drawn == shown }
}
