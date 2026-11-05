package app.sterna.core.data.mail

/**
 * The UIDs of [candidates] that may be put to [mailboxId]'s SELECT, each keyed back to its id. A UID
 * is a number in one folder of one account: asked in another, it names another message, and the
 * answer would take down the banner of mail that never left.
 */
internal fun imapDepartureUids(candidates: List<String>, accountId: String, mailboxId: String): Map<Long, String> =
    candidates.mapNotNull { id ->
        val (mailbox, uid) = ImapMailService.targetOf(id) ?: return@mapNotNull null
        if (mailbox != mailboxId || ImapMailService.accountOf(id) != accountId) null else uid to id
    }.toMap()
