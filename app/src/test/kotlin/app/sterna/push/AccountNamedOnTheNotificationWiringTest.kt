package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, the last resort: [Notifications], [NewMailNotifier] and `SnoozeWorker` need a
 * NotificationManager and an Application. The decision itself is executed in [MailSubTextTest];
 * this only pins what reaches it, whole lines, arguments included (#193).
 */
class AccountNamedOnTheNotificationWiringTest {

    @Test fun `the poster sets the sub-text from the decision, account first and folder second`() {
        val body = bodyOf(NOTIFICATIONS, "notifyNewMail")
        assertEquals(
            "the decision is the only thing that reads the count, the name and the folder, and it " +
                "is handed the setting: without `content` the header line names the account and " +
                "the folder to a reader who asked for neither (#84)",
            listOf(
                "val subText = when (val line = MailNotificationText.subTextFor(content, accountCount, " +
                    "accountLabel, arrival, folderName)) {",
            ),
            codeLinesNaming(body, "subTextFor("),
        )
        assertEquals(
            "every arm of the decision, and the pair is composed by the formatted resource with the " +
                "account as %1\$s and the folder as %2\$s, never concatenated in Kotlin",
            listOf(
                "MailSubText.None -> null",
                "is MailSubText.Account -> line.account",
                "is MailSubText.Folder -> line.folder",
                "is MailSubText.AccountAndFolder -> " +
                    "context.getString(R.string.notif_account_folder, line.account, line.folder)",
            ),
            codeLinesNaming(body, "MailSubText."),
        )
        assertEquals(
            "set once, at construction, so the silent repost's recoverBuilder carries it over",
            listOf(".apply { if (subText != null) setSubText(subText) }"),
            codeLinesNaming(body, "setSubText"),
        )
    }

    @Test fun `the address handed to the decision is the reader's arrival rule, read from the account store`() {
        val body = bodyOf(NOTIFICATIONS, "notifyNewMail")
        assertEquals(
            "computed here and nowhere else, from the message itself and the account's identities, the " +
                "login only as the rule's search list: no caller can hand it in, and the snooze " +
                "wake-up gets it by construction (#243)",
            listOf(
                "val arrival = arrivalAddress(email, store.identities(accountId), store.account(accountId)?.username, " +
                    "folderName)",
                "val subText = when (val line = MailNotificationText.subTextFor(content, accountCount, " +
                    "accountLabel, arrival, folderName)) {",
            ),
            codeLinesNaming(body, "arrival"),
        )
        assertEquals(
            "the store is the application's own account store, read synchronously and offline",
            listOf(
                "val store = (context.applicationContext as Application).container.accountStore",
                "val arrival = arrivalAddress(email, store.identities(accountId), store.account(accountId)?.username, " +
                    "folderName)",
            ),
            codeLinesNaming(body, "store"),
        )
        assertEquals(
            "the notification decides its own-message rule as the reader does, and hands it to the " +
                "rule the list row shares",
            listOf(
                "val role = if (folderName == null) \"inbox\" else null",
                "val ownMessage = showsRecipients(role, unified = false, selfAuthored = isSelfAuthored(email.from, identities))",
                "return rowArrivalAddress(email, identities, login, showRecipients = ownMessage)",
            ),
            codeLinesOf(bodyOf(ARRIVAL, "arrivalAddress")).filterNot { it == "{" || it == "}" },
        )
        assertEquals(
            "the shared rule is the reader's guard applied to the reader's search, identities alone " +
                "counted by the guard: the login handed to deliveredToInHeader is the defect of #180",
            listOf(
                "val received = receivingAddress(email, accountAddresses(identities, login))",
                "return deliveredToInHeader(received, identities.map { it.email }, showRecipients)",
            ),
            codeLinesOf(bodyOf(ARRIVAL, "rowArrivalAddress")).filterNot { it == "{" || it == "}" },
        )
    }

    @Test fun `only the poster reaches the decision, so no path posts a header line around the address`() {
        val reaching = File(root, MAIN_PATH).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> codeLinesNaming(file.readText(), "subTextFor(").isNotEmpty() }
            .map { it.name }
            .toSortedSet()
        assertEquals(
            "the decision's own file and the poster, and no third: a caller of its own would post a " +
                "sub-text that never consulted the arrival address",
            sortedSetOf("MailNotificationText.kt", "Notifications.kt"),
            reaching,
        )
        val snooze = codeLinesOf(SNOOZE_WORKER.readText())
        assertEquals(
            "the snooze wake-up posts through notifyNewMail, once, and composes no header line itself",
            listOf("Notifications.notifyNewMail("),
            snooze.filter { "notifyNewMail" in it || "subTextFor" in it || "MailSubText" in it || "setSubText" in it },
        )
    }

    @Test fun `the folder diff names the account by its drawer label, not its login`() {
        val body = bodyOf(NOTIFIER, "notifyDiff")
        assertEquals(
            "label() is the drawer's name; credentials.username is the login, the same address on " +
                "every shared mailbox under it. Read once and handed over twice: to the banners, " +
                "and to the group summary standing above them, which cannot go by another name",
            listOf(
                "val accountLabel = accountStore.account(credentials.id)?.label()",
                "accountLabel,",
                "accountLabel,",
            ),
            codeLinesNaming(body, "accountLabel"),
        )
        assertEquals(
            "every account in the store, unfiltered, handed to the poster",
            listOf("val accountCount = accountStore.accounts().size", "accountCount,"),
            codeLinesNaming(body, "accountCount"),
        )
        assertEquals(
            "and the posting call carries both, before the folder",
            listOf("silent,", "accountCount,", "accountLabel,", "folderName,"),
            callLines(body, "Notifications.notifyNewMail(").subList(4, 8),
        )
    }

    @Test fun `the account count is read once per pass, before the posting loop`() {
        val lines = codeLinesOf(bodyOf(NOTIFIER, "notifyDiff"))
        val read = lines.indexOf("val accountCount = accountStore.accounts().size")
        val loop = lines.indexOf("NotificationPreviews.postGatheredFirst(")
        assertEquals(
            "notifyDiff names `.accounts()` on exactly one line; `account(id)` re-reads the list " +
                "inside the store and is not what this counts",
            1,
            lines.count { ".accounts()" in it },
        )
        assertTrue(
            "and that line comes before postGatheredFirst: inside its post argument it would be read once per message",
            read in 0 until loop,
        )
    }

    @Test fun `the snooze wake-up passes the account's label and the count, and no folder`() {
        val body = bodyOf(SNOOZE_WORKER, "doWork")
        assertEquals(
            listOf("accountCount = container.accountStore.accounts().size,"),
            codeLinesNaming(body, "accountCount"),
        )
        assertEquals(
            listOf("accountLabel = container.accountStore.account(accountId)?.label(),"),
            codeLinesNaming(body, "accountLabel"),
        )
        assertEquals(listOf("folderName = null,"), codeLinesNaming(body, "folderName"))
    }

    @Test fun `every language that composes the group line composes the account and folder pair`() {
        val files = File(root, RES_PATH).listFiles().orEmpty()
            .map { File(it, "strings.xml") }
            .filter { it.isFile && "name=\"notif_group_line\"" in it.readText() }
        assertEquals("the default and its ten translations", 11, files.size)
        files.forEach { file ->
            assertEquals(
                "${file.parentFile.name}: the pair keeps the account first and the middle dot",
                listOf("<string name=\"notif_account_folder\">%1\$s · %2\$s</string>"),
                file.readLines().map { it.trim() }.filter { "name=\"notif_account_folder\"" in it },
            )
        }
    }

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

    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLinesOf(body).filter { needle in it }

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
        private const val NOTIFIER_PATH = "app/src/main/kotlin/app/sterna/push/NewMailNotifier.kt"
        private const val NOTIFICATIONS_PATH = "app/src/main/kotlin/app/sterna/push/Notifications.kt"
        private const val SNOOZE_WORKER_PATH = "app/src/main/kotlin/app/sterna/snooze/SnoozeWorker.kt"
        private const val RES_PATH = "app/src/main/res"
        private const val MAIN_PATH = "app/src/main/kotlin"
        private const val ARRIVAL_PATH = "app/src/main/kotlin/app/sterna/push/ArrivalAddress.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, NOTIFIER_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        private val NOTIFIER: File by lazy { File(root, NOTIFIER_PATH) }
        private val NOTIFICATIONS: File by lazy { File(root, NOTIFICATIONS_PATH) }
        private val SNOOZE_WORKER: File by lazy { File(root, SNOOZE_WORKER_PATH) }
        private val ARRIVAL: File by lazy { File(root, ARRIVAL_PATH) }
    }
}
