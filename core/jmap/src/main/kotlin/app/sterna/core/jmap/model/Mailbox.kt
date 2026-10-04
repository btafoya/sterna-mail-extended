package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

/** A JMAP Mailbox (RFC 8621 §2). Only the fields we use so far. */
@Serializable
data class Mailbox(
    val id: String,
    val name: String,
    val role: String? = null,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val totalEmails: Int = 0,
    val unreadEmails: Int = 0,
        /** The unread count the drawer shows for this folder, in messages: the one the server gave
         *  at the last refresh, adjusted by the gestures made since. Not a server field: filled
         *  from the cache row, and 0 on a [Mailbox] decoded from the wire. */
    val unreadForList: Int = 0,
        /**
         * Whether the server reports this mailbox as subscribed (RFC 8621 §2, Codeberg #174).
         */
    val isSubscribed: Boolean = true,
        /**
         * Whether this folder CLAIMED a role and another folder of the account was elected to it, an
         * IMAP account with both a `Junk` and a `Spam`, or a personal `Trash` beside a shared one
         * (`ImapClient.assignRoles`, stored as `MailRepository.UNELECTED_ROLE_MARK`).
         */
    val lostRoleClaim: Boolean = false,
)
