package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox

/**
 * Lowercased folder names that denote an archive, compared with `lowercase()`.
 *
 * The twin of [TRASH_FOLDER_NAMES], and the same rule about what it holds: the names SERVERS carry
 * across Sterna's locales, never the app's own labels — the mailbox is named once, on the server,
 * and the phone's language has no say in reading it.
 *
 * It lives OUT of `MailRepository` (which needs Room, a `Context` and a socket) so that one list
 * answers both questions the app asks about an archive: where the Archive button files mail
 * ([archiveFolderByName]) and whether the folder lists may hide that folder
 * ([isRoutingTargetByName]). A second copy of these names would drift, and the day it drifted the
 * app would file mail into a folder it refuses to show (#174).
 *
 * `lowercase()` with no argument: a phone in Turkish maps 'I' onto 'ı' under a locale-sensitive
 * lowercasing and "ARCHIVE" would stop matching.
 */
val ARCHIVE_FOLDER_NAMES = listOf(
    "archive", "archives", "archived",
    "archivé", "archivés", "archiv", "archivio",
    "arquivo", "arquivos", "archief", "archiwum", "архив",
)

/**
 * The account's archive folder recognised BY NAME, top-level preferred — as a function a JVM test
 * EXECUTES. Null when nothing among [mailboxes] looks like an archive, which is what sends the
 * caller to create one.
 *
 * [mailboxes] must be THE ACCOUNT'S OWN folders, never the global folder cache (which holds the
 * last-synced account only): from the unified inbox, archiving another account's message would
 * otherwise aim at a folder id that does not exist for it, and the move would no-op in silence.
 *
 * JMAP ONLY — the IMAP path reads names through `ImapClient.roleFromName`, which this does not
 * touch.
 */
fun archiveFolderByName(mailboxes: List<Mailbox>): String? =
    mailboxes
        .filter { it.name.lowercase() in ARCHIVE_FOLDER_NAMES }
        .minByOrNull { if (it.parentId == null) 0 else 1 }
        ?.id
