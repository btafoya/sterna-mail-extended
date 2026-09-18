package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint ([Notifications] needs Android to run); the holes it leaves are named in [HOLES].
 */
class NoBannerWithoutItsChannelWiringTest {

    @Test fun `every function that posts a banner ensures that banner's channel first`() {
        val sites = postingSites()
        assertEquals(
            "a function of Notifications.kt builds a NotificationCompat.Builder without ensuring " +
                "the channel it names first, or on a channel this lint does not know. On a fresh " +
                "install nothing else has created the channel, and Android throws the banner away " +
                "in silence. The first code line of each posting site must be the ensure of the " +
                "channel its builder names: ${ENSURE_FOR.entries.joinToString { (b, e) -> "'$b' needs '$e'" }}. " +
                HOLES,
            sites.map { it.name to (ENSURE_FOR[it.builderLine] ?: "a channel listed in ENSURE_FOR") },
            sites.map { it.name to it.firstCodeLine },
        )
    }

    @Test fun `a mail banner is built on its own account's channel, never on a shared one`() {
        assertEquals(
            "a new-mail banner or its summary built on a channel common to every account puts all " +
                "the accounts back behind one sound: the setting a user changes in Android for " +
                "one account would change them all",
            listOf(
                "notifyNewMail" to "NotificationCompat.Builder(context, mailChannelId(accountId))",
                "notifyGroupSummary" to "NotificationCompat.Builder(context, mailChannelId(accountId))",
            ),
            postingSites().filter { it.name in MAIL_SITES }.map { it.name to it.builderLine },
        )
        assertEquals(
            "the channel of older builds is only ever deleted, never posted on: a banner put on it " +
                "keeps it alive, and rings with whatever the user had set for every account at once",
            emptyList<String>(),
            membersOf(NOTIFICATIONS).flatten().filter { "LEGACY_CHANNEL_MAIL" in it && "Builder(" in it },
        )
    }

    @Test fun `each channel is created alone, on its own id, at the importance a banner needs`() {
        assertEquals(
            "ensureMailChannel must create the channel of THIS account: the id built by " +
                "mailChannelId(accountId), named from notif_channel_mail_account when the label is " +
                "known and never left unnamed, at IMPORTANCE_HIGH (anything lower ships banners " +
                "with no heads-up and no sound), and no second channel",
            listOf(
                "val manager = context.getSystemService(NotificationManager::class.java)",
                "val id = mailChannelId(accountId)",
                "val name = accountLabel?.let { context.getString(R.string.notif_channel_mail_account, it) }",
                "?: manager.getNotificationChannel(id)?.name",
                "?: context.getString(R.string.notif_channel_mail)",
                "manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH))",
            ),
            memberBody("ensureMailChannel"),
        )
        assertEquals(
            "…and the account's channel id is the account id behind a fixed prefix: the name " +
                "the account shows changes, and a channel keyed on it would be a new channel, " +
                "with Android's defaults, at every rename",
            listOf("MAIL_CHANNEL_PREFIX + accountId"),
            memberBody("mailChannelId"),
        )
        assertEquals(
            "ensureSendFailedChannel creates the send-failure channel and nothing else, at " +
                "IMPORTANCE_HIGH",
            listOf(
                "context.getSystemService(NotificationManager::class.java).createNotificationChannel(",
                "NotificationChannel(",
                "CHANNEL_SEND_FAILED,",
                "context.getString(R.string.notif_channel_send_failed),",
                "NotificationManager.IMPORTANCE_HIGH,",
                "),",
                ")",
            ),
            memberBody("ensureSendFailedChannel"),
        )
    }

    @Test fun `the scan still sees the three posting sites this lint was written for`() {
        assertEquals(
            "the source scan no longer finds the known posting sites of Notifications.kt — a " +
                "rename or a refactor moved them, and the rule above is now comparing two empty " +
                "lists. Fix the scan (or this list) before trusting it again.",
            listOf(
                "notifySendFailed" to "NotificationCompat.Builder(context, CHANNEL_SEND_FAILED)",
                "notifyNewMail" to "NotificationCompat.Builder(context, mailChannelId(accountId))",
                "notifyGroupSummary" to "NotificationCompat.Builder(context, mailChannelId(accountId))",
            ),
            postingSites().filter { it.name in KNOWN_SITES }.map { it.name to it.builderLine },
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    private data class PostingSite(val name: String, val firstCodeLine: String, val builderLine: String)

    /** The code lines of `fun [name]`'s body in [NOTIFICATIONS]. */
    private fun memberBody(name: String): List<String> = membersOf(NOTIFICATIONS)
        .firstOrNull { member -> member.any { FUN_DECL.find(it)?.groupValues?.get(1) == name } }
        ?.let { bodyOf(it) }
        ?: error("Notifications.kt has no function named '$name' — did it get renamed?")

    /** Every function of [NOTIFICATIONS] building a notification on any channel but the service's. */
    private fun postingSites(): List<PostingSite> = membersOf(NOTIFICATIONS).mapNotNull { member ->
        val name = FUN_DECL.find(member.firstOrNull { FUN_DECL.containsMatchIn(it) } ?: "")
            ?.groupValues?.get(1) ?: return@mapNotNull null
        val body = bodyOf(member)
        val builder = body.firstOrNull { "NotificationCompat.Builder(" in it } ?: return@mapNotNull null
        val line = "NotificationCompat.Builder(" + builder.substringAfter("NotificationCompat.Builder(")
        if ("CHANNEL_SERVICE" in line) null else PostingSite(name, body.firstOrNull().orEmpty(), line)
    }

    /**
     * The code lines of a member's body. A block body opens on the first declaration line ending
     */
    private fun bodyOf(member: List<String>): List<String> {
        val decl = member.indexOfFirst { FUN_DECL.containsMatchIn(it) }
        val open = (decl until member.size).firstOrNull { member[it].endsWith("{") }
        if (open != null) return member.drop(open + 1).dropLastWhile { it == "}" }
        return (listOf(member[decl].substringAfter("=").trim()) + member.drop(decl + 1))
            .filter { it.isNotBlank() }
    }

    /**
     * The members of the single `object` in [file], each as its trimmed code lines, comments cut.
     */
    private fun membersOf(file: File): List<List<String>> {
        val lines = file.readLines()
        val starts = lines.indices.filter { MEMBER_START.containsMatchIn(lines[it]) }
        return starts.mapIndexed { i, start ->
            lines.subList(start, starts.getOrElse(i + 1) { lines.size })
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
                    else withoutTrailingComment(trimmed).takeIf { it.isNotBlank() }
                }
        }
    }

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
        val ENSURE_FOR = mapOf(
            "NotificationCompat.Builder(context, mailChannelId(accountId))" to
                "ensureMailChannel(context, accountId, accountLabel)",
            "NotificationCompat.Builder(context, CHANNEL_SEND_FAILED)" to "ensureSendFailedChannel(context)",
        )
        val KNOWN_SITES = listOf("notifySendFailed", "notifyNewMail", "notifyGroupSummary")
        val MAIL_SITES = listOf("notifyNewMail", "notifyGroupSummary")

        const val HOLES = "What this lint does not hold (CONTRIBUTING.md, holes 1 and 3): that " +
            "`accountId` in the ensure and in the builder are bound to the same value, and that " +
            "no `return` stands between the first line and the post. A banner posted without " +
            "`NotificationCompat.Builder` (repostSilently) is outside this scan and pinned in " +
            "LiveBannerSurvivesItsSummaryWiringTest."

        /** A member of the object: four spaces, then something that is not a continuation. */
        val MEMBER_START = Regex("""^ {4}[^\s)}]""")
        val FUN_DECL = Regex("""^(?:private |internal |public )?(?:inline )?fun ([A-Za-z_]\w*)""")

        const val NOTIFICATIONS_PATH = "app/src/main/kotlin/app/sterna/push/Notifications.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, NOTIFICATIONS_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val NOTIFICATIONS: File by lazy { File(root, NOTIFICATIONS_PATH) }
    }
}
