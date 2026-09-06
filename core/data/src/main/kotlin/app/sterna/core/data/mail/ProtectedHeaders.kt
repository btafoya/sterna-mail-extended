package app.sterna.core.data.mail

import app.sterna.core.imap.MimeParser

/**
 * RFC 9788 protected headers: which subject a decrypted PGP/MIME message is shown under
 * (issue #128).
 *
 * An encrypted message puts a COVER subject on the envelope ("[...]", "...", "Encrypted
 * message") and carries the real one inside the ciphertext, in the headers of the decrypted
 * entity. The reader used to show the cover one, because nobody read that header block.
 *
 * Two cases, and no third:
 * - a non-blank `Subject` in [entity] IS the subject;
 * - anything else — no such field, or an empty one — keeps [outerSubject] exactly as it was.
 *
 * The emptiness guard is not a detail. An empty protected subject is not information, it is a
 * loss: it would replace a readable cover subject with a blank title, and the state on screen
 * must stay true to something the message actually says.
 *
 * [entity] is a BYTE CONTAINER (one char per wire byte, ISO-8859-1 — see `MimeParser`'s KDoc),
 * so [MimeParser.decodedHeaderOf] rather than a raw read: that is the same door the outer
 * envelope subject goes through (`ImapClient`), and a protected subject is
 * sender-controlled content just like it.
 *
 * Kept a pure function, out of `MailRepository`, so it can be EXECUTED by its tests with pinned
 * arguments — the repository itself cannot be built on a JVM bench.
 */
internal fun decryptedSubject(outerSubject: String?, entity: String): String? =
    MimeParser.decodedHeaderOf(entity, "Subject")?.takeIf { it.isNotBlank() } ?: outerSubject
