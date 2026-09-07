package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 * [app.sterna.ui.inbox.ConversationScopeWiringTest]: it reads a file as text and proves nothing
 * about what runs. `ConnectViewModel` is an `AndroidViewModel` wired to the app container, so this
 * module (no Robolectric, no instrumented tests) cannot instantiate it. The decision it now takes
 * is covered for real in [AccountAddPrimingTest]; what is left to hold is that the three add paths
 * actually go through that decision, and reading the source is the only way to hold it.
 *
 * The rule, from #121: an add path must never hand the repository credentials it built itself,
 * because those carry no account id until the account exists — and priming the cache with them
 * writes a page of inbox rows under `accountId = ""`. So this file may contain exactly ONE call to
 * `mailRepository.refresh(`, inside [ConnectViewModel.primeInbox], reached only through
 * `addAccountThenPrime`, which stamps the real id on the credentials it primes with.
 *
 * What it does NOT do: it cannot tell that `primeInbox` is passed the right thing at runtime, nor
 * that the account store persisted anything. It only makes the old shape — build credentials,
 * refresh, then add — impossible to write back without a red test.
 */
class ConnectPrimingWiringTest {

    /** The three add paths, by the function each one lives in. */
    private val addPaths = listOf("finishJmapConnect", "finishTokenConnect", "connectImap")

    /**
     * The identity each add path must ask about, spelled out whole. The ARGUMENTS are the rule: an
     * IMAP account is recognised by its IMAP host (its `server` is blank), a JMAP one by its
     * server, and a route that hands over the wrong pair is told "this account is new" about an
     * account it is about to refresh — the silence this change exists to end.
     */
    private val addGuards = mapOf(
        "finishTokenConnect" to
            """val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.JMAP, resolved, "", address)) == null""",
        "finishJmapConnect" to
            """val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.JMAP, server, "", username)) == null""",
        "connectImap" to
            """val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.IMAP, "", imapHost, username)) == null""",
    )

    /**
     * Every path that has to SAY which of the two happened — the three add paths plus the device
     * grant, whose account is minted inside `MailRepository` and which therefore appears in none
     * of the rules above. It said nothing at all before this change, and a re-add through the
     * browser round-trip is the one that was actually caught on the bench.
     */
    private val sayingPaths = addPaths + "pollForToken"

    /** The two paths that probe for a server before any account exists. */
    private val discoveryPaths = listOf("connectAuto", "connectToken")

    /**
     * Every route the "Connect" button can take, all six of them. Offline — airplane mode, Wi-Fi
     * off — none of them can succeed, and without a guard the screen spins through the whole
     * connect/read timeout before saying that nothing answered. Five of six is not a fix: the
     * unguarded one keeps that wait for its protocol only.
     */
    private val offlineGuarded = listOf(
        "connect", "connectAuto", "connectToken", "connectOAuth", "connectOutlookOAuth", "connectImap",
    )

    /**
     * And the two sign-ins the import flow puts on this same screen. They answer through the
     * per-account row (`updateSelected`) instead of the screen's state, so they carry the guard in a
     * second shape — see [IMPORT_OFFLINE_GUARD].
     *
     * Neither list can find a route nobody wrote down: this file reads names, it does not
     * enumerate the screen. These two were missed on the first pass for exactly that reason. A new
     * way to sign an account in belongs in one of these lists on the day it is written.
     */
    private val offlineGuardedImports = listOf("submitImportPassword", "startImportOAuth")

    /**
     * What "the guard comes first" is measured against: the two spellings by which a route hands
     * work to something that will try to reach the network — its own coroutine, or an app-scoped
     * driver on the container. A guard placed after either of them has already let the request go.
     */
    private val requestHandoffs = listOf("viewModelScope.launch", "container.")

    /**
     * Every call in THIS FILE that brings an account into existence. It is a vocabulary, not a
     * proof: a creation call this list does not name is a path the rules below read straight past,
     * which is why [`every account-creating call this lint knows still exists in the file`] refuses
     * to let it hold a name that occurs nowhere.
     */
    private val accountCreation = listOf(
        "container.accountStore.add(",
        "container.accountStore.readdImportedAccount(",
        "container.mailRepository.addOAuthAccount(",
    )

    /**
     * The functions of this file that create an account WITHOUT going through the decision, and why
     * each is out of the rules above rather than an oversight:
     *  - `pollForToken` creates through `MailRepository.addOAuthAccount`, which orders validate /
     *    persist / prime for itself in the data layer. Nothing in this module reads that ordering;
     *  - `restoreImportAccount` puts back a `StoredAccount` the store already had (a dismissed
     *    import), so it mints nothing and primes nothing.
     */
    private val creatorsOutsideTheDecision = listOf("pollForToken", "restoreImportAccount")

    /** Those, plus the writes a re-add makes on the account it found. */
    private val accountStoreWrites = accountCreation +
        listOf("container.accountStore.updatePassword(", "container.accountStore.setCurrent(")

    @Test fun `the screen refreshes in exactly one place`() {
        val calls = REFRESH.findAll(source()).map { it.value }.toList()
        assertEquals(
            "ConnectViewModel must call mailRepository.refresh() exactly once, from primeInbox(). " +
                "Every other call is an add path priming the cache with credentials it built " +
                "itself — which is how mail got written under an empty account id (#121). Found:\n" +
                calls.joinToString("\n"),
            1, calls.size,
        )
    }

    @Test fun `that one refresh is the one primeInbox makes`() {
        val body = functionBody("primeInbox")
        assertTrue(
            "primeInbox() must be the function that refreshes: it is the single point where the " +
                "credentials priming the cache are the id-stamped ones. Body was:\n$body",
            REFRESH.containsMatchIn(body),
        )
        assertTrue(
            "primeInbox() must record the inbox meta against the id it primed (saveInboxMetaFor), " +
                "not against whichever account happens to be current. Body was:\n$body",
            "saveInboxMetaFor(" in body,
        )
    }

    @Test fun `every add path goes through the create-then-prime decision`() {
        val offenders = addPaths.filterNot { "addAccountThenPrime(" in functionBody(it) }
        assertEquals(
            "each add path must add the account through addAccountThenPrime(), which is what proves " +
                "the credentials first, then creates the account, then primes the cache under its " +
                "id. A path left out keeps writing mail under no account for its protocol (#121).",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The rule the previous one does NOT hold: it proves each path reaches the decision, nothing
     * about WHAT it hands it. `prime = { primeInbox(probe) }` — one identifier, `probe` being in
     * scope in all three lambdas — compiles, primes the cache under the blank id again, and leaves
     * every other test green, including the refresh lint (no `refresh(` in that lambda) and the
     * decision's own tests (they never call an add path). Only `it` is the credentials the helper
     * stamped with the account id.
     */
    @Test fun `every add path primes with the credentials the decision stamped`() {
        val offenders = addPaths.filterNot { PRIME_LAMBDA in functionBody(it) }
        assertEquals(
            "each add path must pass `$PRIME_LAMBDA`: `it` is the copy addAccountThenPrime stamped " +
                "with the new account id. Handing it the path's own `probe` (or any credentials " +
                "built before the account existed) writes the inbox under accountId = \"\" again, " +
                "which is #121 exactly.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * Priming must be the LAST step, so nothing exists in the store before the credentials are
     * proven. Creating the account first and then calling the helper passes every other rule here:
     * the call is present, the validation lambda is present, `it` is primed. The order is what the
     * fix is, so the order is what this reads.
     */
    @Test fun `no add path writes to the account store before the decision runs`() {
        val offenders = addPaths.mapNotNull { path ->
            val body = functionBody(path)
            val decision = body.indexOf("addAccountThenPrime(")
            val early = accountStoreWrites.filter { call ->
                val at = body.indexOf(call)
                at >= 0 && at < decision
            }
            if (early.isEmpty()) null else "$path: ${early.joinToString()}"
        }
        assertEquals(
            "an add path must not touch the account store before addAccountThenPrime() — every " +
                "store write belongs inside its `persist` lambda, which only runs once " +
                "testConnection() has proven the credentials. Anything earlier leaves an account " +
                "behind on a mistyped password (#121).",
            emptyList<String>(), offenders,
        )
    }

    /**
     * WHAT THIS PROMISES, EXACTLY — and it is narrower than "the account-creating functions of
     * this file are the ones this lint knows about", which is what it used to be called: every
     * occurrence of one of the THREE STRINGS in [accountCreation] sits in a function that has been
     * judged, either as an add path or as a documented exception. That is a vocabulary check, not a
     * proof. A function that creates an account by any other spelling — a local alias, a helper
     * called with the store passed in, a call this list does not name — is read straight past and
     * reported as nothing, exactly as it was before this rule existed.
     *
     * It does NOT promise that these are all the ways the app can create an account. Off this
     * lint's map, and covered by no rule here:
     *  - the OAuth paths, which mint theirs inside `MailRepository` (`addOAuthAccount`, and the
     *    Outlook flow driven by `OutlookSignIn`);
     *  - `AccountStore.reconcileLinkedAccounts`, which creates a linked sub-account for every
     *    shared or delegated mailbox a session exposes (#31) and prunes the ones it no longer does.
     *    It runs on every connect, from several scopes, and it is the path the create-then-prime
     *    ordering leans on for its justification — nothing here reads it;
     *  - `AccountStore.importAccounts`, which is both the `.k9s` import and the RESTORE OF A
     *    SETTINGS BACKUP (`SettingsViewModel`): it adds accounts to the store directly, with fresh
     *    ids and no password.
     */
    @Test fun `every account-creating call this lint names sits in a function it has judged`() {
        val creators = source().lines().withIndex()
            .filter { (_, line) -> accountCreation.any { it in line } }
            .map { (i, _) -> enclosingFunction(i) }
            .distinct()
        assertEquals(
            "a function of ConnectViewModel calls one of the creation functions this lint names " +
                "(${accountCreation.joinToString()}) without being listed here. If it is an add " +
                "path, put it in `addPaths` and make it go through addAccountThenPrime; if it " +
                "deliberately creates one another way, list it in `creatorsOutsideTheDecision` " +
                "with the reason. Leaving it out lets the rules above read right past it. ⚠ And " +
                "note what this does NOT say: a creation written any other way is invisible to " +
                "this rule, so a green run here is not \"nothing else creates an account\".",
            (addPaths + creatorsOutsideTheDecision).sorted(), creators.sorted(),
        )
    }

    /**
     * And the vocabulary must be alive. `container.accountStore.addOAuth(` sat in that list while
     * occurring nowhere in the file: the rule above was reading the file for a string that could
     * not be there, and reported full coverage of the creation paths on the strength of it. A name
     * that matches nothing does not restrict anything — it only makes the promise look wider.
     */
    @Test fun `every account-creating call this lint knows still exists in the file`() {
        val dead = accountCreation.filterNot { it in source() }
        assertEquals(
            "these calls no longer occur in ConnectViewModel, so the exhaustivity rule above is " +
                "searching for nothing on their behalf. Either the path was renamed (update the " +
                "string) or it is gone (drop it) — do not leave a name that cannot match.",
            emptyList<String>(), dead,
        )
    }

    /**
     * Cancellation is not a sign-in failure: leaving the screen while the inbox loads cancels this
     * coroutine, and a `catch (Throwable)` that maps it to [ConnectState.Error] both lies to a
     * screen that is gone and hides a real cancellation from the machinery above.
     */
    @Test fun `every add path lets cancellation through instead of showing it`() {
        val offenders = addPaths.filterNot { "catch (cancelled: CancellationException)" in functionBody(it) }
        assertEquals(
            "each add path must rethrow CancellationException before its catch-all: leaving the " +
                "screen mid-add is not an error to report, and it is not a reason to undo the add.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The same rule, on the two paths that run autodiscovery before any account exists. They used
     * to write `runCatching { discoverJmapServer(…) }.getOrElse { NotFound }`, which swallows the
     * cancellation that leaving the screen raises and then writes [ConnectState.NeedsServer] — a
     * "tell me your server" form nobody asked for, onto a screen that is gone.
     *
     * Three things are read, because a `contains` on `"catch (cancelled: CancellationException)"`
     * alone proved to hold none of them:
     *  - the catch is pinned WITH ITS BODY ([RETHROWS_CANCELLATION]), since keeping the header and
     *    returning `NotFound` from it restores the defect in full and matches a fragment happily;
     *  - no `runCatching` wraps the probe, however the line is spelled;
     *  - `discoverJmapServer` is still called HERE. Without that, the two rules above are satisfied
     *    trivially by moving the call into a helper — the rule would then guard an empty room.
     *
     * Still not held: a second, nested `try` that swallows before this one sees it. Declared debt
     * — a text lint cannot see scope, and `ConnectViewModel` cannot be run in this module.
     */
    @Test fun `neither discovery path turns a cancelled probe into "no server found"`() {
        val offenders = discoveryPaths.filterNot { path ->
            val body = functionBody(path)
            RETHROWS_CANCELLATION.containsMatchIn(body) &&
                !DISCOVERY_RUN_CATCHING.containsMatchIn(body) &&
                "discoverJmapServer(" in body
        }
        assertEquals(
            "each discovery path must run discoverJmapServer() in a try that rethrows " +
                "CancellationException before its catch-all. runCatching{}.getOrElse{} cannot: it " +
                "catches the cancellation too, and the path then reports NotFound — NeedsServer on " +
                "a screen the user has already left.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * Offline, nothing typed on this screen can reach a server, and every route used to find that
     * out the long way: a DNS lookup that never resolves, then the connect timeout, then the read
     * timeout, per candidate host. The guard is three lines and there is no decision to extract
     * from it — [app.sterna.net.hasUsableNetwork] IS the decision.
     *
     * And that decision has NO test of its own: it reads `ConnectivityManager`, so this module
     * (no Robolectric, no instrumented tests) cannot run it. Inverting `hasUsableNetwork` itself
     * leaves this file green. Say that plainly rather than let the rule below imply cover it does
     * not give.
     *
     * Matched as the WHOLE four-line shape, condition included, and anchored at the function
     * body's own indentation. A fragment test is blind to every mutation that lengthens the line
     * (`if (!hasUsableNetwork(app) && false)`); a test that accepts any indentation is blind to the
     * one that wraps the whole guard in a condition —
     * `if (imapPort == 993) { …guard… }` re-arms the timeout on every other port and matched a
     * `[ \t]*` anchor happily.
     */
    @Test fun `every add route refuses to start a request while offline`() {
        val offenders = offlineGuarded.filterNot { OFFLINE_GUARD.containsMatchIn(functionBody(it)) }
        assertEquals(
            "each of these routes must open with the offline guard, spelled exactly, and at the " +
                "top level of the function — not nested inside any condition:\n" +
                "    if (!hasUsableNetwork(app)) {\n" +
                "        _state.value = ConnectState.Error(string(R.string.connect_offline))\n" +
                "        return\n" +
                "    }\n" +
                "A route without it spins on the full connect/read timeout in airplane mode before " +
                "reporting that nothing answered — for its protocol only, which is worse than no " +
                "guard at all because the screen then behaves differently per sign-in method.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * And it must come FIRST. A guard is not a guard once the request is in flight: placed after
     * `viewModelScope.launch {` it runs inside the coroutine that is already probing, and placed
     * after `container.outlookSignIn.start(` it runs after the browser hand-off. Same instrument as
     * the store-write ordering rule above — compare indices, because both spellings are present
     * either way and only their order says which one happened first.
     */
    @Test fun `the offline guard runs before anything is launched`() {
        val offenders = offlineGuarded.mapNotNull { route ->
            val body = functionBody(route)
            val guard = OFFLINE_GUARD.find(body)?.range?.first ?: return@mapNotNull null
            val handoff = requestHandoffs.map { body.indexOf(it) }.filter { it >= 0 }.minOrNull()
            if (handoff == null || guard < handoff) null else "$route: guard at $guard, handoff at $handoff"
        }
        assertEquals(
            "the offline guard must precede the first viewModelScope.launch / container. call of " +
                "its route: after them the probe is already running and the guard only decorates " +
                "the failure. It stays AFTER the busy()/Connecting anti-double-submit check — a " +
                "different rule, and one nothing in this module tests, so do not read this as " +
                "cover for it.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The same symptom, on the two routes that sign in an account an import brought in. They report
     * through the per-account row rather than the screen's state, so they carry the guard in that
     * shape — but the wait they used to take is the identical one.
     *
     * These are the routes that matter MOST offline, not the least: importing a `.k9s` file is an
     * entirely local operation, so it is exactly what someone does with no connection. The screen
     * then opens straight onto the "accounts to sign in" list, whose buttons are these two.
     */
    @Test fun `signing in an imported account refuses to start a request while offline`() {
        val offenders = offlineGuardedImports.filterNot { IMPORT_OFFLINE_GUARD.containsMatchIn(functionBody(it)) }
        assertEquals(
            "each imported-account sign-in must open with the offline guard in its own shape, at " +
                "the top level of the function:\n" +
                "    if (!hasUsableNetwork(app)) {\n" +
                "        updateSelected { it.copy(error = string(R.string.connect_offline)) }\n" +
                "        return\n" +
                "    }\n" +
                "Without it the row spins on the full connect/read timeout and then shows the raw " +
                "exception text — after an import, which is the offline case by construction.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The vocabulary must be alive, for the same reason `container.accountStore.addOAuth(` had to
     * go: a route name that matches nothing restricts nothing, and the rules above would report
     * six guarded routes while reading five. A rename must land here, in red.
     */
    @Test fun `every route this lint guards still exists in the file`() {
        val dead = (offlineGuarded + offlineGuardedImports)
            .filterNot { Regex("""\bfun $it\b""").containsMatchIn(source()) }
        assertEquals(
            "these routes no longer exist in ConnectViewModel, so the offline rules above are " +
                "reading nothing on their behalf. Renamed? update the name here. Gone? drop it — " +
                "never leave a name that cannot match.",
            emptyList<String>(), dead,
        )
    }

    @Test fun `no add path refreshes on its own`() {
        val offenders = addPaths.filter { REFRESH.containsMatchIn(functionBody(it)) }
        assertEquals(
            "an add path must not call refresh() itself: the credentials in scope there are the " +
                "ones it just built, and they carry no account id until the account exists (#121).",
            emptyList<String>(), offenders,
        )
    }

    @Test fun `every add path proves the credentials before persisting anything`() {
        val offenders = addPaths.filterNot { path ->
            val body = functionBody(path)
            "validate = { container.mailRepository.testConnection(it).getOrThrow() }" in body
        }
        assertEquals(
            "each add path must validate with testConnection(), which authenticates WITHOUT writing " +
                "anything. Validating by priming the cache is the bug; not validating at all would " +
                "leave an account behind on a mistyped password.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * REWRITTEN. This test used to read two fragments of the token path's hand-written re-add
     * branch — `container.accountStore.updatePassword(existing.id, token)` and
     * `AddedAccount(existing.id, created = false)` — with `in body`, which is the blindness this
     * repo has paid for three times: any mutation that LENGTHENS those lines matched happily. And
     * the branch itself is gone: the store recognises the account and refreshes it inside a
     * `@Synchronized` add, so the route makes ONE call whether or not the account was there. What
     * is left to hold is that the route no longer writes the account store by hand around it —
     * `updatePassword` + `setCurrent` outside `persist` is a write before the credentials are
     * proven, and it was gated on `authType == API_TOKEN`, so the same address added by password
     * and then by token walked straight past it into a second account.
     */
    @Test fun `the token path leaves the re-add to the store instead of writing it by hand`() {
        val body = functionBody("finishTokenConnect")
        val offenders = body.lines().map { it.trim() }.filter { UPDATE_PASSWORD.containsMatchIn(it) }
        assertEquals(
            "finishTokenConnect must not update a password / set the current account itself: the " +
                "one call it makes (container.accountStore.add) refreshes the account already " +
                "stored under this identity and hands back ITS id, atomically. Doing it here " +
                "again writes the token twice, and the hand-written search it used to sit behind " +
                "filtered on authType — so re-adding by token an address added by password left " +
                "the reader with two rows for one mailbox. Found:\n" + offenders.joinToString("\n") +
                "\nBody was:\n$body",
            emptyList<String>(), offenders,
        )
    }

    /**
     * WHICH ACCOUNT IS ALREADY THERE is one shared decision (`resolveExistingLogin` +
     * `accountKeyOf`), and each route must ASK it, with the identity it is actually adding. The
     * arguments are pinned, not the call: `accountKeyOf(MailProtocol.JMAP, server, "", username)`
     * and `accountKeyOf(MailProtocol.IMAP, "", imapHost, username)` are different questions, and a
     * route that asks the wrong one gets "no, it is new" for an account it is about to refresh.
     *
     * Whole trimmed lines, compared for equality. `contains` on `resolveExistingLogin(` is blind
     * to every mutation that lengthens the line — `… == null || true` being exactly that.
     */
    @Test fun `each add path asks the shared identity whether the account is already there`() {
        val offenders = addGuards.filterNot { (path, required) ->
            functionBody(path).lines().map { it.trim() }.contains(required)
        }.map { (path, required) -> "$path: expected\n  $required" }
        assertEquals(
            "each add path must decide from the shared identity, before it goes to the network, " +
                "whether this add creates the account or lands on one already installed. Without " +
                "it the screen cannot tell the reader which of the two happened, and the sign-in " +
                "ends exactly as a first one does — on a phone that now shows one account where " +
                "she believes she added a second. Missing:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * And the answer must be USED. `created = true` written into `AddedAccount` is the state the
     * whole change removes: it says "this add created the account" on every add, including the
     * ones that refreshed one.
     */
    @Test fun `no add path declares itself the creator regardless of what it found`() {
        val offenders = addPaths.flatMap { path ->
            functionBody(path).lines().map { it.trim() }.filter { CREATED_TRUE.containsMatchIn(it) }
                .map { "$path: $it" }
        }
        assertEquals(
            "an add path must carry the answer it got (created = created), never a hard-coded " +
                "true: with true, a re-add is reported as a creation and the reader is told an " +
                "account was added that was already installed. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * And it must be SAID. The sentence itself is chosen by `accountAddedToast`, executed in
     * [AccountAddedToastTest] — these routes only call it.
     */
    @Test fun `each add path says which of the two happened`() {
        val offenders = sayingPaths.filterNot { path ->
            functionBody(path).lines().map { it.trim() }.contains(SAYS_IT)
        }
        assertEquals(
            "each add path must end on `$SAYS_IT`: the whole point of asking whether the account " +
                "was already there is telling the reader. Without this line a re-add succeeds in " +
                "silence, which is what made the same mailbox be added over and over. Missing " +
                "in:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * Sub-account reconciliation runs on a CREATION only, and this is the rule that keeps it
     * there. A re-add now lands on the login that was already there — with whatever shared
     * sub-accounts it carries — and it may have just moved that login from OAuth onto a password.
     * `diffLinkedAccounts` derives its evictions from the RAW discovered list, so a session that
     * lists the login's own account but not the shares prunes every sub-account and purges its
     * five tables: a real shared mailbox deleted (#129). Ungated, that destruction becomes
     * reachable from the Add account screen.
     *
     * Whole trimmed line: `contains` on `reconcileLinkedAccountsAfterAdd(` matches the gated and
     * the ungated call alike, which is the whole difference.
     */
    @Test fun `sub-account reconciliation only runs when the add created the account`() {
        val gated = "if (created) container.mailRepository.reconcileLinkedAccountsAfterAdd(added.id)"
        val offenders = listOf("finishTokenConnect", "finishJmapConnect").flatMap { path ->
            functionBody(path).lines().map { it.trim() }
                .filter { RECONCILE.containsMatchIn(it) && it != gated }
                .map { "$path: $it" }
        }
        assertEquals(
            "reconcileLinkedAccountsAfterAdd must sit behind `if (created)`. On a re-add it hands " +
                "the #129 diff a session of possibly the other auth family, over a login that " +
                "already has sub-accounts — and eviction there deletes a real shared mailbox with " +
                "its cache. Nothing is lost by the gate: the call is an optimisation and the " +
                "connect() hook reconciles anyway. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * The last stretch, which every one of the four routes above goes through. `accountAddedToast`
     * is executed by a test and hands back the right resource id; NOTHING followed that id to the
     * Toast until this rule. Written `Toast.makeText(app, string(R.string.connect_account_added), …)`
     * the helper puts "Account added" on every re-add — the exact sentence this change removes —
     * and every other test stays green, because the call lines above are untouched and no test in
     * this module can build a ConnectViewModel.
     *
     * Whole trimmed lines, as a contiguous block: `contains` on `Toast.makeText(` is blind to
     * every mutation that lengthens it.
     */
    @Test fun `the toast helper shows the resource it was handed, and names none of its own`() {
        assertEquals(
            "ConnectViewModel.toast must show the resource accountAddedToast chose. Naming a " +
                "resource here, or dropping the call, is the same defect one floor down: a re-add " +
                "reported as \"Account added\", or said nothing about at all.",
            listOf(
                "private fun toast(@StringRes resId: Int) {",
                "Toast.makeText(app, string(resId), Toast.LENGTH_LONG).show()",
                "}",
            ),
            functionBody("toast").lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /**
     * And no route may name the re-add sentence itself. That is the reproach `OAuthWiringTest`
     * already makes of `oauthRanOutMessage`: naming one of the two answers on the spot is taking
     * the decision back from the function that is tested, into a route no test can run.
     */
    @Test fun `no add path picks the re-add sentence itself`() {
        val offenders = sayingPaths.flatMap { path ->
            functionBody(path).lines().map { it.trim() }.filter { REFRESHED_SENTENCE.containsMatchIn(it) }
                .map { "$path: $it" }
        }
        assertEquals(
            "R.string.connect_account_refreshed is one of the two answers accountAddedToast picks " +
                "between. Named inside a route, the choice moves back into a function nothing in " +
                "this module can execute — where \"Account added\" and \"already set up here\" can " +
                "be swapped without a single red test. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * SOURCE LINT — an account that was just added must have its inbox baseline WRITTEN by the add
     * itself, from the page the add already loaded.
     *
     * Without this line the first background pass of a new account is also the first sight of its
     * inbox, and a first sight seeds SILENTLY (`FetchAndNotify` takes `seedsSilently` and calls
     * `NewMailNotifier.seed`). So the first message that arrives after an account is added is
     * swallowed for good: cached, listed, never announced. On IMAP nothing else can write that
     * baseline — `FetchAndNotify.onInboxRefreshed` returns on its first line for IMAP — so the add
     * is the only place left to write it.
     *
     * Whole trimmed lines, as the whole body, in order, and that is the point:
     *  - `contains("seedInboxBaseline(")` would stay green on
     *    `seedInboxBaseline(credentials.id, meta.mailboxName)`, which writes the baseline under a
     *    key no pass ever reads back — the defect intact, with a green lint;
     *  - the ORDER is the rule that says the seed happens after a successful `refresh()`. `refresh`
     *    throws on failure and `addAccountThenPrime` catches that into `primeFailure`; moved above
     *    it, the seed would run on an add whose inbox never loaded, marking a folder "known,
     *    remembering nothing" — and the next pass would then announce the backlog AND unarchive it
     *    server-side. No `runCatching`, no `try` here either: they would restore that order.
     */
    @Test fun `priming an added account seeds its inbox baseline, last`() {
        assertEquals(
            "ConnectViewModel.primeInbox must end by seeding the inbox baseline of the account it " +
                "just primed:\n  seedInboxBaseline(credentials.id, meta.mailboxId)\n" +
                "Without it, the first mail arriving after an account is added is never announced " +
                "(the first background pass seeds silently). With the wrong argument, or seeded " +
                "before the refresh, it is the same defect with a green test.",
            listOf(
                "private suspend fun primeInbox(credentials: AccountCredentials) {",
                "val meta = container.mailRepository.refresh(credentials)",
                "container.accountStore.saveInboxMetaFor(",
                "credentials.id, meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount,",
                ")",
                "seedInboxBaseline(credentials.id, meta.mailboxId)",
                "}",
            ),
            codeLines(functionBody("primeInbox")),
        )
    }

    /**
     * SOURCE LINT, NEGATIVE SPACE — this screen writes ONE baseline, and there is no second.
     *
     * The rule above asks that a line be PRESENT, and a presence check cannot see a line that was
     * ADDED. A seed written anywhere else on this screen — a re-authentication path, an import
     * sign-in, a "refresh the folders" helper — passes every rule in this file and overwrites a
     * LIVE baseline, which swallows every arrival not yet announced: the reported defect, one
     * screen further along.
     *
     * Compared as a LIST of whole lines: an argument swapped (`read.emails`, `meta.mailboxName`) or
     * a second call site added are both a different list. A `contains` would see neither.
     */
    @Test fun `this screen writes exactly one baseline, through the seeding helper`() {
        assertEquals(
            "ConnectViewModel seeds a baseline somewhere new, or with something other than the " +
                "decision's answer. There is exactly one seed point on this screen — " +
                "seedInboxBaseline, reached from primeInbox after a successful refresh — and it " +
                "writes what baselineForAddedInbox returned, never a list it picked itself.",
            listOf("NewMailNotifier.seed(app, accountId, mailboxId, baseline)"),
            codeLinesNaming(code(), "NewMailNotifier.seed("),
        )
    }

    /**
     * SOURCE LINT — and the seed goes through the decision, which is the part
     * [AddedInboxBaselineTest] actually EXECUTES.
     *
     * The two lines this pins whole are the two that cannot be read from the executed test:
     *  - `NewMailNotifier.hasBaseline(app, accountId, mailboxId)` is the question that keeps a
     *    re-add / re-authentication from overwriting a baseline that is already alive. Passed
     *    `false` on the spot, or asked about another key, an add would erase a live baseline and
     *    swallow whatever it had not announced yet;
     *  - `?: return` is what a null MEANS here. Written `?: emptyList()` — the mutation
     *    `FetchAndNotify`'s own lint exists for — the add would seed an EMPTY baseline over the
     *    live one, and the next pass would announce every unread message inside the notifier's age
     *    floor, unarchiving their threads server-side.
     */
    @Test fun `the seeding helper asks the decision, and a null answer writes nothing`() {
        assertEquals(
            "ConnectViewModel.seedInboxBaseline must read the notifier's bounded read, ask " +
                "baselineForAddedInbox whether this add may write a baseline at all, and RETURN " +
                "on null. Any other shape either bypasses the decision or turns \"do not touch " +
                "this baseline\" into \"replace it with nothing\".",
            listOf(
                "private suspend fun seedInboxBaseline(accountId: String, mailboxId: String) {",
                "val read = container.mailRepository.notifyRead(accountId, mailboxId)",
                "val baseline = baselineForAddedInbox(read, NewMailNotifier.hasBaseline(app, accountId, mailboxId))",
                "?: return",
                "NewMailNotifier.seed(app, accountId, mailboxId, baseline)",
                "}",
            ),
            codeLines(functionBody("seedInboxBaseline")),
        )
        assertEquals(
            "baselineForAddedInbox is called from exactly one place — seedInboxBaseline. A second " +
                "caller is how a seed that skipped the decision gets into this file.",
            listOf("val baseline = baselineForAddedInbox(read, NewMailNotifier.hasBaseline(app, accountId, mailboxId))"),
            codeLinesNaming(code(), "baselineForAddedInbox(").filterNot { it.startsWith("internal fun ") },
        )
    }

    // -- reading the file --------------------------------------------------------------------------

    /**
     * The body of a function of [CONNECT_VIEW_MODEL], as text: from its declaration to the line
     * that closes it, by counting braces.
     *
     * Braces are counted raw — no Kotlin parser here — which is sound only as long as the file's
     * string literals carry none. They do not today; if that changes, this reads too far or stops
     * early rather than lying, and the failure message prints the body it read.
     */
    private fun functionBody(name: String): String {
        val lines = source().lines()
        val start = lines.indexOfFirst { Regex("""\bfun $name\b""").containsMatchIn(it) }
        check(start >= 0) {
            "$name() is gone from ConnectViewModel: this lint reads nothing, so rename it here too " +
                "rather than let the rules pass over an empty string."
        }
        val out = StringBuilder()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            out.appendLine(line)
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    /**
     * The name of the function whose declaration is the nearest one at or above [line] — how a rule
     * asks "which function is this call in?" without walking braces. Reading upwards is enough
     * because nothing in this file declares a function inside another one; a local `fun` would make
     * this answer the inner name, which is a wrong answer, not a silent pass.
     */
    private fun enclosingFunction(line: Int): String {
        val lines = source().lines()
        for (i in line downTo 0) {
            DECLARATION.find(lines[i])?.let { return it.groupValues[1] }
        }
        error("no function declaration above line ${line + 1} of ConnectViewModel — is the file still Kotlin?")
    }

    /**
     * The file's CODE, comments cut — the same helper [app.sterna.push.NotifyCandidatesWiringTest]
     * reads with, and needed for the same reason: the comments beside these call sites name the
     * very expressions the rules forbid (`read.emails.map { it.id }`, `?: emptyList()`), so a rule
     * reading the raw text would answer about a sentence instead of about the code.
     */
    private fun code(): String = codeOf(source())

    /** [text]'s code lines, trimmed, comments and blank lines cut. */
    private fun codeLines(text: String): List<String> = codeOf(text).lines().map { it.trim() }

    /** The code lines of [source] naming [needle] — WHOLE lines, never a `contains`, which is
     *  blind to anything a mutation lengthens. */
    private fun codeLinesNaming(source: String, needle: String): List<String> =
        source.lines().map { it.trim() }.filter { needle in it }

    private fun codeOf(text: String): String = text.lines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    private fun source(): String = CONNECT_VIEW_MODEL.readText()

    private companion object {
        const val PATH = "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt"

        /** Repo root, walked up from the module's working directory (as the other source lints do). */
        val CONNECT_VIEW_MODEL: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, PATH).isFile }
                ?.let { File(it, PATH) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )
        }

        val REFRESH = Regex("""mailRepository\.refresh\([^)]*\)""")

        /** Any `runCatching` wrapped around autodiscovery, however the line is spelled. */
        val DISCOVERY_RUN_CATCHING = Regex("""runCatching\s*\{[^}]*discoverJmapServer""")

        /**
         * The cancellation catch WITH its body. Pinning the header alone leaves
         * `catch (cancelled: CancellationException) { DiscoveryResult.NotFound }` green, which is
         * the reported defect back in full while the lint reports the path as fixed.
         */
        val RETHROWS_CANCELLATION = Regex(
            """catch \(cancelled: CancellationException\) \{[^}]*throw cancelled""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** A Kotlin function declaration, and the name it declares. */
        val DECLARATION = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        /**
         * The offline guard, as a whole: every one of its four lines pinned end to end, so that
         * anything added to the condition (`&& false`), any other string shown, or a `return`
         * turned into something that falls through, fails to match instead of hiding inside a
         * `contains`.
         *
         * The indentation is part of the pattern, and it is the point. Eight spaces is the top
         * level of a method body in this file; twelve is one condition deep. Written `[ \t]*` this
         * matched a guard buried inside `if (imapPort == 993) { … }`, which re-arms the whole
         * timeout for every other port while the lint reports the route as guarded. A guard that
         * only runs sometimes is not a guard, and indentation is the only thing in a text lint that
         * can tell the difference.
         */
        val OFFLINE_GUARD = guardPattern(
            """_state\.value = ConnectState\.Error\(string\(R\.string\.connect_offline\)\)""",
        )

        /** The same guard on the import routes, which answer through the row, not the screen. */
        val IMPORT_OFFLINE_GUARD = guardPattern(
            """updateSelected \{ it\.copy\(error = string\(R\.string\.connect_offline\)\) \}""",
        )

        /**
         * The four-line guard around [answer], anchored at a method body's own indentation: 8
         * spaces for the `if` and its closing brace, 12 for the two lines inside it.
         */
        private fun guardPattern(answer: String) = Regex(
            """^ {8}if \(!hasUsableNetwork\(app\)\) \{[ \t]*\r?\n""" +
                """ {12}$answer[ \t]*\r?\n""" +
                """ {12}return[ \t]*\r?\n""" +
                """ {8}\}[ \t]*$""",
            RegexOption.MULTILINE,
        )

        /** The one thing an add path may prime with — see the test that reads it. */
        const val PRIME_LAMBDA = "prime = { primeInbox(it) }"

        /** How an add path says which of the two happened. Whole line, arguments included. */
        const val SAYS_IT = "accountAddedToast(created, null)?.let { toast(it) }"

        /** Forbidden tokens: a substring search is sound here — a longer line still carries them. */
        val CREATED_TRUE = Regex("""created = true""")
        val REFRESHED_SENTENCE = Regex("""\bR\.string\.connect_account_refreshed\b""")
        val UPDATE_PASSWORD = Regex("""container\.accountStore\.(updatePassword|setCurrent)\(""")

        /** Any call of the #129 reconcile, gated or not — the test compares the WHOLE line. */
        val RECONCILE = Regex("""reconcileLinkedAccountsAfterAdd\(""")
    }
}
