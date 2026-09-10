package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, not a behaviour test. The decisions themselves are executed by
 * `ReachableMailAccountsTest` and `LinkedAccountsDiffTest`; this only reads the plug that carries
 * them, because `MailRepository` needs Room, an Android `Context` and a live session, and `client`
 */
class ForbiddenSubAccountWiringTest {

    private fun bodyLines(file: String, function: String): List<String> =
        DaoQuerySource.mailFunctionBody(file, function)
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun repositoryThenAccounts(): List<String> =
        listOf("MailRepository", "MailAccounts")
            .flatMap { DaoQuerySource.mailSource(it).lines() }
            .map { it.trim() }

    private fun assertBody(function: String, expected: String) {
        assertEquals(
            "MailAccounts.$function is no longer, line for line, what this test was written " +
                "against. Read the new body before updating this: an INSERTED or LENGTHENED line " +
                "is exactly what this pin exists to catch (#129).",
            expected.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            bodyLines("MailAccounts", function),
        )
    }

    @Test fun `reconcile probes every non-primary candidate and hands the store the raw list plus probes`() {
        assertBody(
            "reconcileLinkedAccounts",
            """
            {
            val mailAccountIds = session.mailAccountIds()
            val loginId = accountStore.account(credentials.id)?.loginKey() ?: credentials.id
            if (mailAccountIds.size <= 1 && accountStore.linkedAccounts(loginId).isEmpty()) return
            val discovered = mailAccountIds.map { DiscoveredMailAccount(it, session.accounts[it]?.name.orEmpty()) }
            val probes = discovered.drop(1).associate { candidate ->
            candidate.jmapAccountId to
            runCatching { client.getMailboxes(session, candidate.jmapAccountId, auth) }.rethrowIfCancelled()
            }
            val pruned = runCatching { accountStore.reconcileLinkedAccounts(loginId, discovered, probes) }
            .getOrDefault(emptyList())
            pruned.forEach { prunedId ->
            // App-layer teardown first (notification baselines); each step best-effort so one
            // failure never leaves the rest of a revoked account behind.
            onAccountPruned?.let { hook -> runCatching { hook(prunedId) } }
            bgScope.launch {
            // One runCatching PER delete: a failure in one table must not leave the
            // remaining tables' rows of a revoked account behind.
            runCatching { emailDao.deleteForAccount(prunedId) }
            runCatching { emailFtsDao.clearAccount(prunedId) }
            runCatching { emailBodyDao.deleteForAccount(prunedId) }
            runCatching { mailboxDao.deleteForAccount(prunedId) }
            runCatching { snoozedDao.deleteForAccount(prunedId) }
            }
            }
            }
            """,
        )
    }

    /**
     * The probe must come from the head of the candidate list down, never over the whole of it:
     */
    @Test fun `the probed set starts after the primary`() {
        val probeLines = bodyLines("MailAccounts", "reconcileLinkedAccounts").filter { "getMailboxes" in it || "drop(" in it }
        assertEquals(
            "the #129 probe no longer reads as 'every candidate after the head, one Mailbox/get " +
                "each, each in its own runCatching'.",
            listOf(
                "val probes = discovered.drop(1).associate { candidate ->",
                "runCatching { client.getMailboxes(session, candidate.jmapAccountId, auth) }.rethrowIfCancelled()",
            ),
            probeLines,
        )
    }

    /** The early return stays ahead of the probe: a login with no sub-account costs no request. */
    @Test fun `the early return still sits in front of the probe`() {
        val lines = bodyLines("MailAccounts", "reconcileLinkedAccounts")
        val guard = lines.indexOf(
            "if (mailAccountIds.size <= 1 && accountStore.linkedAccounts(loginId).isEmpty()) return",
        )
        val probe = lines.indexOfFirst { "getMailboxes" in it }
        assertTrue("the single-account early return is gone from reconcileLinkedAccounts", guard >= 0)
        assertTrue("nothing probes any more in reconcileLinkedAccounts", probe >= 0)
        assertTrue("the probe now runs before the single-account early return", guard < probe)
    }

    /**
     * The add-flow entry point reuses the cached session and its auth, so surfacing
     * sub-accounts right after an add costs no token work and no second session fetch.
     */
    @Test fun `the after-add path reuses the cached session's own auth`() {
        assertBody(
            "reconcileLinkedAccountsAfterAdd",
            """
            {
            runCatching {
            val credentials = accountStore.credentials(id) ?: return
            val cached = host.cachedJmapSession()
            ?.takeIf { it.credentials.server == credentials.server && it.credentials.username == credentials.username }
            ?: return
            // The cached session's own auth, so the #129 probe costs no token work of its own.
            reconcileLinkedAccounts(credentials, cached.session, cached.auth)
            }.rethrowIfCancelled()
            }
            """,
        )
    }

    /**
     * Order, not the whole body: the mention list below sees only the text and its rank, so moving
     */
    @Test fun `the session accessor reconciles the linked accounts before it uses the session`() {
        val lines = bodyLines("MailRepository", "jmapSession")
        val reconcile = lines.indexOf("accounts.reconcileLinkedAccounts(credentials, session, auth)")
        val use = lines.indexOfFirst { "client.getMailboxes(" in it }
        assertTrue(
            "jmapSession() no longer calls accounts.reconcileLinkedAccounts(credentials, session, auth) — that " +
                "call is what makes the #129 filter and the #31 prune run at all.",
            reconcile >= 0,
        )
        assertTrue("jmapSession() no longer calls client.getMailboxes( — read the new body.", use >= 0)
        assertTrue(
            "the reconcile now sits BELOW jmapSession()'s own getMailboxes: the session is used before " +
                "the sub-accounts are reconciled.",
            reconcile < use,
        )
    }

    /**
     * Every mention of the reconcile in the file, declaration and call sites alike, with their
     */
    @Test fun `the reconcile is declared once and called from exactly these two places`() {
        val mentions = repositoryThenAccounts()
            .filter { Regex("""\breconcileLinkedAccounts\(""").containsMatchIn(it) }
        assertEquals(
            "a call to MailAccounts.reconcileLinkedAccounts was added, removed or given other " +
                "arguments. The jmapSession() one is what makes the fix run at all (#129) and what " +
                "makes revocations prune (#31); the after-add one is what the add flow shows.",
            listOf(
                "accounts.reconcileLinkedAccounts(credentials, session, auth)",
                "internal suspend fun reconcileLinkedAccounts(",
                "val pruned = runCatching { accountStore.reconcileLinkedAccounts(loginId, discovered, probes) }",
                "reconcileLinkedAccounts(credentials, cached.session, cached.auth)",
            ),
            mentions,
        )
    }

    /**
     * Both `runCatching`s of this path now span a suspension point, and a bare one turns a
     * cancellation into a normal return: the probe would be filed as "this account failed, so keep
     * it", and `ConnectViewModel` (`:249` / `:680`) would write `ConnectState.Connected` on the
     * next line, for a screen the user has just left, its own `catch (cancelled: ...)` never
     * reached. The decision is executed in `CancellationTest`; the plug is read here.
     */
    @Test fun `both runCatchings of this path re-throw a cancellation`() {
        val guarded = repositoryThenAccounts()
            .filter { "rethrowIfCancelled()" in it }
        assertEquals(
            "a runCatching of the #129 path no longer re-throws cancellation, or another one was " +
                "added without it.",
            listOf(
                ".rethrowIfCancelled()",
                "runCatching { note(unarmedNote(failure)) }.rethrowIfCancelled()",
                "runCatching { client.getMailboxes(session, candidate.jmapAccountId, auth) }.rethrowIfCancelled()",
                "}.rethrowIfCancelled()",
            ),
            guarded,
        )
    }

    /**
     * And nothing else in the file may hand the store a candidate list: a second call site would be
     */
    @Test fun `the store's reconcile is called from exactly one place, with the unfiltered list and the probes`() {
        val calls = repositoryThenAccounts()
            .filter { "accountStore.reconcileLinkedAccounts(" in it }
        assertEquals(
            "MailAccounts now calls AccountStore.reconcileLinkedAccounts somewhere else, or with " +
                "something other than (loginId, the raw discovered list, probes) — filtering the " +
                "list here is what turns a passing 'forbidden' into a prune (#129 vs #31).",
            listOf(
                "val pruned = runCatching { accountStore.reconcileLinkedAccounts(loginId, discovered, probes) }",
            ),
            calls,
        )
    }
}
