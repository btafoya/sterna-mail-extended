package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Codeberg #84 for the group summary: "No sender or subject" promises just that new mail arrived,
 * and the summary above the banners was still printing the account label three times: headline,
 * collapsed line, and the big title of its InboxStyle.
 *
 * Source lint, the last resort: posting a summary needs a NotificationManager and a resource
 * lookup, neither of which exists in a JVM test. The decision it wires up, what the setting lets
 * the summary name, is executed in [MailSubTextTest]; this file only pins that the decision is
 * what reaches the builder, and that every path re-posting a summary hands the setting over.
 *
 * What it cannot see: that the builder calls do anything (nothing renders here); the position of a
 * pinned line inside an `if`, or the order two lines run in; what a repository actually returns for
 * the setting; and that an argument named `content` is bound to the setting rather than to
 * something else of the same type. Where a body is pinned by a window of its lines rather than
 * whole, a substitution outside that window is invisible to it.
 */
class GroupSummaryObeysTheContentSettingWiringTest {

    @Test fun `the summary's own label comes from the decision, never from the parameter`() {
        val body = bodyOf(NOTIFICATIONS, "notifyGroupSummary")
        assertEquals(
            "the raw parameter is read exactly once, to be put to the decision. Every other use of " +
                "it is the defect: `setContentTitle(accountLabel)` alone puts the account back on " +
                "screen above banners that were told to say nothing",
            listOf("val account = accountLabel?.let { MailNotificationText.summaryAccountFor(content, it) }"),
            codeLinesNaming(body, "accountLabel"),
        )
        assertEquals(
            "…and what the builder is given is the decision's answer, headline and collapsed line " +
                "both. `account ?: title` is a title that names no account when the decision " +
                "refused one; the collapsed line is skipped then, or the shade prints the same " +
                "sentence twice",
            listOf(".setContentTitle(account ?: title)", ".apply { if (account != null) setContentText(title) }"),
            codeLinesNaming(body, "setContentTitle") + codeLinesNaming(body, "setContentText"),
        )
        assertEquals(
            "…and the InboxStyle's big title is that same `title`, not the label: it is the " +
                "headline of the expanded summary, the third place the account used to appear",
            listOf("val style = NotificationCompat.InboxStyle().setBigContentTitle(title)"),
            codeLinesNaming(body, "setBigContentTitle"),
        )
        assertEquals(
            "…and the two strings, each formatted by getString like the other. Concatenating the " +
                "count in Kotlin would put the number on the wrong side of the sentence in four of " +
                "the ten languages",
            listOf(
                "val title = if (account == null) {",
                "context.getString(R.string.notif_group_count_no_account, count)",
                "} else {",
                "context.getString(R.string.notif_group_count, count, account)",
                "}",
            ),
            codeLinesNaming(body, "").subList(3, 8),
        )
    }

    @Test fun `every language that counts a group counts one without an account`() {
        val files = File(root, RES_PATH).listFiles().orEmpty()
            .map { File(it, "strings.xml") }
            .filter { it.isFile && "name=\"notif_group_count\"" in it.readText() }
        assertEquals("the default and its nine translations", 10, files.size)
        files.forEach { file ->
            val line = file.readLines().map { it.trim() }
                .filter { "name=\"notif_group_count_no_account\"" in it }
            assertEquals("${file.parentFile.name}: the count-only headline is missing", 1, line.size)
            assertTrue(
                "${file.parentFile.name}: it must carry the count and nothing else — a %2\$s here " +
                    "throws IllegalFormatException on a summary posted with one argument: <${line.first()}>",
                "%1\$d" in line.first() && "%2\$s" !in line.first(),
            )
            assertTrue(
                "${file.parentFile.name}: left in English it reaches nine readers of ten in a " +
                    "language they did not choose: <${line.first()}>",
                file.parentFile.name == "values" || "new messages" !in line.first(),
            )
        }
    }

    @Test fun `the diff pass hands the summary the setting it read and the drawer's label`() {
        val body = bodyOf(NOTIFIER, "notifyDiff")
        assertEquals(
            "one read of the setting for the whole pass, and it reaches the summary as it reaches " +
                "the banners: the summary is re-posted on every pass, so left out it would name " +
                "the account again the moment a second message arrived",
            listOf(
                "val (silent, content) = options(context)",
                "content,",
                "content,",
                "content,",
            ),
            codeLinesNaming(body, "content"),
        )
        assertEquals(
            "…and the account is never named by its login here: `credentials.username` is one " +
                "address for every shared mailbox under it, and the banners under this summary " +
                "say `label()` (#193)",
            emptyList<String>(),
            codeLinesNaming(body, "credentials.username"),
        )
    }

    @Test fun `the read receipt path reads the setting before it re-posts the summary`() {
        val body = bodyOf(MESSAGE_VM, "maybeMarkRead")
        assertEquals(
            "marking a message read re-posts the group summary, so this path owes the same reading " +
                "of the setting as the pass that first posted it. `getOrDefault(NONE)` and not the " +
                "app's default: an unreadable settings file must fall back on the quietest, never " +
                "on the most talkative",
            listOf(
                "val content = runCatching { settings.notificationContent.first() }",
                ".getOrDefault(NotificationContent.NONE)",
            ),
            codeLinesNaming(body, "notificationContent") + codeLinesNaming(body, "getOrDefault"),
        )
        assertEquals(
            "…and the dismissal carries that reading, with the account named by the drawer's label",
            listOf(
                "Notifications.dismiss(",
                "getApplication(),",
                "credentials.id,",
                "store.account(credentials.id)?.label(),",
                "content,",
                "listOf(current.id),",
                ")",
            ),
            callLines(body, "Notifications.dismiss("),
        )
    }

    @Test fun `the inbox's dismissal path reads the setting before it re-posts the summary`() {
        val body = bodyOf(INBOX_VM, "dismissBanners")
        assertEquals(
            "the same reading, on the path that clears banners for a whole folder marked read: read " +
                "once outside the per-account loop, handed over with that account's drawer label. " +
                "The whole body and not a window of it, because the lines this function is made of " +
                "are the ones no executed test reaches: `dismissBanners` is private and wants an " +
                "Application, and `it.id to it.id` in the grouping would compile, empty every " +
                "dismissal, and leave read mail on the shade for ever with this suite green. " +
                "`appScope` and not `viewModelScope`: the read suspends, and this view model dies " +
                "with its navigation entry (#19)",
            listOf(
                "{",
                "if (keys.isEmpty()) return",
                "val app = getApplication<Application>()",
                "val byAccount = keys.mapNotNull { e -> credentialsFor(e)?.let { it.id to e.emailId } }",
                ".groupBy({ it.first }, { it.second })",
                "if (byAccount.isEmpty()) return",
                "appScope.launch {",
                "val content = runCatching { settings.notificationContent.first() }",
                ".getOrDefault(NotificationContent.NONE)",
                "byAccount.forEach { (accountId, ids) ->",
                "Notifications.dismiss(app, accountId, store.account(accountId)?.label(), content, ids)",
                "}",
                "}",
                "}",
            ),
            codeLinesNaming(body, ""),
        )
        assertEquals(
            "…and no login reaches the summary from here either",
            emptyList<String>(),
            codeLinesNaming(body, "username"),
        )
    }

    @Test fun `the banner's own buttons read the setting before they re-post the summary`() {
        val body = bodyOf(RECEIVER, "dismiss")
        assertEquals(
            "a Mark as read or a Delete tapped in the shade re-posts the summary too, and this is " +
                "the path with nothing on screen to compare against: without the setting here the " +
                "account's name comes back the instant a banner is acted on",
            listOf(
                "val content = runCatching { container.settingsRepository.notificationContent.first() }",
                ".getOrDefault(NotificationContent.NONE)",
                "Notifications.updateGroupSummary(",
                "context, accountId, container.accountStore.account(accountId)?.label(), content,",
            ),
            codeLinesNaming(body, "").subList(5, 9),
        )
        assertEquals(
            "…and the login is gone from this call: it named the group by an address shared with " +
                "every other mailbox of that account",
            emptyList<String>(),
            codeLinesNaming(body, "it.username"),
        )
    }

    @Test fun `no summary may be posted with a setting nobody passed`() {
        listOf("notifyGroupSummary", "updateGroupSummary", "dismiss").forEach { name ->
            val params = paramsOf(NOTIFICATIONS, name)
            assertTrue(
                "$name must take the setting: a poster that reads it from nowhere is how the " +
                    "snooze wake-up leaked sender and subject (#84), found <$params>",
                "content: NotificationContent," in params,
            )
            assertEquals(
                "$name must not default it: `content: NotificationContent = SENDER_AND_SUBJECT` " +
                    "would compile at every call site and print the account to a reader who asked " +
                    "for nothing, with this suite still green",
                emptyList<String>(),
                params.lines().map { it.trim() }.filter { it.startsWith("content") && "=" in it },
            )
        }
    }

    private fun callLines(body: String, opener: String): List<String> {
        val lines = codeLinesNaming(body, "")
        val start = lines.indexOfFirst { it.endsWith(opener) }
        if (start < 0) error("no call to '$opener' in this body — did it get renamed or inlined?")
        var depth = 0
        for (index in start until lines.size) {
            depth += lines[index].count { it == '(' } - lines[index].count { it == ')' }
            if (depth == 0) return lines.subList(start, index + 1)
        }
        error("unbalanced parentheses after '$opener'")
    }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The parameter list of `fun [name]` in [file], parentheses included, KDoc excluded. */
    private fun paramsOf(file: File, name: String): String {
        val source = file.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${file.name} has no function named '$name' — did it get renamed?")
        val open = source.indexOf('(', fn.range.first)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced parentheses in ${file.name}.$name")
    }

    /** The block body of `fun [name]` in [file], braces included. */
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
        private const val NOTIFIER_PATH = "app/src/main/kotlin/app/sterna/push/NewMailNotifier.kt"
        private const val RECEIVER_PATH = "app/src/main/kotlin/app/sterna/push/NotificationActionReceiver.kt"
        private const val MESSAGE_VM_PATH = "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt"
        private const val INBOX_VM_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val RES_PATH = "app/src/main/res"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, NOTIFICATIONS_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val NOTIFICATIONS: File by lazy { File(root, NOTIFICATIONS_PATH) }
        private val NOTIFIER: File by lazy { File(root, NOTIFIER_PATH) }
        private val RECEIVER: File by lazy { File(root, RECEIVER_PATH) }
        private val MESSAGE_VM: File by lazy { File(root, MESSAGE_VM_PATH) }
        private val INBOX_VM: File by lazy { File(root, INBOX_VM_PATH) }
    }
}
