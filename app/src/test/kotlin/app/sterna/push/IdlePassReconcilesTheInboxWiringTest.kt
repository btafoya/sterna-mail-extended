package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: [PushService] is an Android service and `MailRepository` is not
 * instantiable on the JVM, so the wake of an IDLE cannot be driven here. What it decides is executed
 * elsewhere: the serialisation by [OnePassAtATimeTest], the window by [PullWindowTest]. This pins the
 * wiring of the two (#278): a message deleted from another client leaves the list only through the
 * pull's reconcile, the background pass upserting without ever retiring a row on IMAP.
 */
class IdlePassReconcilesTheInboxWiringTest {

    @Test fun `the IMAP wake reconciles the inbox, after the notification pass, under the same runCatching`() {
        assertEquals(
            "onAccountChanged must run FetchAndNotify first (the banner is decided against the " +
                "baseline before anything moves the rows) and then, on IMAP only, the inbox " +
                "reconcile, both inside one runCatching so a failed reconcile is logged like a " +
                "failed pass and never escapes the service's scope.",
            listOf(
                "runCatching {",
                "FetchAndNotify.run(",
                "this,",
                "credentials,",
                "includeExtras = credentials.protocol != MailProtocol.IMAP,",
                ")",
                "if (credentials.protocol == MailProtocol.IMAP) reconcileImapInbox(this, credentials)",
                "}.onFailure { Log.e(TAG, \"onAccountChanged failed\", it) }",
            ),
            statementsOf("private suspend fun onAccountChanged(credentials: AccountCredentials) {"),
        )
    }

    @Test fun `the reconcile is the pull's own call, on the inbox by id, with the account's window`() {
        assertEquals(
            "reconcileImapInbox must call MailRepository.refresh on the inbox NAMED by id (a null " +
                "id bounds no eviction, #165), with the limit and prune cutoff pullWindowArgs takes " +
                "off the account's SyncWindow, and drop a sign-out the way FetchAndNotify does.",
            listOf(
                "val container = (context.applicationContext as Application).container",
                "val inboxId = container.mailRepository.cachedInboxMailboxId(credentials) ?: return",
                "val (limit, pruneBefore) = pullWindowArgs(container.accountStore.syncWindow(credentials.id), System.currentTimeMillis())",
                "try {",
                "container.mailRepository.refresh(credentials, inboxId, limit, pruneBefore)",
                "} catch (gone: AccountGoneException) {",
                "val cause = accountGoneCause(credentials.id, container.accountStore.accountsUnreadable(), passKind = \"inbox reconcile\")",
                "Log.i(\"PushService\", \"IDLE wake: \$cause, dropped\", gone)",
                "}",
            ),
            topLevelStatementsOf(
                "app/src/main/kotlin/app/sterna/push/IdleInboxReconcile.kt",
                "suspend fun reconcileImapInbox(context: Context, credentials: AccountCredentials) {",
            ),
        )
    }

    @Test fun `every wake goes through the one-pass-at-a-time gate, keyed by login`() {
        val source = source()
        assertEquals(
            "onChanged must hand the group's pass to OnePassAtATime under the login's key, not " +
                "launch it bare: our own deletes wake the IDLE, and each wake would race another " +
                "pass on the same notification baseline.",
            1,
            source.split("onChanged = { if (gen == generation) passes.request(loginId) { group.forEach { onAccountChanged(it) } } },").size - 1,
        )
        assertEquals(
            "onAccountChanged must be called from the gate only",
            2,
            source.split("onAccountChanged(").size - 1,
        )
        assertTrue(
            "the gate must share the service's scope, which onDestroy cancels",
            "private val passes = OnePassAtATime(scope)" in source,
        )
    }

    private fun statementsOf(signature: String): List<String> {
        val lines = source().lines()
        val start = lines.indexOfFirst { it.trim() == signature }
        assertTrue("signature not found in PushService: $signature", start >= 0)
        val body = lines.drop(start + 1).takeWhile { it != "    }" }
        return body.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
    }

    private fun topLevelStatementsOf(path: String, signature: String): List<String> {
        val lines = File(repoRoot, path).readLines()
        val start = lines.indexOfFirst { it.trim() == signature }
        assertTrue("signature not found in $path: $signature", start >= 0)
        val body = lines.drop(start + 1).takeWhile { it != "}" }
        return body.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
    }

    private fun source(): String =
        File(repoRoot, "app/src/main/kotlin/app/sterna/push/PushService.kt").readText()

    private companion object {
        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
