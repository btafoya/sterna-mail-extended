package app.sterna.core.data.mail

/** Whether deleting a message destroys it rather than moving it to the bin: is it already in the
 *  Trash? An absent Trash answers false, not true — `delete()` creates the folder when nothing
 *  answers, so answering true would route the swipe to the held-back destroy. An unknown
 *  [messageMailboxId] (a search hit) answers false too, or the two nulls would meet in the equality
 *  below. The two guards are redundant on purpose: no single test tells them apart. */
fun deleteDestroysMessage(trashMailboxId: String?, messageMailboxId: String?): Boolean {
    if (trashMailboxId.isNullOrBlank()) return false
    if (messageMailboxId.isNullOrBlank()) return false
    return messageMailboxId == trashMailboxId
}
