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
    /**
     * Unread count shown as the drawer badge. For JMAP accounts this is a LIVE local aggregate
     * over the cached `emails` table, mode-appropriate (unread threads in conversation view,
     * unread messages in flat view) and folder-scoped exactly like the collapsed list — so the
     * badge equals the visible bold rows. For IMAP accounts (partial cache window) it falls back
     * to the stored server counter. Not a server field; filled in by the repository. Distinct
     * from [unreadEmails] (the server's stored count, kept for meta/reconciliation).
     */
    val unreadForList: Int = 0,
    /**
     * Whether the server reports this mailbox as subscribed (RFC 8621 §2, Codeberg #174).
     *
     * The `true` default is load-bearing, not a nicety. `JmapClient` decodes with
     * `ignoreUnknownKeys = true`, which forgives a property the SERVER sends and we do not know —
     * it forgives nothing about a property WE declare and the server omits, where a non-defaulted
     * field throws. `decodeList` turns that into a `JmapException`, so on a server that does not
     * report subscription the whole `Mailbox/get` would fail: no drawer at all, account unusable.
     * With the default, such a server simply reports every folder subscribed, which is what
     * "nobody said" has to mean here — the unknown never hides a folder.
     */
    val isSubscribed: Boolean = true,
    /**
     * Whether this folder CLAIMED a role and another folder of the account was elected to it — an
     * IMAP account with both a `Junk` and a `Spam`, or a personal `Trash` beside a shared one
     * (`ImapClient.assignRoles`, stored as `MailRepository.UNELECTED_ROLE_MARK`).
     *
     * **This is not a role and must never be read as one.** [role] is null on such a folder, on
     * purpose and for good (`MailboxMapper.toMailbox`): it is an ordinary folder everywhere — raw
     * name, no icon, renameable, deletable, and the destination of nothing, since every role→mailbox
     * lookup compares roles by exact equality. This boolean carries the ONE fact that survives the
     * erasure, and it has exactly one reader: `ui.inbox.visibleFolders`, which keeps such a folder
     * listed when an account shows only its subscribed folders (#174). Without it a losing `Spam`
     * left the drawer, both move pickers and the unread view at once, while still being excluded
     * from search by design — the folder holding the false positives became unreachable from the
     * app with its mail still on the server.
     *
     * Not a server field; filled in by the repository, like [unreadForList]. It is `false` for
     * every JMAP account: JMAP names the role on the mailbox and holds no election, so nothing
     * there ever loses one.
     *
     * The `false` default is load-bearing for the same reason [isSubscribed]'s `true` is: this
     * type is what `JmapClient.decodeList` decodes a `Mailbox/get` into, and a non-defaulted
     * property the server omits throws `MissingFieldException` there — which `decodeList` turns
     * into a `JmapException` and an account with no drawer at all.
     */
    val lostRoleClaim: Boolean = false,
)
