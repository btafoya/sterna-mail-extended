package app.sterna.core.data.account

import app.sterna.core.imap.OutgoingMime

/**
 * Whether an outgoing message announces this account's OpenPGP key, and what it announces — the
 * whole decision, as one pure function, next to the cache it reads ([PgpPublicKeyCache]).
 *
 * The header is Autocrypt Level 1 §2.1: `Autocrypt: addr=<address>; keydata=<base64 key>`. It goes
 * on **every** send of a PGP account — bare, signed or encrypted — which is the point of the
 * standard: a correspondent who has no key yet is exactly the person it is for, and they are
 * reached by ordinary mail.
 *
 * **No `prefer-encrypt` attribute.** Absent means `nopreference` (Level 1 §2.1.1), which is the
 * truth here: this app has no per-peer encryption preference to state, and `mutual` would tell the
 * correspondent's client to start encrypting by itself.
 *
 * **The PGP mode of the message is not consulted, and must not be.** An Autocrypt header carried
 * only by encrypted mail would only ever reach people who already have the key.
 */
fun autocryptHeaderValue(account: StoredAccount?, fromAddress: String?): String? {
    if (account == null || !account.pgpEnabled) return null
    if (account.pgpSignKeyId == 0L || account.pgpPublicKey.isEmpty()) return null
    val from = fromAddress?.trim().orEmpty()
    if (from.isEmpty() || !sameMailbox(from, account.username)) return null
    // headerSafe on both halves, and it is [OutgoingMime]'s own — the single CR/LF sink, never a
    // second copy of it. The keydata is base64 by construction ([pgpPublicKeyCacheValue] is the one
    // encoder, RFC 4648 with no line breaks) so the filter takes nothing out of it; it is applied
    // all the same because this string also travels the JMAP path, where no MIME builder stands
    // between it and the server.
    //
    // Nothing folds here. Folding inserts CR/LF, so it belongs strictly after this point, and
    // only on the SMTP side ([app.sterna.core.imap.AutocryptHeader.fold]) — JMAP takes the value
    // flat and the server folds it.
    return "addr=${OutgoingMime.headerSafe(from)}; keydata=${OutgoingMime.headerSafe(account.pgpPublicKey)}"
}

/**
 * Whether [from] and [accountAddress] are the same mailbox: trimmed, compared without case.
 *
 * **This is the guard the volet exists around.** A delegated ("on behalf") send puts SOMEBODY
 * ELSE's address in `From:` — `MailRepository` submits through the login's account while the header
 * names the shared mailbox — and this account's key covers that address in no way at all.
 * Announcing it there is not a cosmetic error: the correspondent's client would file that key for
 * that address and encrypt its next message to a key whose owner is not the person reading the
 * mailbox. Unreadable mail, caused by us. So the answer when the two differ is: emit nothing.
 *
 * The comparison follows what this file's neighbours already do ([StoredAccount.defaultIdentity]
 * and [StoredAccount.resolvedIdentities] both key on `trim().lowercase()`): RFC 5321 §2.3.11 leaves
 * local-part case to the receiving host, but every client in reach treats a mailbox
 * case-insensitively, and one convention inside this codebase is worth more than a stricter rule
 * that only this function would apply. `lowercase()` with no argument is `Locale.ROOT` in Kotlin,
 * so a Turkish locale cannot make two ASCII addresses stop matching.
 *
 * It compares the addr-spec only. Callers hand over the bare mailbox address, never a
 * `Name <addr>` form — the display name is not part of the identity being claimed.
 */
private fun sameMailbox(from: String, accountAddress: String): Boolean {
    val own = accountAddress.trim().lowercase()
    return own.isNotEmpty() && from.trim().lowercase() == own
}
