package app.sterna.core.data.account

/**
 * Which of the identities a Save is about to write must ALSO be created on the server
 * (`Identity/set`, RFC 8621 §6.4).
 *
 * Sterna read identities and never created one, so an alias added in Settings lived on the phone
 * alone: the server had no identity for that address and refused every send from it (#172). This is
 * the decision of what leaves — a plain function so a JVM test can execute it, because neither
 * `MailRepository` nor the ViewModel that calls it can be instantiated off a device.
 *
 * [before] is the account AS STORED before this save; [edited] the manual list this save is about
 * to persist; [serverIdentities] the list the CALLER has just read from the server
 * (`Identity/get`), or `null` when that read failed.
 *
 * The guards, and why each one is here:
 *  - **IMAP**: there is no JMAP session and no [StoredAccount.serverIdentities] behind an IMAP
 *    account; an alias there is a local From and nothing else.
 *  - **Linked (delegated) sub-account**: it cannot even run `Identity/get` on itself — the server
 *    answers "You are not an owner" (#31) — and the only account id it could reach is its LOGIN's.
 *    Creating there would post a durable identity on somebody else's account, which is the one
 *    outcome that cannot be undone from this screen (rule 1: never the option that can lose).
 *  - **The server's list could not be read** (`serverIdentities == null`): nothing is told apart in
 *    that state — a brand new address and one the server already holds look identical — and posting
 *    blind fabricates duplicates. Worse, in exactly that state the editor MAKES UP a row carrying
 *    the account's own address (`SettingsScreen`), which reaches this function looking
 *    like something the user typed, so a Save that only renamed a display name would post a durable
 *    identity nobody asked for. Rule 1: never the option that writes what was not asked.
 *    It is the PARAMETER that says this, never `before.serverIdentities.isEmpty()`. That field is
 *    only ever written by `MailRepository.connect`, which neither adding an account, nor Test
 *    connection, nor a sync runs — so on a freshly added account it is empty because nobody has
 *    read it, and inferring "never read" from "empty" refused every creation for good, in silence
 *    (measured on the S7, 2026-08-31). The read is now the caller's job, and an EMPTY list is a
 *    fact: the server holds no identity for this account, so a new address does leave.
 *  - **Already known**: compared against [StoredAccount.identities] as well as the list just read,
 *    not the server list alone. Without the manual half, every Save would re-post every manual
 *    alias the user has ever typed — a retroactive mass creation nobody asked for. Only what THIS
 *    gesture touched leaves; a row whose address the user changed is a new address, so it does
 *    leave.
 *  - **The account's own login address counts as already known while [StoredAccount.identities] is
 *    EMPTY.** In exactly that state the account screen MAKES UP one identity row carrying
 *    [StoredAccount.username] so the Identities section does not open empty
 *    (`SettingsScreen`). Nobody typed it; it reaches here through
 *    `AccountEditorSave` looking like any other row, and a Save that only renamed the
 *    display name of a freshly added account would post a durable `Identity/set` for an address
 *    nobody asked to publish (rule 1: never the option that cannot be undone) — and, offline,
 *    would name that address in the failure message, in nine languages.
 *    The condition is `before.identities.isEmpty()`, no wider and no narrower, because it is the
 *    PRODUCER's condition: the editor fabricates when
 *    `normalizeManualIdentities(account.identities, account.serverIdentities)` is empty AND
 *    `account.serverIdentities` is empty; with an empty server list that call collapses to
 *    `manual.distinctBy(…)` (`StoredAccount`), which is empty only when `manual` is.
 *    Once ONE manual identity is stored the editor fabricates nothing, so a row carrying the
 *    login address has been TYPED and leaves like any other. Do not widen this to "always skip the
 *    login address".
 *    It also closes a mutation no test could see: `SettingsScreen`,
 *    `if (account.serverIdentities.isEmpty())` → `if (true)`. Compose does not run in a JVM test,
 *    so nothing read that line, and the safety of this decision used to depend on it. It no longer
 *    does: whatever the editor seeds on an account with no stored identity, nothing leaves.
 *  - **Plausible**: `part@part`, no blanks, no second `@`. Checked by hand because
 *    `android.util.Patterns` does not exist in this module, and deliberately loose: it exists to
 *    keep a half-typed row from reaching the network, not to arbitrate what an address is.
 *
 * The result is deduped on the same case-folded key, so one Save creates one identity per address.
 */
fun identitiesToCreate(
    before: StoredAccount,
    edited: List<StoredIdentity>,
    serverIdentities: List<StoredIdentity>?,
): List<StoredIdentity> {
    if (before.protocol != MailProtocol.JMAP) return emptyList()
    if (before.isLinked) return emptyList()
    if (serverIdentities == null) return emptyList()
    val fold = { email: String -> email.trim().lowercase() }
    val stored = (before.identities + serverIdentities).map { fold(it.email) }
    // The row the editor makes up when the account holds no manual identity — see the KDoc.
    val fabricated = if (before.identities.isEmpty()) listOf(fold(before.username)) else emptyList()
    val known = (stored + fabricated).toSet()
    return edited
        .filter { plausibleIdentityAddress(it.email) && it.email.trim().lowercase() !in known }
        .distinctBy { it.email.trim().lowercase() }
}

/**
 * Could this save create anything at all — decided WITHOUT a single request.
 *
 * [identitiesToCreate] now needs the server's list, and reading it costs a JMAP session. This
 * answers the question that comes first: "if the server held nothing, would anything leave?" That
 * is the super-set of the candidates — a server that holds something can only filter more — so a
 * `false` here can never hide a case, and a `true` is only a permission to go and look.
 *
 * Without it the caller would open a session on EVERY Save, including on an IMAP account, where
 * this whole path must stay inert (`MailRepository.serverIdentities` throws on IMAP by design).
 */
fun mayCreateIdentities(before: StoredAccount, edited: List<StoredIdentity>): Boolean =
    identitiesToCreate(before, edited, serverIdentities = emptyList()).isNotEmpty()

/** `something@something`, with no whitespace and exactly one `@` — see [identitiesToCreate]. */
private fun plausibleIdentityAddress(email: String): Boolean {
    val trimmed = email.trim()
    if (trimmed.isEmpty()) return false
    val parts = trimmed.split("@")
    if (parts.size != 2) return false
    return parts.all { part -> part.isNotEmpty() && part.none { it.isWhitespace() } }
}
