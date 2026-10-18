package app.sterna.core.data.mail

import java.util.concurrent.ConcurrentHashMap

/**
 * The folders an IMAP account still holds that a fresh listing no longer carries, what
 * `MailboxDao.replaceAllKeepingUnread` drops with `MailboxDao.dropFolderContents` (#255). Only the
 * IMAP listing writes through that function; JMAP goes through `MailboxDao.replaceAll`, which keeps
 * the rows. [held] is scoped to one account: the folder rows known before the listing, the folders of
 * the cached rows, and once per process those of the index ([LegacyIndexSweep]).
 *
 * A folder hidden from the drawer is still listed, and so is one not cached yet: neither is here.
 */
internal fun vanishedImapFolders(held: Collection<String>, listed: Collection<String>): List<String> {
    if (!isWholeImapListing(listed)) return emptyList()
    val kept = listed.toHashSet()
    return held.distinct().filter { it !in kept }
}

/** Every IMAP account has an INBOX, so a listing without one is a truncated answer, not a server
 *  that destroyed everything: taken at its word it would empty the account's cache and index. */
internal fun isWholeImapListing(listed: Collection<String>): Boolean =
    listed.any { it.equals("INBOX", ignoreCase = true) }

/**
 * The accounts whose index was already read for vanished folders in this process. That read scans
 * every row of `email_fts`, whose columns carry no index, inside the listing's write: once is enough
 * to find a folder that vanished before its `mailboxes` row could name it, every later one being in
 * the folder rows or the cache. A claim whose transaction rolls back is lost until the next start.
 */
internal object LegacyIndexSweep {
    private val swept: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun claim(accountId: String): Boolean = swept.add(accountId)
}
