package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxEntity

/** The Sent folder to hand [ImapMailService.send] for its APPEND. Null when "Upload sent messages" is off, and the server is then asked nothing.
 *  On, the copy is owed: the [cached] `sent` folder, else the one [listed] carries under that role, else a literal `Sent` handed to [create].
 *  A `Sent` that [chosen] pins to another role ([lastResortImapFolder]) throws: the copy is never filed in a folder designated for something else. */
internal inline fun sentMailboxToUpload(
    uploadSentCopy: Boolean,
    cached: String?,
    chosen: Map<String, String>,
    listed: () -> List<MailboxEntity>,
    create: (String) -> Unit,
): String? {
    if (!uploadSentCopy) return null
    return cached
        ?: listed().firstOrNull { it.role == "sent" }?.id
        ?: lastResortImapFolder("sent", "Sent", chosen)?.also { create(it) }
        ?: error("No Sent folder, and the name Sent is designated for another role")
}
