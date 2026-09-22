package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.siblingsInFolderOrder
import app.sterna.core.jmap.model.Mailbox

/**
 * One parent's children as every folder list draws them: the folders [folderRank] places ahead of
 * the rest first, in that rank, out of reach of [order]; then the others by [siblingsInFolderOrder].
 * A folder the user pinned to a role is in the first group: the pin reaches [Mailbox.role] in the
 * folder cache, before any screen reads it.
 */
internal fun siblingsInDrawerOrder(siblings: List<Mailbox>, order: List<String>): List<Mailbox> {
    val (standard, own) = siblings.sortedBy { folderRank(it.role) }
        .partition { folderRank(it.role) < folderRank(null) }
    val pending = own.groupBy { it.id }.mapValues { (_, same) -> ArrayDeque(same) }
    return standard + siblingsInFolderOrder(order, own.map { it.id }).map { pending.getValue(it).removeFirst() }
}

/**
 * [own], a picker's folders already sorted by path, with each fratrie put in [order] by
 * [siblingsInFolderOrder] and every subtree kept whole under its parent. A fratrie is keyed by path
 * segments, not by listed parents: a picker drops the folder the message is in, and an IMAP
 * `\Noselect` parent is never listed, yet both still head their children.
 */
internal fun pickerFoldersInOrder(
    own: List<Pair<Mailbox, List<String>>>,
    idOfPath: Map<List<String>, String>,
    order: List<String>,
): List<Mailbox> {
    fun walk(prefix: List<String>, group: List<Pair<Mailbox, List<String>>>): List<Mailbox> {
        val bySegment = group.groupBy { it.second[prefix.size] }
        val segmentOf = bySegment.keys.associateBy { idOfPath[prefix + it] ?: UNLISTED + (prefix + it).joinToString(UNLISTED) }
        return siblingsInFolderOrder(order, segmentOf.keys.toList()).flatMap { key ->
            val path = prefix + segmentOf.getValue(key)
            val (here, below) = bySegment.getValue(path.last()).partition { it.second.size == path.size }
            here.map { it.first } + walk(path, below)
        }
    }
    return walk(emptyList(), own)
}

/** Key of a path no listed folder answers. A folder id never holds a NUL, so no stored order ranks it. */
private const val UNLISTED = "\u0000"

/** One account's folder order, read off ONE record, and the account it is that record's. */
internal data class FolderOrdering(val accountId: String?, val ids: List<String>) {
    /** This order when the list it sorts is [listedAccountId]'s, none otherwise: the pairing trap
     *  `FolderHiding.forList` guards, a new registry meeting the previous account's list. */
    fun forList(listedAccountId: String?): FolderOrdering =
        if (accountId == listedAccountId) this else FolderOrdering(listedAccountId, emptyList())
}

/** What [accountId]'s own record orders. An id resolving to no record orders nothing. */
internal fun folderOrderingOf(accountId: String?, accounts: List<StoredAccount>): FolderOrdering =
    FolderOrdering(accountId, accounts.firstOrNull { it.id == accountId }?.folderOrder ?: emptyList())
