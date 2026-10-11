package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email

/** Key of both in-memory crypto caches. The account is part of it: same-server accounts can hold
 *  colliding email ids (#31), and a bare-id key would serve the sibling account's message. */
internal fun cryptoKey(accountId: String, emailId: String) = "$accountId:$emailId"

/**
 * [original] as a reply quotes it once the reader has decrypted it: the server's message with the
 * body of the decrypted copy [read] holds for [accountId], or null when there is nothing decrypted
 * to quote. Only the three body fields are taken: the decrypted copy also carries the protected
 * subject, which nothing outgoing may read.
 */
internal fun quotableClear(accountId: String, original: Email, read: (key: String) -> MessageBody?): Email? {
    val clear = read(cryptoKey(accountId, original.id)) ?: return null
    val crypto = clear.crypto as? MessageCrypto.Decrypted ?: return null
    // An S/MIME envelope's clear lands in the same cache. Quoting it is not decided (#253 is about
    // OpenPGP alone), so it stays out rather than leave in a reply nothing holds to encryption.
    if (crypto.smime) return null
    // A signed-only original is readable as the server returns it, and a non-null answer tells the
    // composer that the quote was encrypted on arrival.
    if (!crypto.wasEncrypted) return null
    return original.copy(
        textBody = clear.email.textBody,
        htmlBody = clear.email.htmlBody,
        bodyValues = clear.email.bodyValues,
    )
}
