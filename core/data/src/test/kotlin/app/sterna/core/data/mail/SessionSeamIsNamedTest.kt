package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test. The guard cannot be executed here: filling the cache needs a
 * real `fetchSession`, and `JmapClient` is a concrete class with no seam to stand in for it, so
 * `MailRepository` cannot be driven through a warm cache in a JVM test. What the guard buys is the
 * single session per gesture: were it to fall, every gesture would fetch a session and run
 * `reconcileLinkedAccounts` twice, and a sub-account prune could run twice for one gesture (#129).
 *
 * The names are the subject. The session cache is what the repository hands to a caller that must
 * not open one of its own, so its declaration site, its write sites and its type all have to stay
 * countable from the source.
 */
class SessionSeamIsNamedTest {

    private fun bodyLines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function)
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun assertBody(function: String, expected: String) {
        assertEquals(
            "MailRepository.$function is no longer, line for line, what this test was written " +
                "against. Read the new body before updating this: an INSERTED or LENGTHENED line " +
                "is exactly what this pin exists to catch.",
            expected.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            bodyLines(function),
        )
    }

    /** All four files: the drafts, the folders and the accounts were handed the session through the
     *  seam, never a second one of their own. */
    private fun mentions(symbol: String): List<String> =
        listOf("MailRepository", "MailDrafts", "MailFolders", "MailAccounts")
            .flatMap { DaoQuerySource.mailSource(it).lines() }
            .map { it.trim() }
            .filter { Regex("""\b$symbol\b""").containsMatchIn(it) }

    /**
     * The cache seam itself: the early return on a warm cache, the identity of the account it is
     * warm for, and the single write-back. Drop any of the three and the session stops being cached.
     */
    @Test fun `the session accessor returns the cache when it is warm and writes it back when it is not`() {
        assertBody(
            "jmapSession",
            """
            {
            cachedSession?.let { if (it.credentials == credentials) return it }
            val auth = jmapAuth(credentials)
            val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
            val accountId = jmapAccountIdFor(credentials, session)
            accounts.reconcileLinkedAccounts(credentials, session, auth)
            accounts.refreshServerIdentities(session, accountId, credentials.id, auth)
            val mailboxes = client.getMailboxes(session, accountId, auth)
            val roles = jmapRolesToMailboxId(mailboxes, credentials.specialFolders)
            return JmapAccountSession(credentials, session, accountId, auth, roles, mailboxes).also { cachedSession = it }
            }
            """,
        )
    }

    /**
     * The uncached path, the one callers loop over several accounts with. It neither reads nor
     * writes the cache: a cache write here would leave the last account of a loop cached as if a
     * gesture had asked for it, and the next gesture on another account would read that one's ids.
     */
    @Test fun `the per-account resolve neither reads nor writes the cache`() {
        assertBody(
            "resolveJmapAccount",
            """
            {
            val auth = jmapAuth(credentials)
            val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
            val accountId = jmapAccountIdFor(credentials, session)
            val mailboxes = client.getMailboxes(session, accountId, auth)
            return ResolvedJmapAccount(session, accountId, auth, mailboxes)
            }
            """,
        )
    }

    /**
     * One declaration and exactly these write sites. A new assignment anywhere else is a second
     * owner of the cache, and two owners is how a gesture ends up reading another account's ids.
     */
    @Test fun `the cache is declared once and written from exactly these places`() {
        assertEquals(
            "a line naming MailRepository.cachedSession was added, removed or reworded. This is " +
                "the repository's whole JMAP session cache: read the new line before updating " +
                "this, a second write site is what the pin exists to catch.",
            listOf(
                "private var cachedSession: JmapAccountSession? = null",
                "override fun cachedJmapSession(): JmapAccountSession? = cachedSession",
                "cachedSession = JmapAccountSession(",
                """cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to byName), ctx.mailboxes)""",
                """cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to id), ctx.mailboxes)""",
                """cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("trash" to byName), ctx.mailboxes)""",
                """cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("trash" to id), ctx.mailboxes)""",
                "cachedSession = null",
                "cachedSession?.let { if (it.credentials == credentials) return it }",
                "return JmapAccountSession(credentials, session, accountId, auth, roles, mailboxes).also { cachedSession = it }",
            ),
            mentions("cachedSession"),
        )
    }

    /**
     * Every mention of the session type, its declaration, the parameters it is passed through, and
     * the sites that build one. Two construction sites feed the cache and four rewrite it with one
     * more resolved role; a third builder would be a session nobody asked the accessor for.
     */
    @Test fun `the session type is built only where the cache is filled or rewritten`() {
        assertEquals(
            "a line naming MailRepository.JmapAccountSession was added, removed or reworded. A NEW " +
                "construction site is a second session opened behind the accessor's back; read the " +
                "new line before updating this.",
            listOf(
                "class JmapAccountSession(",
                "private var cachedSession: JmapAccountSession? = null",
                "override fun cachedJmapSession(): JmapAccountSession? = cachedSession",
                "cachedSession = JmapAccountSession(",
                "private suspend fun originalSendersOf(ctx: JmapAccountSession, emails: List<Email>): Map<String, OriginRead>? = try {",
                "private suspend fun jmapMoveAll(ctx: JmapAccountSession, emailIds: List<String>, target: String): BulkResult {",
                "private suspend fun jmapDestroyAll(ctx: JmapAccountSession, emailIds: List<String>): BulkResult {",
                "ctx: JmapAccountSession,",
                "private suspend fun archiveMailboxId(ctx: JmapAccountSession): String? {",
                """cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to byName), ctx.mailboxes)""",
                "private suspend fun createArchiveFolder(ctx: JmapAccountSession): String {",
                """cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to id), ctx.mailboxes)""",
                "private suspend fun trashMailboxId(ctx: JmapAccountSession): String? {",
                """cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("trash" to byName), ctx.mailboxes)""",
                "private suspend fun createTrashFolder(ctx: JmapAccountSession): String {",
                """cachedSession = JmapAccountSession(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("trash" to id), ctx.mailboxes)""",
                "override suspend fun jmapSession(credentials: AccountCredentials): JmapAccountSession {",
                "return JmapAccountSession(credentials, session, accountId, auth, roles, mailboxes).also { cachedSession = it }",
                // MailDrafts, which only ever receives one through the seam.
                "suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession",
                "private suspend fun jmapDraftReceiptRequested(ctx: MailRepository.JmapAccountSession, emailId: String): Boolean? {",
                // MailFolders, whose one edge back into the repository is that same seam.
                "suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession",
                // MailAccounts: the seam, and the read of the cache its after-add reconcile reuses.
                "suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession",
                "fun cachedJmapSession(): MailRepository.JmapAccountSession?",
            ),
            mentions("JmapAccountSession"),
        )
    }

    /**
     * The other site that fills the cache, the one every sync passes through, argument by argument.
     * The opening line alone proves nothing: were `credentials` handed anything but the credentials
     * the caller presented, the entry would be one no caller can ever be recognised as, the warm
     * return of `jmapSession` would miss every time, and each gesture would pay a fresh session and
     * a second `reconcileLinkedAccounts`, which is a sub-account purge that can run twice (#129).
     * The role map is pinned for the same reason: an empty one creates a second Trash folder.
     */
    @Test fun `the sync path fills the cache with the account it was asked for`() {
        val lines = DaoQuerySource.mailSource("MailRepository").lines().map { it.trim() }
        val open = lines.indexOf("cachedSession = JmapAccountSession(")
        assertEquals(
            "the cache write of MailRepository.refresh is no longer, line for line, what this test " +
                "was written against. Read the new arguments before updating this: the account the " +
                "entry is filed under and the role map it carries are what the pin is for.",
            listOf(
                "cachedSession = JmapAccountSession(",
                "credentials = credentials,",
                "session = session,",
                "accountId = accountId,",
                "auth = auth,",
                "rolesToMailboxId = jmapRolesToMailboxId(mailboxes, credentials.specialFolders),",
                "mailboxes = mailboxes,",
                ")",
            ),
            if (open < 0) listOf("no line reads exactly 'cachedSession = JmapAccountSession('")
            else lines.subList(open, open + 8),
        )
    }
}
