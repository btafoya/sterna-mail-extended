package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, and the last resort it is meant to be: nothing in this chain can be built on the
 * JVM ([NewMailNotifier] and [Notifications] need an Application and a NotificationManager,
 * [FetchAndNotify] needs a repository with a socket behind it). Every DECISION it carries is
 * EXECUTED next door — the pre-pass in [NotificationPreviewGatheringTest], what a position may
 * reveal in [MailNotificationTextTest] — and none of it is restated here.
 *
 * What is left is the wiring, which is exactly what a green suite would not notice going wrong:
 * an opening line that never reaches the notification, a fetch that slides into the posting loop,
 * a preview gathered for a reader who asked for none. Whole lines, compared — `contains` is blind
 * to any mutation that LENGTHENS a line.
 */
class PreviewReachesTheNotificationWiringTest {

    /**
     * THE ORDER, AND THE ONLY WAY THIS FEATURE CAN LOSE MAIL.
     *
     * The pre-pass is one call, handed the whole batch, and the posting is what [postGatheredFirst]
     * does after it — there is no fetch inside a `forEach` over the new mail. A round trip in that
     * loop would drop an exception into a half-announced batch, skip the [seed] at the bottom of
     * the function, and leave the folder's baseline where it was: the next pass re-announces
     * everything, and a re-announcement MOVES MAIL server-side through `unarchiveThreadsOnReply`.
     *
     * That the gathering happens before the first post, and that nothing escapes it, is executed
     * in [NotificationPreviewGatheringTest]. This says the shipped notifier goes through it.
     */
    @Test fun `the openings are gathered in one pre-pass, not inside the posting loop`() {
        val body = bodyOf(NOTIFIER, "notifyDiff")

        assertEquals(
            "the batch must be posted THROUGH postGatheredFirst, and the pre-pass must be its " +
                "gather argument — one call, for the whole batch",
            listOf("NotificationPreviews.postGatheredFirst(", "NotificationPreviews.gather("),
            codeLinesNaming(body, "NotificationPreviews."),
        )
        assertEquals(
            "and exactly one posting call site, inside that function's post argument: a second one " +
                "in a loop of its own is the defect this is here to prevent",
            listOf("Notifications.notifyNewMail("),
            codeLinesNaming(body, "notifyNewMail("),
        )
        assertEquals(
            "nothing may loop over the new mail here any more — that loop is postGatheredFirst's, " +
                "and it runs after the gathering",
            emptyList<String>(),
            codeLinesNaming(body, "newMail.forEach"),
        )
        assertEquals(
            "the baseline advance must still be the LAST thing the pass does: a preview read " +
                "after it would be a network call between the notifications and their baseline",
            "seed(context, credentials.id, mailboxId, baselineIds)",
            codeLinesOf(body).dropLast(1).last(),
        )
    }

    /**
     * WHAT ACTUALLY CLOSES THE PERIL, stated as a check rather than as reasoning: between the
     * moment the gathering returns and the moment the baseline is advanced there is NO SUSPENSION
     * POINT, so nothing can be cancelled in between and leave a batch announced but not remembered.
     *
     * That property rests on one word: `post` is a plain function type, not a suspending one. Make
     * it `suspend` and the posting loop becomes cancellable halfway through, with the [seed] below
     * it never reached — the folder then re-announces everything on the next pass, and a
     * re-announcement MOVES MAIL server-side through `unarchiveThreadsOnReply`. Everything the
     * shipped notifier calls after the gathering is likewise non-suspending
     * (`Notifications.notifyNewMail`, `cancelChild`, `updateGroupSummary`, `seed`).
     */
    @Test fun `nothing between the gathering and the baseline may suspend`() {
        val signature = PREVIEWS.readText()
            .substringAfter("suspend fun <T> postGatheredFirst(")
            .substringBefore(") {")
        assertEquals(
            "post must stay a plain function type: a suspending one re-opens the window in which a " +
                "cancellation lands between the notifications and the baseline that remembers them",
            listOf(
                "mail: List<T>,",
                "keyOf: (T) -> String,",
                "gather: suspend () -> Map<String, String>,",
                "post: (T, String?) -> Unit,",
            ),
            signature.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertEquals(
            "and the budget consulted along the way must not suspend either",
            listOf("fun claim(): Int? {"),
            codeLinesNaming(PREVIEWS.readText(), "fun claim("),
        )
    }

    /**
     * EVERY ARGUMENT OF THE PRE-PASS, IN ORDER, WHOLE LINES — because the two that matter most
     * are POSITIONAL, and a positional argument is invisible to an assertion that searches for a
     * needle it already expects.
     *
     * The two, both of which COMPILE if replaced and neither of which looks wrong on screen:
     *
     *  - `content` → `NotificationContent.BODY_PREVIEW`: the reader who asked for "no details" —
     *    the one who reported the leak — then pays up to six SELECTs and six 8 KiB BODY.PEEKs per
     *    announcement, and the bodies of their mail cross the network for a string `resolve`
     *    throws away. The executed test of the gate proves the gate; only this proves it is WIRED;
     *  - `newMail.map { it.id }` → `emails.map { it.id }`: the six reads are then spent on the
     *    first six rows of a page of up to fifty, most of them already announced, so the opening
     *    line almost never appears and the radio is paid for nothing.
     */
    @Test fun `every argument of the pre-pass is pinned, positional ones included`() {
        assertEquals(
            "the gate, the mail being announced, the conveyed sources, the account pass's budget, " +
                "the numbering and the bounded read — in that order",
            listOf(
                "NotificationPreviews.gather(",
                "content,",
                "newMail.map { it.id },",
                "previewSources,",
                "previewBudget,",
                "numbering = { repository.recordedNumbering(credentials.id, mailboxId) },",
                "fetch = { source, budgetMs ->",
                "repository.notificationPreview(credentials, mailboxId, source, budgetMs)",
                "},",
                ")",
            ),
            callLines(bodyOf(NOTIFIER, "notifyDiff"), "NotificationPreviews.gather("),
        )
        assertEquals(
            "the setting read once for this pass is what gates it (there must be no second read, " +
                "per message, of a setting the pass already holds)",
            listOf("val (silent, content) = options(context)"),
            codeLinesNaming(bodyOf(NOTIFIER, "notifyDiff"), "options(context)"),
        )
    }

    /**
     * ONE BUDGET FOR THE ACCOUNT'S WHOLE PASS, made OUTSIDE the folder loop.
     *
     * [NewMailNotifier.notifyDiff] is called once per folder, and its `previewBudget` parameter
     * defaults to one of its own — right for a single-folder caller, and four times over budget
     * for a fan-out. An account watching three extra folders would spend 4 × 6 reads and up to
     * 4 × 4 s of blocking IMAP work under its single mutex, on one radio wake-up.
     */
    @Test fun `the account pass makes one budget and shares it between its folders`() {
        val lines = codeLinesOf(bodyOf(FETCH_AND_NOTIFY, "run"))
        val made = lines.indexOf("val previewBudget = NotificationPreviews.PreviewBudget(System::currentTimeMillis)")
        val loop = lines.indexOf("refreshes.forEach { folder ->")

        assertEquals(
            "the budget must be made exactly once in this pass",
            1,
            lines.count { it.startsWith("val previewBudget =") },
        )
        assertTrue(
            "and OUTSIDE the folder loop: made inside it, each folder starts afresh with six " +
                "reads and four seconds of its own",
            made in 0 until loop,
        )
        assertEquals(
            "and it must reach the diff — dropped, the parameter's default gives every folder one " +
                "of its own, which is the very thing this exists to prevent",
            listOf("previewBudget,"),
            codeLinesNaming(bodyOf(FETCH_AND_NOTIFY, "run"), "previewBudget,"),
        )
    }

    /**
     * The opening travels ON the message, so the two paths that post a mail notification — a
     * folder diff and a snooze waking up — read it from one field, and the privacy setting keeps
     * deciding on its own whether it is ever shown.
     */
    @Test fun `the gathered opening is carried on the message that is posted`() {
        assertEquals(
            "every argument of the posting call, in order: `content` decides what a lock screen " +
                "shows and is positional, which is the class of defect #84 closed; `email.copy` is " +
                "what carries the gathered opening, and `?: email.preview` is what leaves JMAP's " +
                "own — already in the row, offline included — alone",
            listOf(
                "Notifications.notifyNewMail(",
                "context,",
                "email.copy(preview = preview ?: email.preview),",
                "credentials.id,",
                "silent,",
                "folderName,",
                "mailboxId,",
                "content,",
                "summarised,",
                ")",
            ),
            callLines(bodyOf(NOTIFIER, "notifyDiff"), "Notifications.notifyNewMail("),
        )
        assertEquals(
            "and the poster reads that one field and passes it to the pure rule, which is what " +
                "decides whether the position may show it at all (#57)",
            listOf(
                "val (title, text, bigText) = " +
                    "MailNotificationText.resolve(content, sender, subject, generic, email.preview)",
            ),
            codeLinesNaming(bodyOf(NOTIFICATIONS, "notifyNewMail"), "MailNotificationText.resolve("),
        )
        assertEquals(
            "and nothing else in the poster touches a preview: it posts, it does not go to the " +
                "network, and it does not re-shape what the pure rule shapes",
            listOf(
                "val (title, text, bigText) = " +
                    "MailNotificationText.resolve(content, sender, subject, generic, email.preview)",
            ),
            codeLinesNaming(bodyOf(NOTIFICATIONS, "notifyNewMail"), "preview"),
        )
    }

    /**
     * Codeberg #84 stands: the poster's user settings still have NO defaults, and this change
     * added no parameter that a caller could forget. The opening rides on the message instead, so
     * there is nothing new to omit.
     */
    @Test fun `the poster still takes its user settings without defaults`() {
        val signature = NOTIFICATIONS.readText()
            .substringAfter("fun notifyNewMail(")
            .substringBefore(") {")
        assertEquals(
            "silent and content are user settings, and a default is how the snooze wake-up used to " +
                "post loudly with sender and subject against the reader's explicit choice (#84)",
            listOf(
                "context: Context,",
                "email: Email,",
                "accountId: String,",
                "silent: Boolean,",
                "folderName: String?,",
                "mailboxId: String?,",
                "content: NotificationContent,",
                "summarised: Boolean,",
            ),
            signature.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /**
     * The snooze wake-up posts through the same function and so shows an opening line at the same
     * one position — it is re-posting a mail notification, and a reader who asked for the body
     * asked for it there too. It reads the stored `preview` of the row it woke up, and fetches
     * nothing: a wake-up is not a sync.
     */
    @Test fun `the snooze wake-up still reads the real settings, and gathers nothing`() {
        val body = bodyOf(SNOOZE_WORKER, "doWork")

        assertEquals(
            "the wake-up must keep reading the stored settings rather than posting with defaults (#84)",
            listOf("val (silent, content) = NewMailNotifier.options(applicationContext)"),
            codeLinesNaming(body, "NewMailNotifier.options("),
        )
        assertEquals(
            "and hand them over by name",
            listOf("silent = silent,", "content = content,"),
            codeLinesNaming(body, "silent = silent,") + codeLinesNaming(body, "content = content,"),
        )
        assertEquals(
            "a wake-up posts one cached message; it must not grow a pre-pass, a fetch or a budget",
            emptyList<String>(),
            codeLinesNaming(body, "NotificationPreviews") + codeLinesNaming(body, "notificationPreview("),
        )
    }

    /**
     * The conveyance reaches the notifier. Dropped between the refresh and the diff, the pre-pass
     * would find no source for any message and IMAP would silently never show an opening line —
     * with every test above still green, because each of them is handed its sources by hand.
     */
    @Test fun `the refresh hands the notifier the sources it learned`() {
        assertEquals(
            "the folder's own preview sources, on their own line and not defaulted away",
            listOf("folder.previewSources,"),
            codeLinesNaming(bodyOf(FETCH_AND_NOTIFY, "run"), "previewSources"),
        )
    }

    /**
     * The code lines of the call opened by the line that ENDS with [opener], through to the line
     * that closes it — parentheses counted.
     *
     * Compared as a list, so the arguments are judged IN ORDER and as whole lines: a positional
     * argument swapped for another expression of the same type is a different line here, where a
     * needle-filtered assertion never sees it at all.
     */
    private fun callLines(body: String, opener: String): List<String> {
        val lines = codeLinesOf(body)
        val start = lines.indexOfFirst { it.endsWith(opener) }
        if (start < 0) error("no call to '$opener' in this body — did it get renamed or inlined?")
        var depth = 0
        for (index in start until lines.size) {
            depth += lines[index].count { it == '(' } - lines[index].count { it == ')' }
            if (depth == 0) return lines.subList(start, index + 1)
        }
        error("unbalanced parentheses after '$opener'")
    }

    /** Every CODE line of [body], trimmed — comments and blank lines dropped. */
    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The block body of `fun [name]` in [file] — braces included. */
    private fun bodyOf(file: File, name: String): String {
        val source = file.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${file.name} has no function named '$name' — did it get renamed?")
        val open = source.indexOf('{', fn.range.last)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in ${file.name}.$name")
    }

    companion object {
        private const val PUSH_SOURCES = "app/src/main/kotlin/app/sterna/push"
        private const val NOTIFIER_PATH = "$PUSH_SOURCES/NewMailNotifier.kt"
        private const val NOTIFICATIONS_PATH = "$PUSH_SOURCES/Notifications.kt"
        private const val PREVIEWS_PATH = "$PUSH_SOURCES/NotificationPreviews.kt"
        private const val FETCH_AND_NOTIFY_PATH = "$PUSH_SOURCES/FetchAndNotify.kt"
        private const val SNOOZE_WORKER_PATH = "app/src/main/kotlin/app/sterna/snooze/SnoozeWorker.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, NOTIFIER_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val NOTIFIER: File by lazy { File(root, NOTIFIER_PATH) }
        private val NOTIFICATIONS: File by lazy { File(root, NOTIFICATIONS_PATH) }
        private val PREVIEWS: File by lazy { File(root, PREVIEWS_PATH) }
        private val FETCH_AND_NOTIFY: File by lazy { File(root, FETCH_AND_NOTIFY_PATH) }
        private val SNOOZE_WORKER: File by lazy { File(root, SNOOZE_WORKER_PATH) }
    }
}
