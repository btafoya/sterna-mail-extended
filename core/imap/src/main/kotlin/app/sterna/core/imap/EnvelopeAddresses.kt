package app.sterna.core.imap

/**
 * One envelope address list — slot 4 (`reply-to`), 5 (`to`), 6 (`cc`) or 7 (`bcc`) of an
 * ENVELOPE — turned into the addresses the app carries. The four slots were four copies of this
 * block; the decision lives here once so it can be RUN by a test instead of being read.
 *
 * This function does not know WHICH slot it was handed. Picking the slot stays at the call
 * site, where the comments explaining the indices are: filling `cc` from slot 7 would publish
 * blind copies, and nothing here could tell.
 *
 * Each entry is RFC 3501's four-field address: `(name adl mailbox host)`. An entry that carries
 * an address or a display name is a MEMBER and becomes an [ImapAddress]. Two other shapes are
 * not addresses at all but the delimiters of RFC 5322 group syntax, which a server sends
 * verbatim:
 *
 *   group start `(NIL NIL "the-list" NIL)`   — a mailbox with no host
 *   group end   `(NIL NIL NIL NIL)`          — everything absent
 *
 * A group that HAS members is carried by its members: a group start immediately followed by a
 * member is dropped and nothing is added, which is what every reader of these lists expects and
 * what the older tests pin.
 *
 * A group start followed by anything else is a group that names NOBODY, and dropping it loses the
 * field entirely: `Cc: undisclosed-recipients:;` arrived as an empty Cc, which reopened a draft
 * with the copy line collapsed and the save disabled. So it yields ONE entry carrying the group's
 * name and no address ([ImapAddress.email] is null) — exactly what the header says.
 *
 * The guard is "not followed by a MEMBER", not "followed by a group end", and the difference is
 * a data loss both ways:
 *
 *  - A start with NO end is carried too — a start at the end of the list, or before another
 *    start. HYPOTHETICAL, and named as such: it is the defence for a server that would hand a
 *    group back half-spelled. It is NOT what was measured. On `emu`/Stalwart, 2026-08-26, a bare
 *    atom (`Cc: undisclosed-recipients`, the `:;` not rebuilt) does not come back at all — the
 *    server answers `cc = NIL` and nothing reaches this function. That is why the APPEND now
 *    writes the group form itself (`localDraftOutgoing`, `core/data`): the round trip is fixed
 *    where the bytes are written, and requiring an end marker here would still be the wrong
 *    reading — an unmatched start is a group naming nobody, and dropping it would empty a field
 *    that "addressing proved empty" then authorises the save to expunge the copy of.
 *  - `Cc: the-list: "Nobody";` gives a member that is a display name with no address. Looking for
 *    the end marker would emit the group name beside it, putting in the field a recipient nobody
 *    wrote. So "member" here is the SAME test the loop uses to emit — one definition, not two.
 *
 * Assumed price, arbitrated: a malformed address with no domain (`To: bob`, i.e.
 * `(NIL NIL "bob" NIL)`) also stops vanishing silently and comes out as an entry with a name and
 * no address. That is the intended direction — the composer shows the string back instead of
 * dropping it.
 *
 * The name is taken from the mailbox field (index 2), never index 0, which a group start
 * leaves NIL — and it is decoded with [decodeWords] like every other display name, because a
 * group name is header text and can arrive as an encoded-word.
 *
 * The guard that drops an entry with neither an address nor a name stays: it is what keeps a
 * malformed entry from becoming an empty recipient. It is only NARROWED, by the group start.
 */
internal fun envelopeAddresses(entries: List<Any?>?): List<ImapAddress> {
    val parsed = entries.orEmpty().map(::parseEnvelopeEntry)
    val out = ArrayList<ImapAddress>(parsed.size)
    for (index in parsed.indices) {
        val entry = parsed[index] ?: continue
        if (entry.isMember) {
            out += ImapAddress(name = entry.name, email = entry.email)
            continue
        }
        // Neither an address nor a name: a delimiter, or something malformed. The only one worth
        // an entry is a group start that no member follows — a group naming nobody. A group end
        // is never one (it has no mailbox), so it stays dropped, and so does an entry that is
        // simply unreadable.
        //
        // "No member follows" covers three cases and they are all real: a group end, the end of
        // the list (our own round trip), and another group start. `parsed[index + 1]` is null
        // both past the end and for a token that is not an address list at all — neither is a
        // member.
        if (entry.isGroupStart && parsed.getOrNull(index + 1)?.isMember != true) {
            out += ImapAddress(name = decodeWords(entry.rawBox), email = null)
        }
    }
    return out
}

/** One parsed envelope entry: what it says, and which of the three shapes it is. */
private class EnvelopeEntry(
    val name: String?,
    val email: String?,
    val rawBox: String?,
    val isGroupStart: Boolean,
) {
    /** It carries an address or a display name, so it is a recipient and not a delimiter. */
    val isMember: Boolean get() = email != null || name != null
}

/** An envelope address `(name adl mailbox host)`, or null for a token that is not one. */
@Suppress("UNCHECKED_CAST")
private fun parseEnvelopeEntry(entry: Any?): EnvelopeEntry? {
    val addr = entry as? List<Any?> ?: return null
    val rawName = addr.getOrNull(0) as? String
    val rawBox = addr.getOrNull(2) as? String
    val rawHost = addr.getOrNull(3) as? String
    val name = decodeWords(rawName)
    // The local part and the domain need [decodeHeaderBytes] just as much as the display name
    // beside them: an EAI address (RFC 6531) arrives as an 8-bit literal, and a mangled one
    // sends mail to an address that does not exist.
    val box = rawBox?.let(::decodeHeaderBytes)
    val host = rawHost?.let(::decodeHeaderBytes)
    return EnvelopeEntry(
        name = name,
        email = if (box != null && host != null) "$box@$host" else null,
        rawBox = rawBox,
        // Index 1 (the source route, `adl`) is ignored here as it is everywhere else: it is
        // obsolete and no reader looks at it.
        isGroupStart = rawName == null && rawBox != null && rawHost == null,
    )
}
