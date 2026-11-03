package app.sterna.core.imap

/**
 * A message as the reader opens it: its header, and the body [MimeParser.parseBody] gives for its
 * source, read part by part off the BODYSTRUCTURE or, when that cannot serve, off the whole source.
 */
data class ImapOpenRead(
    /** The header block on the parts path, the whole source otherwise: each reader of it stops at
     *  the first blank line, so both answer alike. */
    val header: String,
    val body: MimeBody,
    /** Characters of the text read, what a refusal for size reports. */
    val bodyChars: Long,
    val wholeSource: Boolean,
)

/** One displayable text section, and how to decode it. [size] is the server's count of its octets. */
data class DisplayTextPart(val section: String, val encoding: String, val charset: String?, val size: Int)

/**
 * What `MimeParser.walk` finds in a message, learned from its BODYSTRUCTURE alone: the first
 * `text/html` and the first `text/plain` leaf, and the same attachments in the same order with the
 * same sections, which `fetchAttachment` and `fetchInlineImages` then address on the server.
 */
data class DisplayStructure(
    val html: DisplayTextPart?,
    val text: DisplayTextPart?,
    val attachments: List<MimeAttachment>,
    /** Every `type/subtype` met in the tree, multiparts included, lowercased. */
    val types: Set<String>,
)

/**
 * What only the exact bytes of the whole message can show: the signature check and the decryption
 * both read the source byte for byte, and their open keeps the one parse those paths are built on.
 */
private val WHOLE_SOURCE_TYPES = setOf(
    "multipart/signed",
    "multipart/encrypted",
    "application/pkcs7-mime",
    "application/x-pkcs7-mime",
    "application/pgp-encrypted",
)

fun needsWholeSource(structure: DisplayStructure): Boolean = structure.types.any { it in WHOLE_SOURCE_TYPES }

/**
 * The parsed BODYSTRUCTURE of a message as [DisplayStructure], or null when it is not one this walk
 * can trust. Null is the answer to anything malformed, never a partial structure: the caller then
 * reads the whole source, as K-9's `downloadSaneBody` does, rather than show half a message.
 */
fun displayStructureOf(bodystructure: Any?): DisplayStructure? {
    val walk = StructureWalk()
    return if (walk.visit(bodystructure, prefix = "", depth = 0)) {
        DisplayStructure(walk.html, walk.text, walk.attachments, walk.types)
    } else {
        null
    }
}

private class StructureWalk {
    var html: DisplayTextPart? = null
    var text: DisplayTextPart? = null
    val attachments = mutableListOf<MimeAttachment>()
    val types = mutableSetOf<String>()

    /** False when [node] is not a body structure. Mirrors `MimeParser.walk` rule for rule. */
    fun visit(node: Any?, prefix: String, depth: Int): Boolean {
        val fields = node as? List<*> ?: return false
        // A multipart opens with its children's structures, a single part with its type as a
        // string: that shape is the discriminator (RFC 3501 §7.4.2), the subtype comes after them.
        if (fields.firstOrNull() is List<*>) {
            val children = fields.takeWhile { it is List<*> }
            val subtype = fields.getOrNull(children.size) as? String ?: return false
            types += "multipart/${subtype.lowercase()}"
            if (depth >= MimeParser.MAX_DEPTH) return true
            children.take(MimeParser.MAX_PARTS).forEachIndexed { index, child ->
                val childPrefix = if (prefix.isEmpty()) "${index + 1}" else "$prefix.${index + 1}"
                if (!visit(child, childPrefix, depth + 1)) return false
            }
            return true
        }
        val type = fields.firstOrNull() as? String ?: return false
        val subtype = fields.getOrNull(1) as? String ?: return false
        val mime = "$type/$subtype".lowercase()
        types += mime
        val params = parametersOf(fields.getOrNull(2)) ?: return false
        val size = (fields.getOrNull(6) as? String)?.toLongOrNull()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: return false
        val cte = (fields.getOrNull(5) as? String)?.trim()?.lowercase() ?: "7bit"
        val disposition = fields.getOrNull(dispositionIndexOf(mime)) as? List<*>
        val dispositionType = disposition?.firstOrNull() as? String ?: ""
        val filename = paramValueIn(parametersOf(disposition?.getOrNull(1)).orEmpty(), "filename")
            ?: paramValueIn(params, "name")
        val section = prefix.ifEmpty { "1" }
        val cid = (fields.getOrNull(3) as? String)?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() }
        val isAttachment = !filename.isNullOrBlank() || dispositionType.lowercase().contains("attachment")
        val isInlineImage = cid != null && mime.startsWith("image/")
        fun attach(fallbackName: String, withCid: Boolean = false) {
            attachments += MimeAttachment(section, filename ?: fallbackName, mime, size, cte, if (withCid) cid else null)
        }
        when {
            isAttachment -> attach("attachment", withCid = true)
            isInlineImage -> attach("image", withCid = true)
            mime == "message/rfc822" -> attach("message.eml")
            mime == "text/html" -> if (html == null) html = DisplayTextPart(section, cte, charsetOf(params), size)
            mime == "text/plain" -> if (text == null) text = DisplayTextPart(section, cte, charsetOf(params), size)
            mime.startsWith("text/calendar") -> attach("invite.ics")
            mime == "application/pgp-encrypted" || mime == "application/pgp-signature" ||
                mime == "application/pkcs7-signature" || mime == "application/x-pkcs7-signature" ->
                attach(mime.substringAfter('/'))
        }
        return true
    }

    /** `body-fld-dsp` sits after the fields a type adds (RFC 3501 §7.4.2): a text part's line
     *  count, a message's envelope, body and line count. */
    private fun dispositionIndexOf(mime: String): Int = when {
        mime.startsWith("text/") -> 9
        mime == "message/rfc822" -> 11
        else -> 8
    }

    private fun charsetOf(params: List<Pair<String, String>>): String? =
        params.firstOrNull { it.first == "charset" }?.second
}

/** `("charset" "utf-8" "name" "a.pdf")` as pairs with lowercased keys; NIL is none, any other shape is unreadable. */
private fun parametersOf(node: Any?): List<Pair<String, String>>? {
    if (node == null) return emptyList()
    val list = node as? List<*> ?: return null
    return list.chunked(2).mapNotNull { pair ->
        val key = pair.getOrNull(0) as? String ?: return@mapNotNull null
        val value = pair.getOrNull(1) as? String ?: return@mapNotNull null
        key.lowercase() to value
    }
}

/**
 * The open of one message: `UID FETCH (BODYSTRUCTURE BODY.PEEK[HEADER])`, then the text sections
 * alone, so a large attachment costs nothing until it is asked for. Every read is `BODY.PEEK`: a
 * bare `BODY[…]` sets `\Seen` on the server, for every device, before the reader decides anything.
 */
fun ImapSession.readForDisplay(uid: Long): ImapOpenRead = readTextParts(uid) ?: wholeSourceRead(uid)

/** [readForDisplay] with no whole-source fallback: null where only the source would serve, which
 *  for a signed or encrypted message carries its attachments too. */
fun ImapSession.readTextParts(uid: Long): ImapOpenRead? =
    try {
        readByParts(uid)
    } catch (refused: ImapException) {
        // A server that refuses the structure or a section may still serve the source. A dropped
        // connection or a spent read budget is not a refusal: it must reach the reader's Retry.
        if (refused.taggedStatus == null) throw refused
        null
    }

/** Null when the whole source has to be read instead. */
private fun ImapSession.readByParts(uid: Long): ImapOpenRead? {
    val head = fetchItems(uid, "BODYSTRUCTURE BODY.PEEK[HEADER]")
    val header = (head["BODY[HEADER]"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    val structure = displayStructureOf(head["BODYSTRUCTURE"]) ?: return null
    if (needsWholeSource(structure)) return null
    val texts = listOfNotNull(structure.html, structure.text)
    val announced = texts.sumOf { it.size.toLong() }
    if (announced > MimeParser.MAX_BODY_CHARS) return tooLargeRead(header, announced)
    val sections = fetchTextSections(uid, texts.map { it.section }) ?: return null
    val read = sections.values.sumOf { it.length.toLong() }
    if (read > MimeParser.MAX_BODY_CHARS) return tooLargeRead(header, read)
    fun decoded(part: DisplayTextPart?): String? = part?.let {
        MimeParser.decode(sections.getValue(it.section), it.encoding, displayCharsetOf(it.charset))
    }
    return ImapOpenRead(header, MimeBody(decoded(structure.html), decoded(structure.text), structure.attachments), read, wholeSource = false)
}

/** Servers write `us-ascii` for a text that names no charset, which the whole-source parse reads as
 *  UTF-8; UTF-8 decodes ASCII identically, so reading it in its place loses nothing. */
private fun displayCharsetOf(announced: String?) =
    MimeParser.charsetNamed(announced?.takeUnless { it.trim().equals("us-ascii", ignoreCase = true) })

private fun tooLargeRead(header: String, chars: Long) =
    ImapOpenRead(header, MimeBody(null, null, emptyList(), tooLarge = true), chars, wholeSource = false)

private fun ImapSession.wholeSourceRead(uid: Long): ImapOpenRead {
    val raw = fetchSource(uid)
    return ImapOpenRead(raw, MimeParser.parseBody(raw), raw.length.toLong(), wholeSource = true)
}

/** Null when the server left out one of them: a missing section is not an empty one, and an
 *  empty body cached for it would stay for the life of the install. */
private fun ImapSession.fetchTextSections(uid: Long, sections: List<String>): Map<String, String>? {
    if (sections.isEmpty()) return emptyMap()
    val items = fetchItems(uid, sections.joinToString(" ") { "BODY.PEEK[$it]" })
    return sections.associateWith { section -> items["BODY[$section]"] as? String ?: return null }
}

/**
 * The items of one message's FETCH answer, by their exact names. Not `bodyItem()`, which takes the
 * first name starting with `BODY` and would hand back the BODYSTRUCTURE of a combined fetch. A
 * server may split its answer over several lines; one naming another UID is not this message's.
 */
private fun ImapSession.fetchItems(uid: Long, items: String): Map<String, Any?> {
    val out = mutableMapOf<String, Any?>()
    for (line in command("UID FETCH $uid ($items)").untagged) {
        if (line.getOrNull(2) != "FETCH") continue
        @Suppress("UNCHECKED_CAST")
        val list = line.getOrNull(3) as? List<Any?> ?: continue
        val answer = pairUp(list)
        val answered = (answer["UID"] as? String)?.toLongOrNull()
        if (answered != null && answered != uid) continue
        out += answer
    }
    return out
}
