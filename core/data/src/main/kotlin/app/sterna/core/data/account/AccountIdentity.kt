package app.sterna.core.data.account

/**
 * WHICH ACCOUNT IS ALREADY THERE — the one answer, in one place.
 *
 * Adding the same mailbox twice used to make two accounts, because every creation route compared
 * (or did not compare) its own way. This file holds the comparison itself, as a pure function, so
 * a route decides by CALLING it rather than by re-deriving it.
 *
 * [authType] is deliberately NOT part of the key: the same address added by password and then by
 * OAuth is the same account, and a key that separated them would let the list lie again.
 *
 * Nothing here is persisted or migrated. Normalisation happens when the key is COMPUTED;
 * [AccountStore] keeps writing exactly what it wrote before.
 */

/** The identity an account is recognised by: protocol + normalised endpoint + normalised username. */
data class AccountKey(
    val protocol: MailProtocol,
    val endpoint: String,
    val username: String,
)

/**
 * An endpoint reduced to what actually identifies the server: trimmed, lower-cased, with an
 * `http://` / `https://` scheme and any trailing `/` removed.
 *
 * The scheme is not cosmetic. The OAuth routes store the RAW candidate the user typed
 * (`https://mail.example.test`) where the token route stores what discovery resolved
 * (`mail.example.test`); compared as-is, those two are different servers and the duplicate gets in.
 *
 * A PATH IS KEPT, deliberately, and this is where the guard stops. `mail.example.test` and
 * `mail.example.test/jmap/session` stay two different endpoints, so the same mailbox reached once
 * by autodiscovery and once by a pasted session URL is still two accounts. Stripping the path
 * would merge them — and would equally merge two genuinely different JMAP servers hosted under one
 * name at different paths, overwriting one account's secret and connection with the other's. A
 * duplicate is ugly; a wrong merge loses what the user had, so the wider rule loses (rule 1).
 */
fun normalizeEndpoint(raw: String): String =
    raw.trim()
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
        .trim()

/** IMAP keys on the IMAP host (an IMAP account carries a blank `server`); everything else on `server`. */
fun accountEndpointOf(protocol: MailProtocol, server: String, imapHost: String): String =
    if (protocol == MailProtocol.IMAP) imapHost else server

/**
 * The key these fields would be recognised by, or null when there is nothing to recognise:
 * a blank endpoint or a blank username means NO guard at all, rather than a key that would make
 * two unrelated half-filled accounts collide.
 */
fun accountKeyOf(
    protocol: MailProtocol,
    server: String,
    imapHost: String,
    username: String,
): AccountKey? {
    val endpoint = normalizeEndpoint(accountEndpointOf(protocol, server, imapHost))
    val user = username.trim().lowercase()
    if (endpoint.isBlank() || user.isBlank()) return null
    return AccountKey(protocol, endpoint, user)
}

/** The key of a stored account. */
fun accountKeyOf(a: StoredAccount): AccountKey? =
    accountKeyOf(a.protocol, a.server, a.imapHost, a.username)

/** The account already stored under [key], or null (including for a null key: no key, no match). */
fun resolveExisting(accounts: List<StoredAccount>, key: AccountKey?): StoredAccount? {
    if (key == null) return null
    return accounts.firstOrNull { accountKeyOf(it) == key }
}

/**
 * The stored LOGIN recognised by [key], or null. [resolveExisting] narrowed to standalone records
 * (`loginId == null`).
 *
 * Not decoration. A linked sub-account BORROWS its login's secret ([StoredAccount.loginKey] hands
 * back `loginId ?: id`), so resolving an add onto one would file the new password or refresh token
 * under an id nothing ever reads, while that account went on borrowing somebody else's. The two
 * writing routes ([AccountStore.add], [AccountStore.addOAuth]) create a login and never a
 * sub-account, so a login is the only thing they may recognise.
 */
fun resolveExistingLogin(accounts: List<StoredAccount>, key: AccountKey?): StoredAccount? =
    resolveExisting(accounts.filter { it.loginId == null }, key)

/**
 * The first login recognised by one of [keys], in the order given, or null. A null key recognises
 * nothing (see [resolveExistingLogin]), so a route may hand over a key it could not build without
 * losing the keys after it.
 *
 * Why here and not inside [accountKeyOf]. A stored account has ONE key and must keep exactly
 * one: `mail.example.test` and `jmap.example.test` are two endpoints, and two genuinely different
 * mailboxes served under them would recognise each other the day the key itself grew a family of
 * hosts — [AccountStore.writePassword] would then overwrite a secret that has no second copy
 * (rule 1: the option that cannot lose data wins). A ROUTE offers one key MORE, of its own
 * choosing, for its own add and no one else's. It looks under each key in turn and lets
 * [refreshedWith] put `server` right on the way past.
 *
 * What is NOT checked here is PROVENANCE. This compares keys and does nothing else: the extra
 * key recognises whatever login happens to sit under that host, whether this route filed it, the
 * manual password form did, or the API-token route did. Nothing asks. What keeps the reach narrow
 * is not a check but what a caller may offer — both keys carry the SAME address, and the two hosts
 * are ones `Jmap.autodiscoverHosts` produced for that ONE domain — so the login found under the
 * older key is the same mailbox by every field the store has.
 *
 * Without it, an OAuth account added by 1.5.0 or 1.5.1 — filed under the host the OAuth document
 * came from, `mail.<domain>` — is invisible to the host this version resolves, `<domain>`. The
 * first re-authentication after the update (the routine gesture when a refresh token expires)
 * would take a SECOND line: the fix for #55 manufacturing the duplicate of #55.
 *
 * The ORDER is the decision, not a detail. The key this version writes comes first, so when both
 * lines happen to exist an add lands on the one this version maintains.
 */
fun resolveExistingLoginAmong(accounts: List<StoredAccount>, keys: List<AccountKey?>): StoredAccount? =
    keys.firstNotNullOfOrNull { resolveExistingLogin(accounts, it) }

/**
 * [existing] brought up to date with what an add has just PROVED, and with nothing else.
 *
 * Written as `existing.copy(...)` on purpose, never as `proven.copy(...)`. The two read the same
 * today and diverge the day a field is added to [StoredAccount]: starting from [existing] KEEPS it,
 * starting from [proven] silently resets it to whatever a fresh sign-in form happened to build.
 * Rule 1 of this project — the option that cannot lose data wins.
 *
 * Kept from [existing], because no sign-in proves any of them: the id (the encrypted secret is
 * bound to it, and every table refers to it), accountName, color, loginId, jmapAccountId, the inbox
 * metadata, syncWindow, notificationsEnabled, uploadSentCopy, showOnlySubscribedFolders,
 * watchedFolders, signature, identities, serverIdentities, defaultIdentityId and the PGP
 * settings. Overwriting accountName
 * alone would mean a user re-adding her account with the "account name" field left empty loses the
 * name she gave it; overwriting color, watchedFolders or the identities loses settings the add
 * never even asked about.
 *
 * Coming back with a password after OAuth DOES clear oauth* and put authType back to BASIC, and
 * that is wanted: `AccountStore.writePassword` writes the password into the very slot that held the
 * refresh token, so leaving the tokens behind would make the record describe a credential the
 * account no longer has.
 *
 * importPending goes false: the account has just been signed in, it is no longer waiting for it.
 */
fun refreshedWith(existing: StoredAccount, proven: StoredAccount): StoredAccount =
    existing.copy(
        server = proven.server,
        username = proven.username,
        protocol = proven.protocol,
        authType = proven.authType,
        oauthAccessToken = proven.oauthAccessToken,
        oauthAccessExpiresAt = proven.oauthAccessExpiresAt,
        oauthTokenEndpoint = proven.oauthTokenEndpoint,
        oauthClientId = proven.oauthClientId,
        imapHost = proven.imapHost,
        imapPort = proven.imapPort,
        imapSecurity = proven.imapSecurity,
        smtpHost = proven.smtpHost,
        smtpPort = proven.smtpPort,
        smtpSecurity = proven.smtpSecurity,
        importPending = false,
    )
