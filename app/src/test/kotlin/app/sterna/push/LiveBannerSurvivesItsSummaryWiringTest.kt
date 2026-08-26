package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Volet B of "a live banner must survive its own group summary".
 *
 * Cancelling a notification that carries `FLAG_GROUP_SUMMARY` makes the system run
 * `cancelGroupChildrenLocked(…)`: EVERY live child of the group falls with it
 * (`REASON_GROUP_SUMMARY_CANCELED`, and it has been so since API 21). So when
 * [Notifications.updateGroupSummary] drops the summary because the account is down to a single
 * child, that lone child — unread mail the server still holds — loses its banner too. Shipped
 * since v1.2.0: two unread messages, one is deleted or read from another client, and the second
 * is never announced again.
 *
 * ⚠ ITS OWN LIMIT, written here so this file is never read as a proof: this is a SOURCE LINT.
 * It proves the TEXT of the wiring and nothing else. It does not prove that Android re-posts the
 * children, that the re-posted banner is silent, or that the shade ends up right — no JVM test in
 * this module can, because nothing here executes [Notifications.updateGroupSummary] (there is
 * neither Robolectric nor an `androidTest` source set, and no fake `NotificationManager`), and the
 * decision it guards travels in no pure function. ⛔ The proof of the fix is the bench run, not
 * this suite.
 *
 * ⛔ Whole lines only, never a `contains` on a fragment: a `contains` is blind to every mutation
 * that LENGTHENS the line, and this repo has been caught by that three times.
 */
class LiveBannerSurvivesItsSummaryWiringTest {

    @Test fun `the children captured above are put back, and only after the cancel`() {
        val body = bodyOf(NOTIFICATIONS, "updateGroupSummary")
        assertEquals(
            "the cancel of the summary takes every live child of the group down with it, so each " +
                "child captured above must be posted straight back under its own id. Without this " +
                "line the surviving unread message loses its banner and is never announced again " +
                "— mail the server still holds, silently gone from the shade",
            listOf("children.forEach { child -> repostSilently(context, manager, child) }"),
            codeLinesNaming(body, "repostSilently("),
        )

        // ⭐ THE pin of this file. A list of lines cannot see order, and the mutation that hurts
        // most keeps every line and only moves one: re-post first, cancel second. That is the
        // original defect again — the cascade fires last and wipes what was just put back — and
        // every other assertion here stays green through it.
        val lines = codeLinesNaming(body, "")
        val cancelAt = lines.indexOfFirst { it == "manager.cancel(summaryId)" }
        val repostAt = lines.indexOfFirst { "repostSilently(" in it }
        assertTrue(
            "the re-post must come AFTER the cancel (cancel at line $cancelAt, re-post at line " +
                "$repostAt of the body). Re-posting first lets the summary's cascade take the " +
                "fresh banners down again: the user sees the banner appear and vanish, and the " +
                "unread message is announced by nothing",
            cancelAt >= 0 && repostAt >= 0 && cancelAt < repostAt,
        )
        assertEquals(
            "…and the same two lines, whole and in this order — the pair pinned as a sequence so " +
                "that neither a second cancel nor a re-post moved above it can hide in between",
            listOf(
                "manager.cancel(summaryId)",
                "children.forEach { child -> repostSilently(context, manager, child) }",
            ),
            lines.filter { "manager.cancel(summaryId)" in it || "repostSilently(" in it },
        )
        assertEquals(
            "…and the body WHOLE, every line of it. The pins above name a needle each — the " +
                "re-post, its order, the one read of the shade — and leave the rest of the " +
                "function unheld, yet half of the gesture lives exactly there: the condition that " +
                "decides, the summary's id, the filter on this account's group, and the `return`. " +
                "Five mutations walk past needle pins and each one costs the user something: " +
                "`if (summaryShownFor(children.size))` demolishes the summary on a real burst and " +
                "hangs one over a lone message; an `if (children.isEmpty()) return` before the " +
                "cancel strands a \"3 new messages\" header alone on the bar for ever; " +
                "`sbn.notification.group != group` re-posts ANOTHER account's banners and prints " +
                "its senders and subjects in this summary (#92 and #84 broken by one character); " +
                "`(\"summary:\" + accountId).hashCode() + 1` never cancels the real summary, which " +
                "then sits above a single banner; and dropping the `return` re-posts the children " +
                "and THEN publishes a summary that rings for mail already announced — the #56 " +
                "promise broken. Only the whole body sees them",
            listOf(
                "{",
                "val manager = context.getSystemService(NotificationManager::class.java)",
                "val group = GROUP_PREFIX + accountId",
                "val summaryId = (\"summary:\" + accountId).hashCode()",
                "val active = manager.activeNotifications",
                "val children = active.filter { sbn ->",
                "sbn.id != summaryId && sbn.notification.group == group &&",
                "(sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0",
                "}.sortedByDescending { it.postTime }",
                "if (!summaryShownFor(children.size)) {",
                "if (active.any { it.id == summaryId && it.notification.group == group }) {",
                "manager.cancel(summaryId)",
                "children.forEach { child -> repostSilently(context, manager, child) }",
                "}",
                "return",
                "}",
                "val lines = children.map { sbn ->",
                "val sender = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()",
                "?: context.getString(R.string.notif_new_message)",
                "val subject = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()",
                "?: context.getString(R.string.message_no_subject)",
                "context.getString(R.string.notif_group_line, sender, subject)",
                "}",
                "notifyGroupSummary(context, accountId, accountLabel, children.size, lines, silent)",
                "}",
            ),
            lines,
        )
    }

    @Test fun `the re-post only ever touches this account's own children`() {
        val body = bodyOf(NOTIFICATIONS, "updateGroupSummary")
        assertEquals(
            "the shade is read ONCE, into `active`, from which `children` is filtered by this " +
                "account's group and stripped of the summary. A second read here — re-posting " +
                "from `manager.activeNotifications` — would put ANOTHER account's banners back on " +
                "screen, and a send failure or a push notice with them (the class of Codeberg #92: " +
                "the group is per account, GROUP_PREFIX + accountId)",
            listOf("val active = manager.activeNotifications"),
            codeLinesNaming(body, "activeNotifications"),
        )
        assertEquals(
            "…and the one loop in this function walks `children`, that same filtered list",
            listOf("children.forEach { child -> repostSilently(context, manager, child) }"),
            codeLinesNaming(body, ".forEach"),
        )
    }

    @Test fun `the re-posted banner does not ring, buzz or fly in again`() {
        val body = bodyOf(NOTIFICATIONS, "repostSilently")
        assertEquals(
            "the message was already announced when it first arrived: the re-post must stay mute. " +
                "GROUP_ALERT_SUMMARY hands the noise to a summary that is being cancelled, so " +
                "nothing sounds. Drop this line and every summary teardown makes old mail ring " +
                "again with a fresh heads-up banner",
            listOf(".setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)"),
            codeLinesNaming(body, "setGroupAlertBehavior"),
        )
        assertEquals(
            "…and never GROUP_ALERT_ALL here. That is Codeberg #56 turned inside out — the two " +
                "pop-ups the reporter complained about, brought back by the teardown path — and " +
                "#56 carries a public promise made on 2026-07-28 that the individual banner would " +
                "only come back if he asked for it",
            emptyList<String>(),
            codeLinesNaming(body, "GROUP_ALERT_ALL"),
        )
    }

    @Test fun `the re-posted banner is the very notification that was on screen, copied`() {
        val body = bodyOf(NOTIFICATIONS, "repostSilently")
        assertEquals(
            "the banner is COPIED, not rebuilt. `recoverBuilder` keeps the Reply / Mark as read / " +
                "Delete actions, their PendingIntents, the subText and the BigTextStyle exactly as " +
                "they were posted. Rebuilding from the extras would silently return a banner whose " +
                "buttons are gone or point at the wrong message",
            listOf("Notification.Builder.recoverBuilder(context, child.notification)"),
            codeLinesNaming(body, "recoverBuilder"),
        )
        assertEquals(
            "…and the body WHOLE, every line of it — the pins above name a needle each and leave " +
                "the rest unheld. What could escape them: `manager.notify(child.id + 1, …)` posts " +
                "a duplicate banner nothing will ever cancel; `child.notification.contentIntent` " +
                "swapped for a fresh Intent opens the wrong message on tap; a `.setSilent(true)` " +
                "or a `.setTimeoutAfter(…)` slipped in makes the restored banner behave unlike the " +
                "one it replaces",
            listOf(
                "{",
                "ensureMailChannel(context)",
                "manager.notify(",
                "child.id,",
                "Notification.Builder.recoverBuilder(context, child.notification)",
                ".setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)",
                ".build(),",
                ")",
                "}",
            ),
            codeLinesNaming(body, ""),
        )
    }

    @Test fun `the comment that let this defect live for thirteen months may not come back`() {
        // Searched in the RAW text: `codeLinesNaming` throws comments away, which is exactly where
        // this sentence lives.
        val body = bodyOf(NOTIFICATIONS, "updateGroupSummary")
        assertEquals(
            "\"The system shows a lone child by itself\" states the opposite of what happens: the " +
                "system takes the lone child DOWN with the summary. That sentence is why the " +
                "cancel was read as harmless for thirteen months, and why every reader after it " +
                "stopped looking. It must not come back beside the cancel",
            emptyList<String>(),
            body.lines().map { it.trim() }.filter { "The system shows a lone child by itself" in it },
        )
    }

    /**
     * The code lines of [body] naming [needle], comments and blank lines dropped. An empty
     * [needle] therefore enumerates the body WHOLE, which is the only pin a mutation cannot slip
     * a new line past.
     */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
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
        private const val NOTIFICATIONS_PATH = "app/src/main/kotlin/app/sterna/push/Notifications.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, NOTIFICATIONS_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val NOTIFICATIONS: File by lazy { File(root, NOTIFICATIONS_PATH) }
    }
}
