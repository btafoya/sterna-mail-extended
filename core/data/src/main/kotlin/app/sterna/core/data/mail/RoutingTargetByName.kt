package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox

/**
 * Can one of the app's actions pick [mailbox] as a destination on the strength of its NAME alone?
 *
 * ⭐ The invariant, in one function: **a folder the app is willing to file mail into stays visible.**
 * The bench found the opposite on 2026-08-25 — Stalwart answers `Archive` with `role: null` AND
 * `isSubscribed: false`, so "show subscribed folders only" took it out of the drawer and out of the
 * move picker while the Archive button kept filing mail into it. Archived mail became unreachable
 * from the app. That is K-9's `thunderbird-android#2615`, on our own server.
 *
 * ⛔ It answers by RUNNING the very elections the routing runs ([archiveFolderByName],
 * [trashFolderByName]) on a list holding [mailbox] alone, instead of re-reading their tables. There
 * is therefore no second copy of the names to drift, and no way to widen what the app files into
 * without widening what it shows.
 *
 * ⛔ Only archive and trash. On JMAP they are the only two destinations with a by-name fallback:
 * sent and drafts read `rolesToMailboxId[…]` strictly and file nothing without a role, junk is
 * never a write target. Naming more folders here would not fix a hiding bug, it would hide the
 * absence of one.
 *
 * ⚠ This is a VISIBILITY question, never a routing one: the caller is `visibleFolders`. Nothing
 * routes through it, so a `true` here can move no mail.
 */
fun isRoutingTargetByName(mailbox: Mailbox): Boolean {
    val alone = listOf(mailbox)
    return archiveFolderByName(alone) != null || trashFolderByName(alone) != null
}
