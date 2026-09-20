package app.sterna.ui.settings

import app.sterna.core.data.account.SPECIAL_FOLDER_ROLES
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.inbox.mailboxRoleNameRes
import app.sterna.ui.inbox.mailboxTree
import app.sterna.ui.inbox.visibleFolders

/**
 * What the five special-folder lines of the folder screen offer and what a tap on one writes
 * (#227), decided here so a JVM test can run it: nothing in this file composes.
 */

/** One line: a role, what it says under the role's name, and everything its picker offers. */
internal data class SpecialFolderRow(
    /** One of [SPECIAL_FOLDER_ROLES]. */
    val role: String,
    val label: SpecialFolderLabel,
    val options: List<SpecialFolderOption>,
)

/**
 * What a line says under the role's own name. Decided here rather than in the composable because it
 * is the one thing on the line the user reads to know whether her choice took: a line that prints a
 * designated folder as a detected one says the setting did nothing.
 *
 * Both names are the SERVER's. The role's translated name would print the same word for the two
 * folders this screen exists to tell apart.
 */
internal sealed interface SpecialFolderLabel {
    /** No folder carries the role at all: nothing designated, nothing elected. */
    data object None : SpecialFolderLabel

    /** The account designated this folder and the listing carries it. */
    data class Chosen(val name: String) : SpecialFolderLabel

    /** Nobody designated anything; this is the folder the listing elected by itself. */
    data class Detected(val name: String) : SpecialFolderLabel
}

/** One entry of a role's picker. */
internal data class SpecialFolderOption(
    /** null is the "Automatic" entry, which hands the role back to the server's own election. */
    val folderId: String?,
    /** The name the SERVER gave this folder, never the role's translated name: an account with a
     *  `Junk` and a `Spam` would otherwise offer "Spam" twice and be designated at random. */
    val name: String,
    /** Its depth in the folder tree: the entries keep the drawer's own order, so a folder reads
     *  under the parent it hangs from. Two leaves of one name under two parents share a depth, and
     *  the order is what tells them apart. */
    val depth: Int,
    /** The role this folder carries today when that is not the role being chosen: designating it
     *  takes that role away from it, and that consequence has to be readable before the tap. Only a
     *  role the app can name: a JMAP role is the server's own string and RFC 8621 §2 allows any, so
     *  the rest would print a raw server token inside a translated sentence. */
    val heldRole: String?,
    val selected: Boolean,
)

/** The write a tap asks for, or null for one the data layer would drop on the floor. */
internal data class SpecialFolderWrite(val role: String, val folderId: String?)

/**
 * The five lines for one account, from the folders its own screens list ([visibleFolders]) and
 * [chosen], its stored choices. [mailboxes] carry the effective role, the choice already applied to
 * the cache, which is why the folder a line names is read off the listing and not off [chosen]: a
 * choice whose folder the listing no longer carries is not what the app files mail into today.
 *
 * [idsArePaths] is IMAP, where a folder id is a path.
 */
internal fun specialFolderRows(
    mailboxes: List<Mailbox>,
    showOnlySubscribed: Boolean,
    chosen: Map<String, String>,
    inboxId: String?,
    idsArePaths: Boolean,
): List<SpecialFolderRow> {
    val listed = visibleFolders(mailboxes, showOnlySubscribed)
    val targets = mailboxTree(listed, collapsed = emptySet())
        .filterNot { isInboxTarget(it.mailbox, inboxId, idsArePaths) }
    val targetIds = targets.mapTo(mutableSetOf()) { it.mailbox.id }
    return SPECIAL_FOLDER_ROLES.map { role ->
        // Only a choice this listing can honour, the guard `honouredSpecialFolders` applies on the
        // other side: a role whose folder is gone reads as automatic here because that is what the
        // app does with it, and the stored choice stays, its folder may come back.
        val choice = chosen[role]?.takeIf { it in targetIds }
        val chosenName = listed.firstOrNull { it.id == choice }?.name
        val detected = listed.firstOrNull { it.role == role }?.name
        SpecialFolderRow(
            role = role,
            label = when {
                chosenName != null -> SpecialFolderLabel.Chosen(chosenName)
                detected != null -> SpecialFolderLabel.Detected(detected)
                else -> SpecialFolderLabel.None
            },
            options = listOf(
                SpecialFolderOption(folderId = null, name = "", depth = 0, heldRole = null, selected = choice == null),
            ) + targets.map { node ->
                SpecialFolderOption(
                    folderId = node.mailbox.id,
                    name = node.mailbox.name,
                    depth = node.depth,
                    heldRole = node.mailbox.role?.takeIf { it != role && mailboxRoleNameRes(it) != null },
                    selected = node.mailbox.id == choice,
                )
            },
        )
    }
}

/**
 * Whether no role may be moved onto [mailbox]: a cache row carries one role, so the account would
 * be left with no folder answering its inbox, and under `trash` or `junk` the inbox would drop out
 * of every search result. Recognised as both listings recognise it, by the role the listing settled
 * and, where ids are paths, by the one RFC 3501 §5.1 reserves: a server announcing `\Archive` on
 * INBOX takes it out of the role election, so the role alone is no guard.
 */
private fun isInboxTarget(mailbox: Mailbox, inboxId: String?, idsArePaths: Boolean): Boolean =
    mailbox.role == "inbox" ||
        mailbox.id == inboxId ||
        (idsArePaths && mailbox.id.equals("INBOX", ignoreCase = true))

/**
 * The write behind a tap on [folderId] for [role], null when [role] is not one a folder may be
 * pinned to. The guard belongs to the caller: `MailFolders.setSpecialFolder` stores whatever role
 * it is handed, and a key outside [SPECIAL_FOLDER_ROLES] is one every reader of the choice filters
 * back out, a setting that takes the tap and does nothing.
 *
 * It is that door the write goes through, never `AccountStore.setSpecialFolder`, which is the
 * record alone: the drawer, the search and the delete all read the role off the folder cache, and
 * a choice left to the next sync is a setting the app visibly ignores.
 */
internal fun specialFolderWrite(role: String, folderId: String?): SpecialFolderWrite? =
    if (role in SPECIAL_FOLDER_ROLES) SpecialFolderWrite(role, folderId) else null
