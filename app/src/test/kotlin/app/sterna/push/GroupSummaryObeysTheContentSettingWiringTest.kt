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
 * It has since grown a second charge on `maybeMarkRead`, the same instrument for the same reason:
 * that the banner of a message just read is cleared only when the write of the read mark returned.
 * `ReadMarkCleanupTest` executes that decision; here it is the wiring, and the one dismissal in the
 * body, that are pinned.
 *
 * What it cannot see: that the builder calls do anything (nothing renders here); the position of a
 * pinned line inside an `if`, or the order two lines run in; what a repository actually returns for
 * the setting; and that an argument named `content` is bound to the setting rather than to
 * something else of the same type. Where a body is pinned by a window of its lines rather than
 * whole, a substitution outside that window is invisible to it, which is why the dismissal is
 * counted as well as placed. Nor does it see what a log line reaches, or whether a reader ever
 * sees a banner: that a kept banner is the whole of what a failed mark tells anyone is a claim no
 * test in this module can hold.
 */
class GroupSummaryObeysTheContentSettingWiringTest {

    @Test fun `the summary's own label comes from the decision, never from the parameter`() {
        val body = bodyOf(NOTIFICATIONS, "notifyGroupSummary")
        assertEquals(
            "the raw parameter is read exactly twice: to name the account's channel, a name read " +
                "in Android's notification settings and not printed on the notification, and to " +
                "be put to the decision. Every other use of it is the defect: " +
                "`setContentTitle(accountLabel)` alone puts the account back on screen above " +
                "banners that were told to say nothing",
            listOf(
                "ensureMailChannel(context, accountId, accountLabel)",
                "val account = accountLabel?.let { MailNotificationText.summaryAccountFor(content, it) }",
            ),
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
            "…and the two titles, each formatted by getQuantityString like the other, the count " +
                "given twice: once to pick the plural form, once to be printed. Concatenating the " +
                "count in Kotlin would put the number on the wrong side of the sentence in four of " +
                "the eleven languages, and a plain getString reads \"2 новых сообщений\" in Russian " +
                "and \"2 nowych wiadomości\" in Polish, where two to four take another form than five",
            listOf(
                "val title = if (account == null) {",
                "context.resources.getQuantityString(R.plurals.notif_group_count_no_account, count, count)",
                "} else {",
                "context.resources.getQuantityString(R.plurals.notif_group_count, count, count, account)",
                "}",
            ),
            codeLinesNaming(body, "").subList(3, 8),
        )
    }

    /**
     * Checks the shape of the two titles, not their grammar: that each file declares both plurals,
     * with the quantities its `search_result_count` already declares, and the placeholders a
     * caller passes. Whether a form reads right in its language is left to a reader of it.
     */
    @Test fun `every language declares both summary titles as plurals with the arguments they are given`() {
        val files = File(root, RES_PATH).listFiles().orEmpty()
            .map { File(it, "strings.xml") }
            .filter { it.isFile && "name=\"search_result_count\"" in it.readText() }
        assertEquals("the default and its ten translations", 11, files.size)
        files.forEach { file ->
            val lang = file.parentFile.name
            val text = file.readText()
            val reference = pluralItems(text, "search_result_count")
                ?: error("$lang: no <plurals name=\"search_result_count\">")
            val withAccount = pluralItems(text, "notif_group_count")
            val noAccount = pluralItems(text, "notif_group_count_no_account")
            assertTrue("$lang: notif_group_count is not a <plurals>", withAccount != null)
            assertTrue("$lang: notif_group_count_no_account is not a <plurals>", noAccount != null)
            assertEquals(
                "$lang: notif_group_count must declare the quantities this language counts with, " +
                    "the ones search_result_count declares; a missing one falls back on `other`",
                reference.keys,
                withAccount!!.keys,
            )
            assertEquals(
                "$lang: notif_group_count_no_account must declare the same quantities",
                reference.keys,
                noAccount!!.keys,
            )
            (withAccount.entries + noAccount.entries).forEach { (quantity, item) ->
                assertTrue("$lang/$quantity: the count is missing: <$item>", "%1\$d" in item)
                assertTrue(
                    "$lang/$quantity: left in English it reaches ten readers of eleven in a " +
                        "language they did not choose: <$item>",
                    lang == "values" || "new message" !in item,
                )
            }
            // TranslationParityTest compares format arguments on <string> only: for these two
            // plurals, this exact set is the only check that a translation asks for no argument
            // the caller does not pass.
            withAccount.forEach { (quantity, item) ->
                assertEquals(
                    "$lang/$quantity: the count and the account, nothing else: <$item>",
                    setOf("%1\$d", "%2\$s"),
                    placeholdersIn(item),
                )
            }
            noAccount.forEach { (quantity, item) ->
                assertEquals(
                    "$lang/$quantity: it must carry the count and nothing else — a %2\$s here " +
                        "throws IllegalFormatException on a summary posted with one argument: <$item>",
                    setOf("%1\$d"),
                    placeholdersIn(item),
                )
            }
        }
    }

    private fun placeholdersIn(item: String): Set<String> =
        Regex("%\\d+\\$[a-zA-Z]").findAll(item).map { it.value }.toSet()

    /** quantity → text of the items of `<plurals name="[name]">` in [xml], or null if there is none. */
    private fun pluralItems(xml: String, name: String): Map<String, String>? {
        val block = Regex(
            "<plurals\\s+name=\"${Regex.escape(name)}\"[^>]*>(.*?)</plurals>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml) ?: return null
        return Regex("<item\\s+quantity=\"([a-z]+)\"[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(block.groupValues[1])
            .associate { it.groupValues[1] to it.groupValues[2] }
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

    @Test fun `the read mark and its dismissal outlive the page, and a cancelled read is no setting`() {
        val body = bodyOf(MESSAGE_VM, "maybeMarkRead")
        assertEquals(
            "the persistence must be launched on the app's scope: one of these view models exists " +
                "per pager page and is cleared the moment the page is, so on `viewModelScope` a " +
                "rotation, a back gesture out of the reader or a second swipe kills the block " +
                "mid-flight — the dot cleared on screen, the server never told, and the message " +
                "unread again after a sync",
            listOf("appScope.launch {"),
            codeLinesNaming(body, "appScope"),
        )
        assertEquals(
            "…and not one line of this body may still name `viewModelScope`: a single straggler " +
                "there is a straggler that dies with the page",
            emptyList<String>(),
            codeLinesNaming(body, "viewModelScope"),
        )
        assertEquals(
            "…and the field is the application's own scope. A local `appScope` handed the view " +
                "model's job would pass every line above while dying with the page just the same",
            listOf("private val appScope = application.container.appScope"),
            codeLinesNaming(MESSAGE_VM.readText(), "private val appScope"),
        )
        assertEquals(
            "…and the write claims the (account, message) pair before it goes out. The block " +
                "outlives the page now: a rotation builds a second view model, whose own `anchorMarked` " +
                "is fresh and whose read of the cache still says unread, because the mark is written " +
                "there only after the round trip. It marks the same message a second time, both marks " +
                "read it unread, and both take one off the folder's count: three unread, one opened, " +
                "and the drawer says one",
            listOf(
                "appScope.launch {",
                "val credentials = credentials() ?: return@launch",
                "val key = credentials.id to current.id",
                "if (!markingRead.add(key)) return@launch",
                "try {",
                "val write = runCatching { repo.setRead(credentials, current.id, true) }",
                "when (readMarkCleanup(write)) {",
                "ReadMarkCleanup.DISMISS -> {",
            ),
            linesFrom(body, "appScope.launch {", 8),
        )
        assertEquals(
            "…and what the write handed back is what everything below is decided on, all three " +
                "answers spelled out. A discarded `runCatching` is the offline defect: `setRead` " +
                "writes the cache only after the round trip, so a throw leaves the mark nowhere, " +
                "and dismissing anyway destroys the one banner that said this mail arrived: the " +
                "next sync brings the unread dot back and posts nothing behind it. The `when` is " +
                "exhaustive on purpose: a fourth answer will not compile until this body says what " +
                "it does about the shade",
            listOf(
                "ReadMarkCleanup.DISMISS -> {",
                "ReadMarkCleanup.KEEP_AND_LOG -> write.exceptionOrNull()?.let { failure ->",
                "ReadMarkCleanup.KEEP_AND_RETHROW -> write.getOrThrow()",
            ),
            codeLinesNaming(body, "ReadMarkCleanup."),
        )
        assertEquals(
            "…and the write that threw is written down, with the throwable and the message it was " +
                "about. What the reader is told is the banner this branch leaves standing; the log " +
                "is the only place the cause exists, and a refused STORE, a dead socket and a bug " +
                "are one and the same event without it",
            listOf("android.util.Log.w(\"SternaMessage\", \"mark read failed: \${current.id}\", failure)"),
            codeLinesNaming(body, "Log.w("),
        )
        assertEquals(
            "…and nothing on this path writes to `_actionStatus`. This view model is held by a store " +
                "owner disposed with its pager page, and this block runs on `appScope` exactly " +
                "because it outlives that page: a status written here is collected by nobody in the " +
                "case the branch exists for. It would also fire on a settle rather than on a " +
                "gesture, one refusal per message read offline",
            emptyList<String>(),
            codeLinesNaming(body, "_actionStatus") + codeLinesNaming(body, "readerActionFailureText"),
        )
        assertEquals(
            "…and it is given back in a finally: a claim left behind by anything thrown on the way " +
                "out is a message this process will never mark read again, on any page",
            listOf("} finally {"),
            codeLinesNaming(body, "finally"),
        )
        assertEquals(
            "…and the claims are held by the class, not by the instance: a field of this view model " +
                "is exactly what the rotation throws away, which is how the pair reaches two of them. " +
                "A set safe for `Dispatchers.Default`, where both blocks run",
            listOf("private val markingRead = ConcurrentHashMap.newKeySet<Pair<String, String>>()"),
            codeLinesNaming(MESSAGE_VM.readText(), "markingRead ="),
        )
        assertEquals(
            "marking a message read re-posts the group summary, so this path owes the same reading " +
                "of the setting as the pass that first posted it. The fallback is NONE and not the " +
                "app's default: a settings file that will not parse must leave the summary the " +
                "quietest, never the most talkative. `getOrElseUnlessCancelled` is depth and not a " +
                "live guard, nothing cancels this scope today; it is what keeps a cancelled read " +
                "from ever being counted as a file that would not parse",
            listOf(
                "val content = runCatching { settings.notificationContent.first() }",
                ".getOrElseUnlessCancelled { NotificationContent.NONE }",
            ),
            codeLinesNaming(body, "notificationContent") + codeLinesNaming(body, "getOrElseUnlessCancelled"),
        )
        assertEquals(
            "…and no line of this body may read the setting with `getOrDefault`, which would count " +
                "a cancelled read as a settings file that would not parse",
            emptyList<String>(),
            codeLinesNaming(body, "getOrDefault"),
        )
        assertEquals(
            "…and the reading and the dismissal are the first two things the branch taken by a " +
                "write that returned does, in that order: the setting is read for this dismissal " +
                "and not for another one further down",
            listOf(
                "ReadMarkCleanup.DISMISS -> {",
                "val content = runCatching { settings.notificationContent.first() }",
                ".getOrElseUnlessCancelled { NotificationContent.NONE }",
                "Notifications.dismiss(",
            ),
            linesFrom(body, "ReadMarkCleanup.DISMISS -> {", 4),
        )
        assertEquals(
            "…and there is exactly one dismissal in the whole body, so that window is the whole " +
                "truth. A count and not a window, because every other assertion here pins lines it " +
                "expects to find: a second `Notifications.dismiss` anywhere below, in the branch " +
                "for a write that threw or after the `when`, passes them all one by one and puts " +
                "the original defect back whole, the shade emptied of the one banner that survived " +
                "a mark this device never managed to write",
            1,
            codeLinesNaming(body, "Notifications.dismiss(").size,
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

    /** [count] code lines of [body] from the one line equal to [anchor]. */
    private fun linesFrom(body: String, anchor: String, count: Int): List<String> {
        val lines = codeLinesNaming(body, "")
        val at = lines.indexOf(anchor)
        if (at < 0) error("no line '$anchor' in this body — did it get renamed or reflowed?")
        return lines.subList(at, minOf(at + count, lines.size))
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
