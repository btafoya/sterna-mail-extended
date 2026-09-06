package app.sterna.core.data.account

/**
 * Drops every account's OpenPGP signing key id and public key. A key id names a key inside
 * ONE provider's keyring, so carrying it across a switch would point at a key the new one has
 * never heard of (#151).
 *
 * [pgpEnabled]/[pgpEncryptByDefault] are left alone: clearing them too would silently undo a
 * separate user choice — same call K-9 makes with `NO_OPENPGP_KEY`.
 */
fun withoutPgpSignKeys(accounts: List<StoredAccount>): List<StoredAccount> =
    accounts.map { it.copy(pgpSignKeyId = 0L, pgpPublicKey = "") }
