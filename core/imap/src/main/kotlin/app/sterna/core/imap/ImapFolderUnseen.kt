package app.sterna.core.imap

internal const val CAP_LIST_STATUS = "LIST-STATUS"

/**
 * What [ImapSession.listFoldersWithUnseen] brings back: the listing of [ImapSession.listFolders] and
 * the unread counts the server gave along with it. A server advertising `LIST-STATUS` (RFC 5819)
 * gives them in the LIST itself, at no extra round trip; any other is asked one `STATUS` per listed
 * folder, core IMAP4rev1 and so unguarded. No failure of the counts fails the listing: an advertised
 * `LIST-STATUS` that is refused all the same falls back on the plain LIST, with no count at all.
 */
data class ImapFolderListing(
    val folders: List<ImapFolder>,
    /**
     * Unseen messages by [ImapFolder.path]. A path absent from the map is unknown, never zero: the
     * server refused, said nothing, the link failed before its turn, or it was not asked. The folder
     * the caller named as the one it selects next is never here, in either mode, its count being
     * [ImapSession.unseenCount]'s to give. Counted by the server's `UNSEEN`, which unlike that
     * method does not leave `\Deleted` messages out.
     */
    val unseen: Map<String, Int>,
)

/**
 * The `UNSEEN` of each `* STATUS` line, by mailbox path, decoded exactly as [parseListFolders]
 * decodes a LIST name: the counts are looked up by [ImapFolder.path], so a name decoded any other
 * way leaves every non-ASCII folder without its count (#101, #174). A line stating no usable
 * `UNSEEN` contributes nothing.
 */
internal fun parseStatusUnseen(untagged: List<List<Any?>>): Map<String, Int> {
    val unseen = mutableMapOf<String, Int>()
    for (resp in untagged) {
        // * STATUS "name" (UNSEEN n)
        if (resp.getOrNull(1) != "STATUS") continue
        val wire = resp.getOrNull(2) as? String ?: continue
        val items = resp.getOrNull(3) as? List<*> ?: continue
        val at = items.indexOfFirst { (it as? String).equals("UNSEEN", ignoreCase = true) }
        if (at < 0) continue
        val count = (items.getOrNull(at + 1) as? String)?.toIntOrNull()?.takeIf { it >= 0 } ?: continue
        unseen[decodeMailboxPath(wire)] = count
    }
    return unseen
}

/**
 * Every unread message of the selected folder, newest first, the whole folder and not the synced
 * window. No cap: without ESEARCH the server cannot be asked for fewer. Every untagged `SEARCH`
 * line is read, unlike [ImapSession.searchUids]: a dropped tail is unread mail the list never
 * shows. No line at all is an empty list; a refused search throws. `\Deleted` messages are named
 * here, unlike in [ImapSession.unseenCount], and dropped when fetched: a UID of this list may never
 * come back as a message.
 */
fun ImapSession.unseenUids(): List<Long> = command("UID SEARCH UNSEEN").untagged
    .filter { it.getOrNull(1) == "SEARCH" }
    .flatMap { unseenLine -> unseenLine.drop(2).mapNotNull { (it as? String)?.toLongOrNull() } }
    .sortedDescending()
