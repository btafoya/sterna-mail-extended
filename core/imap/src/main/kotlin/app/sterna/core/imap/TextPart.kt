package app.sterna.core.imap

/**
 * Where a message's readable text is, as the SERVER described it in the BODYSTRUCTURE that came
 * with the envelope — enough to fetch that one section and to decode it, without asking again.
 *
 * It is kept because the structure is ALREADY on the wire: every page of every folder walk asks
 * for `BODYSTRUCTURE` and, until now, reduced it to the single boolean [ImapMessage.hasAttachment].
 * A notification preview that had to re-ask for it would spend a second round trip per message, in
 * a background service, before it could even name the section it wants.
 *
 * [section] is the IMAP body-section path ("1", "2", "1.1"…), numbered exactly as
 * `MimeParser.parseBody` numbers the same message, so a section named here addresses the same part
 * an attachment download addresses. [mime] is `text/<subtype>`, lowercased; [encoding] is the
 * Content-Transfer-Encoding the server announced and [charset] the charset parameter it announced,
 * both null when it announced none — reading a part under the wrong charset is mojibake on screen,
 * so what was announced travels rather than being guessed at the far end.
 */
data class ImapTextPart(
    val section: String,
    val mime: String,
    val encoding: String?,
    val charset: String?,
)

/** How much of a body a preview fetch reads: the first 8 KiB of the text part, and no more. */
const val IMAP_PREVIEW_FETCH_BYTES = 8192

/** Bound the nesting a structure walk will follow (the [MimeParser] bound, and for the reason). */
private const val MAX_STRUCTURE_DEPTH = 24

/** Bound the siblings of one multipart a structure walk will look at. */
private const val MAX_STRUCTURE_PARTS = 1024

/**
 * The FIRST part of a parsed BODYSTRUCTURE that is this message's BODY, depth first — or null when
 * it has none, which is a normal answer and not an error: a message that is one PDF has no opening
 * line to show, and there is then nothing to preview.
 *
 * The first TEXT part, never simply the first part. The first part of an ordinary
 * `multipart/mixed` is as often an image or a PDF as it is the message, and fetching it would put
 * base64 — or, one level up, a `--boundary` line — on the lock screen in place of the mail.
 *
 * And "the body", not "any text part". The rest of the app, reading the SAME message, already
 * decides this in [MimeParser] — a part with a filename or an `attachment` disposition is a FILE
 * (`MimeParser.walk`, `isAttachment`), and `text/calendar` is an invitation captured as
 * `invite.ics`, never as text. Deciding it differently here would show `notes.txt` where the
 * message should be, or put `BEGIN:VCALENDAR VERSION:2.0 PRODID:…` on a lock screen — and the two
 * readings of one message would disagree, which is the kind of divergence nobody finds twice.
 *
 * So: `text/plain` or `text/html`, and not a file. The disposition and the filename come from the
 * same BODYSTRUCTURE (RFC 3501 §7.4.2), so this costs nothing more than reading two more fields.
 *
 * Depth first because that is where the text of real mail is: `multipart/mixed { multipart/
 * alternative { text/plain, text/html }, application/pdf }` has its opening line at 1.1, and a walk
 * that only looked at the top level would find no text part in the commonest message there is.
 *
 * Pure, and given the token tree rather than a socket, because it is the whole decision: which
 * section a notification is about to read out of somebody's mailbox.
 */
fun firstTextPart(bodystructure: Any?): ImapTextPart? = textPartIn(bodystructure, prefix = "", depth = 0)

private fun textPartIn(node: Any?, prefix: String, depth: Int): ImapTextPart? {
    if (depth > MAX_STRUCTURE_DEPTH) return null
    val fields = node as? List<Any?> ?: return null
    val head = fields.firstOrNull()
    // A multipart's structure opens with the structures of its children; a single part opens with
    // its type as a string. That shape IS the discriminator (RFC 3501 §7.4.2), and there is no
    // other: the subtype of a multipart sits after its children, not before them.
    if (head is List<*>) {
        fields.asSequence().takeWhile { it is List<*> }.take(MAX_STRUCTURE_PARTS)
            .forEachIndexed { index, child ->
                val childPrefix = if (prefix.isEmpty()) "${index + 1}" else "$prefix.${index + 1}"
                textPartIn(child, childPrefix, depth + 1)?.let { return it }
            }
        return null
    }
    if (!(head as? String).equals("text", ignoreCase = true)) return null
    val subtype = (fields.getOrNull(1) as? String)?.lowercase() ?: return null
    if (subtype !in BODY_SUBTYPES) return null
    if (isFilePart(fields)) return null
    return ImapTextPart(
        // A message that is not a multipart at all is section "1" — the same numbering
        // `MimeParser.walk` gives it (`prefix.ifEmpty { "1" }`), so both sides address one part.
        section = prefix.ifEmpty { "1" },
        mime = "text/$subtype",
        encoding = (fields.getOrNull(5) as? String)?.lowercase(),
        charset = structureParameter(fields.getOrNull(2), "charset"),
    )
}

/**
 * The text subtypes that are a message's body. `text/calendar` is deliberately absent: [MimeParser]
 * captures it as a downloadable `invite.ics`, and an invitation's payload is `BEGIN:VCALENDAR`,
 * which is machine text and not something to read on a lock screen.
 */
private val BODY_SUBTYPES = setOf("plain", "html")

/**
 * Index of `body-fld-dsp` in a `text/…` part's BODYSTRUCTURE (RFC 3501 §7.4.2).
 *
 * A text part carries one field the others do not — `body-fld-lines` at 7 — so its extension data
 * starts one late: md5 at 8, disposition at 9. This is only ever read for a `text/…` part, which is
 * what makes the constant correct rather than approximately correct.
 */
private const val TEXT_DISPOSITION = 9

/**
 * Whether this part is a FILE rather than the message, by the rule [MimeParser.walk] already
 * applies to the same message: a disposition of `attachment`, or a filename — `("filename" "x")` on
 * the disposition, or the `name` parameter of the content type, which is where older senders put it.
 *
 * An `inline` disposition with no filename is NOT a file: that is what a sender writes on the body
 * itself, and refusing it would leave those messages with no preview at all.
 */
private fun isFilePart(fields: List<Any?>): Boolean {
    val disposition = fields.getOrNull(TEXT_DISPOSITION) as? List<Any?>
    if ((disposition?.firstOrNull() as? String).equals("attachment", ignoreCase = true)) return true
    val filename = structureParameter(disposition?.getOrNull(1), "filename")
        ?: structureParameter(fields.getOrNull(2), "name")
    return !filename.isNullOrBlank()
}

/** One parameter of a BODYSTRUCTURE parameter list — `("charset" "utf-8" "format" "flowed")`. */
private fun structureParameter(parameters: Any?, name: String): String? {
    val list = parameters as? List<Any?> ?: return null
    var i = 0
    while (i < list.size) {
        if ((list[i] as? String).equals(name, ignoreCase = true)) return list.getOrNull(i + 1) as? String
        i += 2
    }
    return null
}
