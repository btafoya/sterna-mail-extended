package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. The decision itself — which account is already installed —
 * is EXECUTED next door by `AccountIdentityTest` (`accountKeyOf`, `resolveExistingLogin`). This
 * only reads the plug that carries it into the two OAuth add paths, because `MailRepository` needs
 * Room, an Android `Context` and a live session and cannot be built in a JVM test.
 *
 * What it exists for. Both paths hand the store an account and then tell the screen whether THIS
 * add is what created it. Get that backwards and the sentence is inverted on every single OAuth
 * sign-in: a first, genuine add says "Account already set up. Connection refreshed.", and a
 * re-add — the defect the whole branch is about, seen on the bench — says "Account added" over an
 * account nobody added. Nothing else in the suite would go red: no JVM test builds this class.
 *
 * Whole trimmed lines, as a CONTIGUOUS block, never `contains`. A fragment check is blind to
 * everything that lengthens the line, and `… == null || true` is exactly that.
 */
class OAuthAddIdentityWiringTest {

    private fun bodyLines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function)
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }

    /**
     * [expected] must appear in the body of [function] as a CONTIGUOUS run of whole trimmed lines.
     * Anchored on its first line and compared line for line, so a changed argument, an added
     * elvis or a swapped identifier lands in the diff instead of slipping under a substring.
     */
    private fun assertBlock(function: String, why: String, expected: List<String>) {
        val start = bodyLines(function).indexOf(expected.first())
        val found = if (start < 0) emptyList() else bodyLines(function).drop(start).take(expected.size)
        assertEquals(why, expected, found)
    }

    private fun assertAsks(function: String, why: String, expected: List<String>) =
        assertBlock(
            function,
            "MailRepository.$function must ask the shared identity, BEFORE the store writes, " +
                "whether this add creates the account — on the very values it then hands to " +
                "addOAuth. $why",
            expected,
        )

    /**
     * JMAP: the endpoint is the host, the username is the address the request carried. Asked
     * before `accountStore.addOAuth`, because afterwards the answer is always "it exists" — the
     * store has just refreshed the login in place.
     *
     * On BOTH keys this route has written for this address, IN THE SAME ORDER the store resolves
     * them: the host this version resolves, then the host the OAuth document came from, which is
     * what 1.5.0 and 1.5.1 stored. Asked on the first alone while the store looks under both, the
     * screen says "account added" over an account the store just refreshed — the sentence 1.5.1
     * had just put right.
     */
    @Test fun `the JMAP OAuth add asks whether the account is already installed`() {
        assertAsks(
            "addOAuthAccount",
            "Asked after the store, or on the wrong pair, and a re-add of the same mailbox ends " +
                "the way a first sign-in does: nothing said, and one row on a phone where the " +
                "reader believes she just added a second account. Asked on `host` — the host the " +
                "OAuth document came from — it answers about an identity the store is not given: " +
                "stored under the OAuth document's host, the same mailbox added by password and " +
                "then by OAuth makes two accounts (#55). Asked on the resolved host ALONE, it " +
                "answers about only one of the two keys the store now looks under, and an account " +
                "OAuth-added under 1.5.0 or 1.5.1 is announced as newly added.",
            listOf(
                "val created = resolveExistingLoginAmong(",
                "accountStore.accounts(),",
                "listOf(",
                """accountKeyOf(MailProtocol.JMAP, server, "", email),""",
                """accountKeyOf(MailProtocol.JMAP, host, "", email),""",
                "),",
                ") == null",
            ),
        )
    }

    /**
     * IMAP: an IMAP account is keyed on its IMAP host (its `server` is blank), and on the address
     * the `id_token` named — the one the store is given — never the alias the user typed.
     */
    @Test fun `the Microsoft OAuth IMAP add asks on the values the store is given`() {
        assertAsks(
            "addOAuthImapAccount",
            "Keyed on the typed address or on a blank server, this path answers about an account " +
                "that is not the one it is about to write, and the Outlook sign-in toast then " +
                "says the opposite of what happened.",
            listOf(
                "val created = resolveExistingLogin(",
                "accountStore.accounts(),",
                """accountKeyOf(MailProtocol.IMAP, "", provider.imap.host, username),""",
                ") == null",
            ),
        )
    }

    /**
     * And the answer must leave the function. `return created` is the whole reason these two
     * stopped returning `Unit`; dropped for a literal, the screens above are told the same thing
     * every time and the guard is decorative.
     */
    @Test fun `both OAuth adds return what they found, not a literal`() {
        val offenders = listOf("addOAuthAccount", "addOAuthImapAccount").filterNot { function ->
            bodyLines(function).contains("return created")
        }
        assertEquals(
            "each OAuth add path must end on `return created`. Returning a literal tells every " +
                "screen above the same story on every sign-in — a first add reported as a re-add, " +
                "or a re-add reported as a creation.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The host the account is STORED under is not the host the tokens came from. The password
     * path resolves the server by probing `Jmap.autodiscoverHosts` by rank and keeps the apex;
     * the OAuth candidate list drops the apex, so the document is fetched from `mail.<domain>`.
     * Persist that and one mailbox added both ways is two accounts on the phone (#55) — two
     * inboxes, two badges, two sets of notifications. So this path must run the SAME resolution
     * before it stores, and fall back on the document host only when it yields nothing.
     */
    @Test fun `the JMAP OAuth add resolves the server the password path would have stored`() {
        assertBlock(
            "addOAuthAccount",
            "MailRepository.addOAuthAccount must resolve the server with the shared autodiscovery " +
                "before it stores anything, falling back on the OAuth document's host via " +
                "oauthServerToStore. Stored under the OAuth document's host, the same mailbox " +
                "added by password and then by OAuth makes two accounts (#55). And the block " +
                "starts at the token validation because the ORDER is the point: moved above it, " +
                "three guessed hosts get probed with a token nothing has proved yet. The guard " +
                "line is pinned whole, so inverting it to !oauthHostWasGuessed(…) lands here.",
            listOf(
                """client.fetchSession(Jmap.sessionUrlFor(host), BearerAuth(tokens.accessToken)).mailAccountId()""",
                """?: error("This user has no JMAP mail account.")""",
                "val guessedHost = oauthHostWasGuessed(host, Jmap.autodiscoverHosts(email))",
                "val server = if (guessedHost) {",
                "oauthServerToStore(",
                """discoverJmapServer(email, password = "", token = tokens.accessToken),""",
                "host,",
                ")",
            ),
        )
    }

    /**
     * The other half of the fence. The manual form's OAuth button and its password button read
     * the SAME field, and the password one stores what was typed verbatim
     * (`ConnectViewModel.finishJmapConnect` → `accountStore.add(server, …)`). A host the reader
     * dictated must therefore reach the store untouched, or `jmap.example.org` typed on both
     * buttons files one mailbox as two accounts — #55, remade by the fix for #55.
     */
    @Test fun `the JMAP OAuth add keeps a host the reader dictated verbatim`() {
        assertBlock(
            "addOAuthAccount",
            "MailRepository.addOAuthAccount must resolve ONLY a host autodiscovery guessed; the " +
                "other branch hands the store `host` unchanged. Resolve unconditionally and the " +
                "manual form's OAuth button files the account somewhere else than its own " +
                "password button, from the same typed field (#55).",
            listOf(
                "val server = if (guessedHost) {",
                "oauthServerToStore(",
                """discoverJmapServer(email, password = "", token = tokens.accessToken),""",
                "host,",
                ")",
                "} else {",
                "host",
                "}",
            ),
        )
    }

    /**
     * A resolution that answers nothing writes the account under the old host: #55 reproduced
     * exactly, and on a server we do not own success and silent failure look identical. One line
     * of log is what tells them apart. The host, never the address.
     */
    @Test fun `a silent resolution leaves a line to read`() {
        assertBlock(
            "addOAuthAccount",
            "MailRepository.addOAuthAccount must log when the guard passed and autodiscovery " +
                "resolved nothing, because the account is then stored under the OAuth document's " +
                "host — #55 as it was, with nothing anywhere to say so.",
            listOf(
                "if (guessedHost && server == host) {",
                """android.util.Log.w("MailRepository", "OAuth add: nothing autodiscovered for ${'$'}host; stored as-is (#55)")""",
                "}",
            ),
        )
    }

    /**
     * And the resolved host must be what the store is GIVEN. Resolving it and then writing
     * `server = host` anyway is the defect in full, with a decoy line above it that reads right.
     */
    @Test fun `the JMAP OAuth add stores the resolved server, not the document host`() {
        assertBlock(
            "addOAuthAccount",
            "MailRepository.addOAuthAccount must hand accountStore.addOAuth the RESOLVED server. " +
                "Stored under the OAuth document's host, the same mailbox added by password and " +
                "then by OAuth makes two accounts (#55).",
            listOf(
                "val id = accountStore.addOAuth(",
                "server = server,",
                "username = email,",
            ),
        )
    }

    /**
     * And the store must be told the OTHER host this route has filed this address under. An
     * account OAuth-added by 1.5.0 or 1.5.1 sits under `mail.<domain>`; this version resolves
     * `<domain>` and would find nothing there. Dropping `documentHost = host` is exactly the
     * mutation that reopens the duplicate — on the phone of everyone who already has an OAuth
     * account, at the first re-authentication, which is a routine gesture when a refresh token
     * expires. Pinned with the two argument lines above it so the check cannot pass by matching
     * a bare `)`.
     */
    @Test fun `the JMAP OAuth add tells the store which host 1_5_x filed this account under`() {
        assertBlock(
            "addOAuthAccount",
            "MailRepository.addOAuthAccount must pass accountStore.addOAuth the host the OAuth " +
                "document came from. Without it, an account OAuth-added under 1.5.0 or 1.5.1 " +
                "takes a SECOND line at the first re-add after the update — the fix for #55 " +
                "manufacturing the duplicate of #55.",
            listOf(
                "tokenEndpoint = metadata.tokenEndpoint,",
                "clientId = Jmap.OAUTH_CLIENT_ID,",
                "documentHost = host,",
                ")",
            ),
        )
    }

    /**
     * Sub-account reconciliation runs on a CREATION only — the same rule the two neighbouring
     * routes already carry (`ConnectViewModel.finishTokenConnect` / `finishJmapConnect`, both
     * written `if (created) …`), and this path had it nowhere.
     *
     * A re-add now lands on the login that was already there — with whatever shared sub-accounts
     * it carries. `diffLinkedAccounts` derives its evictions from the RAW discovered list
     * (`AccountStore`), so a session that lists the login's own account but not the shares
     * prunes every sub-account and purges its five tables: a real shared mailbox deleted with its
     * cache, which is the destruction #129 exists to prevent. Ungated, that becomes reachable from
     * the Add account screen — and the earlier volets of this branch are precisely what make an
     * OAuth add LAND on an existing login, so this path is now walked.
     *
     * Nothing is lost by the gate: the call is an optimisation that reuses the cached session so
     * the accounts screen is complete without a round trip, and the `connect()` hook reconciles
     * anyway (see the KDoc of `reconcileLinkedAccountsAfterAdd`).
     *
     * Whole trimmed line, never `contains`: `reconcileLinkedAccountsAfterAdd(` matches the gated
     * and the ungated call alike, which is the whole difference this rule is about.
     */
    @Test fun `sub-account reconciliation only runs when the OAuth add created the account`() {
        val gated = "if (created) reconcileLinkedAccountsAfterAdd(id)"
        val offenders = bodyLines("addOAuthAccount")
            .filter { RECONCILE.containsMatchIn(it) && it != gated }
        assertEquals(
            "MailRepository.addOAuthAccount must call reconcileLinkedAccountsAfterAdd behind " +
                "`if (created)`, exactly as the two routes in ConnectViewModel do. Ungated, an " +
                "OAuth re-add that lands on a login carrying SHARED MAILBOXES hands the #129 diff " +
                "a session that may not list the shares: every sub-account is deleted and its " +
                "five tables purged — a real shared mailbox gone, cache included, with nothing to " +
                "restore it from. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    private companion object {
        /** Any call of the #129 reconcile, gated or not — the test compares the WHOLE line. */
        val RECONCILE = Regex("""reconcileLinkedAccountsAfterAdd\(""")
    }
}
