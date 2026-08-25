package app.sterna.core.imap

/** Transport security for an IMAP/SMTP connection. */
enum class MailSecurity {
    /** Implicit TLS from the first byte (IMAP 993 / SMTP 465). */
    TLS,

    /** Plain connection upgraded with STARTTLS (IMAP 143 / SMTP 587). */
    STARTTLS,

    /** No encryption (discouraged; for local testing only). */
    NONE,
}

/** Connection + credentials for an IMAP or SMTP server. */
data class MailServerConfig(
    val host: String,
    val port: Int,
    val security: MailSecurity,
    val username: String,
    val password: String,
    /**
     * OAuth bearer access token. When non-null the client authenticates with the SASL
     * **XOAUTH2** mechanism instead of a password (used for Outlook/Microsoft etc.).
     */
    val accessToken: String? = null,
)

/**
 * The SASL XOAUTH2 initial client response (base64), per the Google/Microsoft spec:
 * `base64("user=" + username + ^A + "auth=Bearer " + token + ^A^A)` where ^A is U+0001.
 */
internal fun xoauth2Payload(username: String, accessToken: String): String {
    val sep = Char(1) // SASL XOAUTH2 field separator (Ctrl-A / U+0001)
    val raw = "user=$username${sep}auth=Bearer $accessToken$sep$sep"
    return java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
}

/** A mailbox returned by LIST, with any role inferred from its name/attributes. */
data class ImapFolder(
    /** The display leaf, decoded and stripped of bidi/control characters. Never sent back. */
    val name: String,
    /**
     * The mailbox path in UNICODE, decoded from modified UTF-7 (Codeberg #101) — the app's
     * identifier for the folder, and what every `ImapSession` command takes. It is re-encoded
     * at the socket, so it must stay a faithful decoding: nothing is filtered out of it.
     */
    val path: String,
    /** A normalised role ("inbox", "sent", "drafts", "trash", "junk", "archive") or null. */
    val role: String?,
    val delimiter: String,
    /**
     * The role this folder CLAIMED and did not get, because another folder of the same listing
     * carried it already — null whenever [role] is non-null, and null for a folder that claimed
     * nothing. A second `\Trash`, or a second mailbox the name table reads as a trash.
     *
     * A statement of fact, nothing more: this module reports what the listing said and decides
     * nothing from it. What the app makes of a lost claim belongs to `:core:data`, which turns a
     * lost `trash`/`junk` into a MARKED role (`~trash`, `~junk`, `MailRepository`'s
     * `UNELECTED_ROLE_MARK`) so a second trash stays out of search and out of the index while
     * remaining an ordinary folder everywhere else. The two modules do not depend on each other,
     * so that convention lives in a comment on both sides and nowhere in code here.
     */
    val unelectedRole: String? = null,
)

/** One decoded envelope address (either part may be absent). */
data class ImapAddress(
    val name: String?,
    val email: String?,
)

/** One email's envelope + flags as fetched from IMAP (body fetched separately). */
data class ImapMessage(
    val uid: Long,
    val subject: String?,
    val fromName: String?,
    val fromEmail: String?,
    val to: List<ImapAddress>,
    /**
     * Envelope `Reply-To` (index 4): the addresses the SENDER named as the place to answer.
     *
     * Empty when the header is absent, which is the common case and means "answer the From".
     * When it is not empty it is not a preference: a list, a ticketing alias or a `no-reply@`
     * sender said in the message itself that the From address is not read. Persisted like the
     * recipients ([ImapMessage.to]) so an offline reply and the notification quick reply — both
     * of which only ever see the cache row — address it too.
     */
    val replyTo: List<ImapAddress> = emptyList(),
    /**
     * Envelope `Cc` (index 6): the people put in copy, who a "reply to all" has to keep.
     *
     * Empty when the header is absent. Persisted like the recipients ([ImapMessage.to]) because
     * the cache row is all a reopened draft and an offline reply-all ever see: an address that
     * stops here is an address they cannot address, and the copied parties silently drop out of
     * the conversation.
     */
    val cc: List<ImapAddress> = emptyList(),
    /**
     * Envelope `Bcc` (index 7): the blind copies.
     *
     * ⚠ Practically always empty on a received message — a server does not tell a recipient who
     * else was blind-copied — and empty too on the mail THIS app SENT, whose bytes are built with
     * [BlindCopies.OMITTED]: a header there would hand the hidden recipients to everyone the
     * message names.
     *
     * ⭐ The drafts THIS app APPENDs are the one exception, and they DO carry a `Bcc:` since
     * 2026-08-25: the ENVELOPE mirrors the headers of whatever was APPENDed, and `appendDraft`
     * asks [OutgoingMime.build] for [BlindCopies.WRITTEN]. That header is the only place a Cci
     * survives a save — a draft is reopened from the server's copy, not from what the composer
     * still remembers — so this field is where it comes back, and it is the only field of a
     * Sterna message that ever holds one. A draft another client appended with a `Bcc:` header
     * reads back here just the same. Read anyway, because it costs
     * nothing (the ENVELOPE is already fetched whole) and because a field that is never read is a
     * field one starts believing is lost somewhere else.
     *
     * ⛔ Its own list, never merged into [cc]: that merge is how a blind copy stops being blind.
     */
    val bcc: List<ImapAddress> = emptyList(),
    /** Epoch millis from the envelope date (0 if unparseable). */
    val dateMillis: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    /**
     * The `\Deleted` flag: the server has been told this message is to go, and only an EXPUNGE
     * (from any client) actually removes it. Sterna never shows such a message — see
     * [ImapSession.messages], the one place that acts on this.
     */
    val deleted: Boolean = false,
    val hasAttachment: Boolean,
    val messageId: String?,
    val inReplyTo: String?,
    /**
     * Where this message's readable text is, per the BODYSTRUCTURE that came with the envelope, or
     * null when it has no `text/…` part (and when the server sent no structure at all).
     *
     * ⭐ Additive and nullable: nothing that lists, searches or restores a message reads it. It is
     * kept ONLY so that a later, deliberate read — the notification preview — can name a section
     * without asking the server to describe the message a second time. The structure was already
     * fetched and already thrown away; see [firstTextPart].
     */
    val textPart: ImapTextPart? = null,
)

/** Result of selecting a mailbox. */
data class ImapMailboxStatus(
    val exists: Int,
    val uidValidity: Long,
    val uidNext: Long,
    /**
     * ⛔ Whether [exists] is a number THE SERVER STATED, or merely the value it is initialised to.
     *
     * `SELECT` is required to answer `* n EXISTS` (RFC 3501 §6.3.1), and when it does not — the line
     * absent, or an `n` that will not parse (`* NIL EXISTS`) — [exists] stays 0, which reads exactly
     * like an empty folder. It is not one: it is a folder nothing is known about. A walk built on it
     * asks for no page at all, comes back with no UID, and the reconcile above would then take the
     * empty result as "the folder is empty" and DELETE every cached row of it.
     *
     * ⭐ False by default, and the default is the refusing one on purpose: a value that has to be
     * asserted to license a delete cannot be forgotten into existence. `false` costs a cache that
     * keeps more than the window until the next refresh; `true` costs the folder.
     */
    val existsObserved: Boolean = false,
)

/**
 * What a move reported about where the mail landed — the answer to `UID MOVE`, or to the
 * `UID COPY` it falls back to.
 *
 * [uids] is the source-UID → destination-UID mapping `COPYUID` reported (RFC 4315 §3, and RFC 6851
 * §4.3 for `UID MOVE`), empty when the server reported none: the move still happened, only
 * per-message Undo positioning is lost.
 *
 * [destinationUidValidity] is the DESTINATION folder's numbering as that same `COPYUID` stated it
 * — the first token of the response code, which this client used to discard. ⭐ It is a FROZEN
 * fact, not a reading: the numbering in force at the instant those destination UIDs were assigned,
 * stated by the server in the same breath as the UIDs themselves. Nothing else in a move ever
 * learns it, because a `UID MOVE` does not SELECT its destination — so on a Trash the user has
 * never opened this is the ONLY number an Undo can oppose (Codeberg #99). Null when no `COPYUID`
 * came back at all, and null when the chunks of one move disagreed about it (see [ImapSession.move]).
 *
 * ⛔ Reporting it refuses nothing here: `core/imap` states what the server said and decides
 * nothing from it, exactly as [ImapFolder.unelectedRole] does.
 */
data class ImapMoved(val uids: Map<Long, Long>, val destinationUidValidity: Long?) {
    companion object {
        /** Nothing moved, nothing stated — what a caller answers when the move never went out. */
        val NONE = ImapMoved(emptyMap(), null)
    }
}
