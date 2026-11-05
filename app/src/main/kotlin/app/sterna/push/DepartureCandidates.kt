package app.sterna.push

import app.sterna.core.data.account.MailProtocol

/**
 * What may have left a folder since its last pass. JMAP's delta names its departures; an IMAP
 * re-read names none, so there they are the [remembered] ids the folder no longer shows. A
 * remembered id outside a bounded read is alive: this is a candidate list only.
 *
 * It goes through `Notifications.departuresToCancel` before anything asks the server: on IMAP the
 * difference can run to hundreds of ids, of which a handful wear a banner, and only those are worth
 * a question.
 */
internal fun departureCandidates(
    protocol: MailProtocol,
    departedIds: List<String>,
    remembered: Collection<String>,
    held: Collection<String>,
): List<String> {
    if (protocol != MailProtocol.IMAP) return departedIds
    val shown = held.toSet()
    return departedIds + remembered.filterNot { it in shown }
}
