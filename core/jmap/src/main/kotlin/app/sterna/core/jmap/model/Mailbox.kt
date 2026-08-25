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
     * ⛔ The `true` default is load-bearing, not a nicety. `JmapClient` decodes with
     * `ignoreUnknownKeys = true`, which forgives a property the SERVER sends and we do not know —
     * it forgives nothing about a property WE declare and the server omits, where a non-defaulted
     * field throws. `decodeList` turns that into a `JmapException`, so on a server that does not
     * report subscription the whole `Mailbox/get` would fail: no drawer at all, account unusable.
     * With the default, such a server simply reports every folder subscribed, which is what
     * "nobody said" has to mean here — the unknown never hides a folder.
     */
    val isSubscribed: Boolean = true,
)
