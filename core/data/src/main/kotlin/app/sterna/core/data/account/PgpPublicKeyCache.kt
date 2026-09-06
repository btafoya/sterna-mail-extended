package app.sterna.core.data.account

import java.util.Base64

/**
 * Everything decidable about [StoredAccount.pgpPublicKey] — the account's OWN public key, kept on
 * the account so something with no screen can announce it later.
 *
 * Why a cache at all: reading a key from the OpenPGP provider can answer
 * USER_INTERACTION_REQUIRED, and the only launcher of a provider PendingIntent in this app is a
 * `@Composable`. So the key is read where there IS a screen (choosing the key, opening the
 * composer) and written down; a worker sending hours later can only emit what is already here.
 *
 * Pure functions, in this module, because nothing here can build an [AccountStore] (it needs a
 * `Context` and the Keystore, and there is no Robolectric): the store EXECUTES these, so this is
 * the part a JVM test can execute too.
 */

/**
 * The exact text [StoredAccount.pgpPublicKey] stores for what the provider handed back.
 *
 * The encoding happens HERE and nowhere else, deliberately. The provider returns raw bytes (a
 * binary, minimized transferable public key), the field is a `String`, and two call sites persist
 * it — choosing a key, and the composer's catch-up. Two encoders is two chances to disagree on
 * line wrapping, and a wrapped value is not what a message header can carry.
 *
 * `Base64.getEncoder()`, i.e. RFC 4648 with NO line breaks — not `getMimeEncoder()`, which folds at
 * 76 columns with CRLF. Absent or empty bytes give the empty string, which is exactly "no cache":
 * a provider that answered with nothing must not be recorded as an answer.
 */
fun pgpPublicKeyCacheValue(minimizedKey: ByteArray?): String =
    if (minimizedKey == null || minimizedKey.isEmpty()) {
        ""
    } else {
        Base64.getEncoder().encodeToString(minimizedKey)
    }

/**
 * The account whose public key must be fetched now, or null when there is nothing to do — the whole
 * catch-up rule, in one place, for the composer to execute.
 *
 * Three conditions, and all three matter:
 *  - a signing key is configured ([StoredAccount.pgpSignKeyId] non-zero): with no key there is
 *    nothing to ask the provider FOR;
 *  - the cache is empty: this is a catch-up for accounts configured before the field existed, not
 *    a refresh. Re-reading a cache we already hold would be a provider round-trip per composer;
 *  - [alreadyTried] does not name it. Load-bearing: the composer re-arbitrates PGP several times
 *    per opening (open, restore, every "From" change), and a provider that is absent or busy fails
 *    every time — without this the same doomed round-trip runs again and again behind a screen the
 *    user is typing on. One attempt per account per composer; the next opening tries again.
 */
fun pgpPublicKeyBackfill(account: StoredAccount?, alreadyTried: Set<String>): String? =
    account
        ?.takeIf { it.pgpSignKeyId != 0L && it.pgpPublicKey.isEmpty() && it.id !in alreadyTried }
        ?.id

/**
 * [accounts] with the public key of [id] replaced by [publicKey] — but ONLY while that account
 * still carries [signKeyId], and nothing else touched.
 *
 * The key-id guard is the whole point. Reading a key is a round-trip to another app, and while
 * it runs the user can pick a different key or switch OpenPGP app entirely (which empties every
 * cache, see [withoutPgpSignKeys]). Writing back unconditionally would file the OLD key's bytes
 * under the NEW key id, and the account would then announce, as its key, one it cannot sign with.
 *
 * A zero [signKeyId] matches nothing: an account with no key has no key to cache.
 */
fun withCachedPgpPublicKey(
    accounts: List<StoredAccount>,
    id: String,
    signKeyId: Long,
    publicKey: String,
): List<StoredAccount> = accounts.map {
    if (signKeyId != 0L && it.id == id && it.pgpSignKeyId == signKeyId) {
        it.copy(pgpPublicKey = publicKey)
    } else {
        it
    }
}
