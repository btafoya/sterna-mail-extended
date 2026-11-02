package app.sterna.ui.inbox

import app.sterna.core.data.account.SyncWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint for the plug, with the value it carries executed beside it. `InboxViewModel` is an
 * `AndroidViewModel` and this module has neither Robolectric nor instrumented tests, so the one
 * line that turns the setting into a request cannot be run here, only read.
 */
class SyncWindowIsWhatTheRefreshSendsTest {

    // -- the value that travels down that line ------------------------------------------------------

    @Test fun `the window this path reads is the largest the picker offers`() {
        // The weld between the pinned line below and the constants: the number written out here is
        // the settings label ("10,000 messages"), and if either end moves this fails on the path,
        // naming it, as well as in core:data.
        assertEquals(
            "the value `refreshFolder` sends as its refresh limit is no longer the number the " +
                "biggest choice on the settings row promises",
            10_000, SyncWindow.COUNT_10000.limit,
        )
        assertEquals(
            "an account left on \"Everything\" by an older build now caches a number nothing on " +
                "the settings row names",
            10_000, SyncWindow.ALL.limit,
        )
    }

    // -- the plug -----------------------------------------------------------------------------------

    @Test fun `refreshFolder is these statements, and the window is what both requests send`() {
        assertEquals(
            "InboxViewModel.refreshFolder is no longer, line for line, what this test was written " +
                "against. Read the new body before updating it: a number substituted for " +
                "`limit`, or a cap inserted above the call, is exactly what this pin " +
                "exists to catch — and no other test in the repo can see it.",
            """
            {
            val credentials = store.load() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
            val (limit, pruneBefore) = pullWindowArgs(store.syncWindow(credentials.id), System.currentTimeMillis())
            val storedInbox = store.account(credentials.id)?.inboxId
            val first = repo.refresh(credentials, mailboxId, limit, pruneBefore)
            val realigned = realignedInboxId(mailboxId, storedInbox, repo.cachedInboxMailboxId(credentials))
            val updated = if (realigned == null) first else repo.refresh(credentials, realigned, limit, pruneBefore)
            if (updated.mailboxId == storedInbox || realigned != null || mailboxId == null) {
            store.saveInboxMetaFor(credentials.id, updated.mailboxId, updated.mailboxName, updated.accountName, updated.unreadCount)
            runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), credentials, updated.mailboxId) }
            }
            selection.value = Sel.Folder(updated.mailboxId)
            meta.value = Meta(updated.accountName, updated.mailboxName, updated.unreadCount)
            }
            """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            bodyLines("refreshFolder"),
        )
    }

    @Test fun `the age cutoff is derived from the same window, not from a second setting`() {
        // The two halves of one setting: `pruneBefore` is the window's age and `window.limit` its
        // count, and `MailRepository.pruneRetention` requires them to come from the same window
        // (its floor is read as "keep at least this many whatever their age"). Two different
        // sources here would let the prune delete under a floor the fetch never used.
        val body = bodyLines("refreshFolder")
        assertEquals(
            "refreshFolder reads the sync window more than once — the two halves must come from " +
                "one read, or they can disagree",
            1, body.count { "store.syncWindow(" in it },
        )
        assertTrue(
            "the limit and the cutoff no longer come out of one pullWindowArgs call on that read",
            body.any { it.startsWith("val (limit, pruneBefore) = pullWindowArgs(store.syncWindow(credentials.id), ") },
        )
    }

    // -- reading the source --------------------------------------------------------------------------

    /** The body of `fun [function]` in `InboxViewModel`, comment and blank lines dropped. */
    private fun bodyLines(function: String): List<String> {
        val source = INBOX_VM.readText()
        val declaration = Regex("""\bfun\s+$function\s*\(""").find(source)
            ?: error("InboxViewModel has no function named '$function' — renamed?")
        var i = source.indexOf('{', declaration.range.last)
        val open = i
        var depth = 0
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(open, i).lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
    }

    private companion object {
        const val INBOX_VM_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VM_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }

        val INBOX_VM: File by lazy { File(root, INBOX_VM_PATH) }
    }
}
