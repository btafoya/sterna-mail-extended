package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads source files as text.
 *
 * `NotifierCandidateWindowTest` (in `core/data`) runs the actual agreement: the bounded read has to
 * contain everything the notifier's age floor can still announce. Neither end of it is reachable
 * from a JVM test in this module — `NewMailNotifier` needs `SharedPreferences` and an `Application`
 * container, `FetchAndNotify` needs both plus a repository — so what a test HERE can do is check
 * that the shipped code is still wired to the pieces that test runs.
 *
 * The three edits it guards are all silent, and each one re-opens the bug the bound was written
 * for while every test in the repo stays green:
 *
 *  1. the foreground hook reading the whole inbox again (`cachedEmailsForMailboxes` was exactly
 *     that, and it ran on EVERY foreground refresh with unarchive-on-reply on);
 *  2. the age floor inlined back into `newSince` — the floor and the read then drift, and the
 *     drift shows up as notifications for months-old mail, on the user's phone, not here;
 *  3. `receivedAfter` re-implemented instead of delegating, which quietly un-shares the leniency
 *     the read's `sortKey = 0` clause exists to honour.
 */
class NotifyCandidatesWiringTest {

    @Test fun `the foreground hook reads the notifier's two sets, not the inbox`() {
        val source = code(FETCH_AND_NOTIFY)
        assertTrue(
            "FetchAndNotify.onInboxRefreshed must read " +
                "container.mailRepository.notifyRead(credentials.id, inboxMailboxId) — the same " +
                "bounded read the push/worker pass gets. Its line is now:\n" +
                source.lines().filter { "mailRepository" in it }.joinToString("\n"),
            "val read = container.mailRepository.notifyRead(credentials.id, inboxMailboxId)"
                in source.lines().map { it.trim() },
        )
        assertTrue(
            "a whole-folder read is back in FetchAndNotify",
            "cachedEmailsForMailboxes" !in source && "getByMailbox" !in source,
        )
    }

    @Test fun `every seed writes the baseline ids, never only the announced messages`() {
        // The defect the core/data two-pass test caught: seeding from what was ANNOUNCED means the
        // baseline is capped, a capped baseline forgets rows, and a later pass announces them —
        // and unarchives their threads, server-side. There are three seed points across the two
        // passes and each has to remember the whole read.
        val lines = code(FETCH_AND_NOTIFY).lines().map { it.trim() }
        listOf(
            "val remembered = baselineIds + returned.map { it.id }",
            "NewMailNotifier.seed(context, credentials.id, folder.mailboxId, remembered)",
            "NewMailNotifier.seed(context, credentials.id, inboxMailboxId, read.baselineIds)",
            "val remembered = read.baselineIds + returned.map { it.id }",
            "NewMailNotifier.seed(context, credentials.id, inboxMailboxId, remembered)",
        ).forEach {
            assertTrue(
                "FetchAndNotify no longer contains the line:\n  $it\n" +
                    "A baseline seeded from anything narrower than the pass's own id read is the " +
                    "audited defect coming back.",
                it in lines,
            )
        }
        assertTrue(
            "notifyDiff must be handed the remembered ids as well as the announceable messages",
            "context, credentials, folder.mailboxId, folderName, folder.emails + returned, remembered," in lines &&
                "NewMailNotifier.notifyDiff(context, credentials, inboxMailboxId, null, emails + returned, remembered)"
                in lines,
        )
    }

    /**
     * SOURCE LINT, NEGATIVE SPACE — these are ALL the places this file seeds a baseline, and
     * there is no other.
     *
     * Every rule above asks that a line be PRESENT, and a presence check cannot see a line that was
     * ADDED. This mutation passes all of them, because it names neither `folder.baselineIds` nor
     * `orEmpty()` nor `?: emptyList()`, and it re-opens the whole defect through the side door —
     * the first sight of a folder is marked "known, remembering nothing", so the next pass
     * announces everything in it:
     *
     *     if (folder.emails.isEmpty() && !NewMailNotifier.hasBaseline(context, credentials.id, folder.mailboxId))
     *         NewMailNotifier.seed(context, credentials.id, folder.mailboxId, emptyList())
     *
     * Compared as a LIST of whole lines, in order: an argument swapped, a fourth call site added or
     * one of these three removed are all a different list. A `contains` would see none of it.
     */
    @Test fun `these three are every baseline this file writes, and there is no fourth`() {
        assertEquals(
            "FetchAndNotify seeds a baseline somewhere new. There are exactly three seed points — " +
                "the folder loop's silent seed, the foreground hook's first sight, and its " +
                "notifications-off arm — and each writes ids the pass actually read. A fourth is " +
                "how an empty baseline gets written without any of the rules above noticing.",
            listOf(
                "NewMailNotifier.seed(context, credentials.id, folder.mailboxId, remembered)",
                "NewMailNotifier.seed(context, credentials.id, inboxMailboxId, read.baselineIds)",
                "NewMailNotifier.seed(context, credentials.id, inboxMailboxId, remembered)",
            ),
            codeLinesNaming(code(FETCH_AND_NOTIFY), "NewMailNotifier.seed("),
        )
    }

    /**
     * SOURCE LINT — a refresh that says "I cannot tell you what this folder holds"
     * (`FolderRefresh.baselineIds == null`) must SKIP the folder whole: no seed, no diff, not one
     * write of the baseline.
     *
     * The refusal costs nothing to honour, and that is why skipping is the right gesture rather
     * than a special case: a refused page is empty by construction, so there is nothing to
     * announce, and the only thing this loop would do with it is precisely the write being refused.
     *
     * The mutation this exists for is `?: emptyList()`. `seed` REPLACES the baseline, so an
     * unknown taken as empty erases it, and the next pass announces every unread message inside the
     * notifier's age floor — one banner per thread, at 3 a.m., for mail already read. It is
     * unreachable from a JVM test in this module (`NewMailNotifier` needs SharedPreferences,
     * `FetchAndNotify` an Application container), and the decision it honours is executed one
     * module down in core/data's `EmptyPushPageIsNotAnEmptyFolderTest`.
     *
     * It also carries what the pinned `remembered` line above lost when it stopped naming
     * `folder.baselineIds`: this is the line that says the remembered ids come from the refresh.
     */
    @Test fun `a folder whose page could not say what it holds is skipped, never remembered as empty`() {
        val lines = code(FETCH_AND_NOTIFY).lines().map { it.trim() }
        assertTrue(
            "FetchAndNotify.run must refuse a null baseline by leaving the folder alone:\n" +
                "  val baselineIds = folder.baselineIds ?: return@forEach\n" +
                "Its lines naming folder.baselineIds are now:\n" +
                lines.filter { "folder.baselineIds" in it }.joinToString("\n"),
            listOf("val baselineIds = folder.baselineIds ?: return@forEach") ==
                lines.filter { "folder.baselineIds" in it },
        )
        assertTrue(
            "a null baseline was flattened into an empty one somewhere in FetchAndNotify — " +
                "`orEmpty()` / `?: emptyList()` on this path IS the defect, spelled quietly",
            "orEmpty()" !in code(FETCH_AND_NOTIFY) && "?: emptyList()" !in code(FETCH_AND_NOTIFY),
        )
    }

    @Test fun `the notifier advances its baseline from the ids it was given`() {
        val lines = code(NEW_MAIL_NOTIFIER).lines().map { it.trim() }
        assertTrue(
            "NewMailNotifier.notifyDiff must end with seed(context, credentials.id, mailboxId, " +
                "baselineIds) — seeding `emails` there re-caps the baseline inside the notifier, " +
                "where no call-site rule can see it.",
            "seed(context, credentials.id, mailboxId, baselineIds)" in lines,
        )
        assertTrue(
            "seed must still REPLACE the baseline (putStringSet) with what it was given. If it " +
                "ever unions instead, the reasoning in NotifyCandidates.kt and the two-pass test " +
                "are about a different function than the one that ships.",
            ".putStringSet(key(accountId, mailboxId), baselineIds.toSet())" in lines,
        )
    }

    @Test fun `the notifier takes its age floor from the shared decision`() {
        val source = code(NEW_MAIL_NOTIFIER)
        assertTrue(
            "NewMailNotifier.newSince must compute its floor with notifyFloor(lastPass) — the " +
                "function core/data's NotifierCandidateWindowTest runs against the bounded read. " +
                "Inlined here, the two can drift by any amount with no test able to see it.",
            "val floor = notifyFloor(lastPass)" in source.lines().map { it.trim() },
        )
        assertTrue(
            "NewMailNotifier declares its own horizon again — there is one number, and it lives " +
                "in core/data's NotifyCandidates.kt because the read one module down needs it",
            "NOTIFY_HORIZON_MS =" !in source,
        )
    }

    @Test fun `the age predicate is the shared one, not a second copy of it`() {
        val source = code(NEW_MAIL_NOTIFIER)
        assertTrue(
            "receivedAfter must delegate to announceableAt(email.receivedAt, floorMs): the read " +
                "keeps undated rows (sortKey = 0) precisely because this predicate lets them " +
                "through, and a private copy can lose that without a word.",
            "private fun receivedAfter(email: Email, floorMs: Long): Boolean = " +
                "announceableAt(email.receivedAt, floorMs)" in source.replace("\n", " ")
                .replace(Regex(" +"), " "),
        )
    }

    @Test fun `mark-all-read asks for the unread keys, not for every cached row`() {
        // The read it replaced was per SCOPE, so in the unified view it was one whole folder per
        // account — for a list of ids and a notification dismissal.
        val source = code(INBOX_VIEW_MODEL)
        assertTrue(
            "InboxViewModel.markAllRead must read repo.cachedUnreadKeys(scopes). Its line is now:\n" +
                source.lines().filter { "cachedUnread" in it }.joinToString("\n"),
            "val cachedUnread = repo.cachedUnreadKeys(scopes)" in source.lines().map { it.trim() },
        )
        assertTrue(
            "a whole-folder read is back in InboxViewModel",
            "cachedEmailsForMailboxes" !in source && "getByMailbox" !in source,
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The code lines of [source] naming [needle] — whole lines, and [code] has already cut the
     *  comments. Whole lines, never a search: `in` is blind to anything a mutation LENGTHENS. */
    private fun codeLinesNaming(source: String, needle: String): List<String> =
        source.lines().map { it.trim() }.filter { needle in it }

    /** [file]'s code as one string, comments cut — the comments beside these call sites name the
     *  very expressions the rules forbid. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
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

    private companion object {
        const val FETCH_AND_NOTIFY_PATH = "app/src/main/kotlin/app/sterna/push/FetchAndNotify.kt"
        const val NEW_MAIL_NOTIFIER_PATH = "app/src/main/kotlin/app/sterna/push/NewMailNotifier.kt"
        const val INBOX_VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, FETCH_AND_NOTIFY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val FETCH_AND_NOTIFY: File by lazy { File(root, FETCH_AND_NOTIFY_PATH) }
        val NEW_MAIL_NOTIFIER: File by lazy { File(root, NEW_MAIL_NOTIFIER_PATH) }
        val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
