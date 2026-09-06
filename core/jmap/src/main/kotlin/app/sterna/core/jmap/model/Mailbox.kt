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
        /** Unread count shown as the drawer badge. For JMAP accounts a LIVE local aggregate over the
         *  cached `emails` table, mode-appropriate and folder-scoped exactly like the collapsed list,
         *  so the badge equals the visible bold rows; IMAP accounts (partial cache) fall back to the
         *  stored server counter. Not a server field. Distinct from [unreadEmails]. */
    val unreadForList: Int = 0,
        /**
         * Whether the server reports this mailbox as subscribed (RFC 8621 §2, Codeberg #174).
         * The `true` default is load-bearing: `ignoreUnknownKeys` forgives a property the SERVER
         * sends and we do not know, and nothing about one WE declare and the server omits, where a
         * non-defaulted field throws and `decodeList` turns that into a failed `Mailbox/get` — no
         * drawer at all. With the default such a server reports every folder subscribed, which is what
         * "nobody said" has to mean: the unknown never hides a folder.
         */
    val isSubscribed: Boolean = true,
        /**
         * Whether this folder CLAIMED a role and another folder of the account was elected to it — an
         * IMAP account with both a `Junk` and a `Spam`, or a personal `Trash` beside a shared one.
         *
         * This is not a role and must never be read as one: [role] is null on such a folder, on
         * purpose, and it is an ordinary folder everywhere. This boolean carries the ONE fact that
         * survives the erasure, and it has exactly one reader — `ui.inbox.visibleFolders`, which keeps
         * the folder listed when an account shows only its subscribed folders (#174). Without it a
         * losing `Spam` left the drawer, both move pickers and the unread view at once while still
         * being excluded from search, its mail unreachable from the app. `false` for every JMAP
         * account, JMAP naming the role on the mailbox and holding no election. The `false` default
         * is load-bearing for the same reason [isSubscribed]'s `true` is.
         */
    val lostRoleClaim: Boolean = false,
)
