package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxEntity

/**
 * [listed] as the folder cache may keep it: a row whose id is outside [stated] was given no unread
 * count by the server and carries a placeholder, so it takes the count in [cached], 0 for a folder
 * the cache never held. `MailboxDao.replaceAllKeepingUnread` reads [cached] in the transaction that
 * writes, which is what lets a count nudged by `MailboxDao.adjustCounts` meanwhile be the one kept.
 */
internal fun keepingUnstatedUnread(
    listed: List<MailboxEntity>,
    stated: Set<String>,
    cached: Map<String, Int>,
): List<MailboxEntity> =
    listed.map { if (it.id in stated) it else it.copy(unreadEmails = cached[it.id] ?: 0) }
