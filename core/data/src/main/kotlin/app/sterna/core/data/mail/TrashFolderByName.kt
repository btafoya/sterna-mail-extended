package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox

/**
 * Lowercased folder names that denote a bin, compared with `lowercase()`.
 *
 * These are the names SERVERS carry — what Dovecot, Roundcube, Thunderbird and the common webmails
 * create in each language — never the app's own labels: the mailbox is named once, on the server,
 * and the phone's language has no say in reading it. The list is the `-> "trash"` arm of
 * `ImapClient.roleFromName`, which is this repo's reference table for the nine locales, plus the
 * spellings that table does not carry (`bin`, `çöp`, `削除済み`, `papperskorg`, `koš`).
 *
 * ⛔ It may only ever SHRINK by accident, never grow into a folder the user reads: a name that also
 * denotes an inbox, a sent folder, an archive, drafts or spam turns "delete" into "move somewhere
 * else", and into "move somewhere the server empties on a timer" for spam. [TrashFolderByNameTest]
 * runs that rule name by name.
 */
val TRASH_FOLDER_NAMES = listOf(
    "trash", "deleted", "deleted items", "deleted messages", "bin", // en (+ en-GB)
    "papierkorb", "gelöschte objekte", "gelöschte elemente", // de
    "papelera", "elementos eliminados", // es
    "corbeille", "éléments supprimés", // fr
    "cestino", "posta eliminata", "elementi eliminati", // it
    "prullenbak", "prullenmand", "verwijderde items", // nl
    "kosz", "elementy usunięte", // pl
    "lixeira", "lixo", "itens excluídos", "itens eliminados", // pt
    "корзина", "удалённые", "удаленные", // ru
    "çöp", "削除済み", "papperskorg", "koš", // beyond the app's locales, common on servers
)

/**
 * The account's Trash folder recognised BY NAME, top-level preferred — as a function a JVM test
 * EXECUTES. Null when nothing among [mailboxes] looks like a bin, which is what lets the caller
 * create one rather than move deleted mail into a folder the user reads.
 *
 * `MailRepository` needs Room, a `Context` and a socket, so a rule written inside it is a rule no
 * test ever runs. This is the whole by-name decision; the repository keeps only the role lookup and
 * the context bookkeeping around it.
 *
 * ⛔ Why it exists. `delete()` was `rolesToMailboxId["trash"] ?: error(…)`: on a JMAP account where
 * no folder carries the `trash` role, deleting threw and the user had no way at all to get rid of a
 * message. Archive has resolved-or-created on both protocols all along.
 *
 * ⚠ JMAP ONLY — the IMAP path reads names through `ImapClient.roleFromName`, which this does not
 * touch.
 *
 * ⛔ [mailboxes] must be THE ACCOUNT'S OWN folders, never the global folder cache (which holds the
 * last-synced account only): from the unified inbox, a delete on another account would otherwise
 * aim at a folder id that does not exist for it, and the move would no-op in silence (#31).
 */
fun trashFolderByName(mailboxes: List<Mailbox>): String? =
    mailboxes
        .filter { it.name.lowercase() in TRASH_FOLDER_NAMES }
        .minByOrNull { if (it.parentId == null) 0 else 1 }
        ?.id
