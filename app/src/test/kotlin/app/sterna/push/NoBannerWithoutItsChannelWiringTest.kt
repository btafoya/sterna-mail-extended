package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, and it has to be: [Notifications] is an Android object — every function here
 * takes a `Context` and ends in `NotificationManager.notify`, so none of them can be called from
 * a JVM test in this module. What is pinned is the WIRING, whole line by whole line; `contains`
 * is blind to any mutation that lengthens a line.
 *
 * The defect: on an install whose push was never armed in DIRECT mode (battery saver,
 * UnifiedPush only, a linked sub-account — `Transport.isDirect` is EVENT_SOURCE/IMAP_IDLE only,
 * `BootRestart`), `PushService` never starts, so `PushService.onCreate`'s
 * `ensureChannels` never runs. The `new_mail` channel then does not exist and Android drops
 * every banner in silence, with nothing in the app's own log:
 *
 *     E NotificationService: No Channel found for pkg=app.sterna, channelId=new_mail
 *
 * What the three rules protect:
 *
 *  1. every function that posts on `CHANNEL_MAIL` calls `ensureMailChannel(context)` as its FIRST
 *     code line — first, not merely somewhere: after the `notify` it is too late, the banner is
 *     already gone. The list is DISCOVERED from the source (any function whose body names
 *     `NotificationCompat.Builder(` and `CHANNEL_MAIL`), so a fourth posting site added to THIS
 *     file, building its notification in its own body, is held to the same rule without this test
 * being touched. Its reach stops there: a poster written in another file, or one whose
 *     builder comes from a helper, names neither literal and is not seen.
 *  2. the discovery still finds the three sites this lint exists for — without it, a rename or a
 *     refactor that empties the scan would leave rule 1 comparing two empty lists, green and
 *     blind.
 *  3. what `ensureMailChannel` actually creates, whole line by whole line. Rules 1 and 2 only see
 *     that a NAME is called; the channel id and the importance live one call deeper, where
 *     `CHANNEL_MAIL` → `CHANNEL_SERVICE` re-opens the whole defect and `IMPORTANCE_HIGH` →
 *     `IMPORTANCE_MIN` makes every banner silent — both with the two rules above still green.
 *
 * `createNotificationChannel` is idempotent and does not overwrite a channel the user has
 * retuned, so calling it on every post costs nothing and loses no setting. Never delete and
 * recreate.
 */
class NoBannerWithoutItsChannelWiringTest {

    @Test fun `every function that posts a new-mail banner ensures its channel first`() {
        val sites = postingSites()
        assertEquals(
            "a function of Notifications.kt builds a NotificationCompat.Builder on CHANNEL_MAIL " +
                "without ensuring the channel first. On an install whose push was never armed in " +
                "direct mode nothing else creates 'new_mail', and Android throws the banner away " +
                "in silence. The first code line of each posting site must be " +
                "'$ENSURE' — exactly as notifySendFailed already does.",
            sites.map { it.name to ENSURE },
            sites.map { it.name to it.firstCodeLine },
        )
    }

    /**
     * The banner path must ensure the MAIL channel ALONE. `ensureChannels` also creates
     * `push_service` — "Keeps a live connection to your mail server for instant mail" — and on
     * precisely the installs this repairs no such connection is ever opened, so posting a banner
     * would put a settings entry on screen that says something untrue. That channel belongs to
     * [PushService], which creates it when it really starts.
     */
    @Test fun `the mail channel is created alone, at the importance a banner needs`() {
        assertEquals(
            "ensureMailChannel is the body every banner runs before posting, and no test reaches " +
                "past its NAME. Pinned whole: the channel id (CHANNEL_SERVICE here would leave " +
                "'new_mail' uncreated and the original silence intact), the importance " +
                "(IMPORTANCE_HIGH — anything lower ships banners with no heads-up and no sound, " +
                "the reported symptom minus its logcat signature), and the absence of any second " +
                "channel.",
            listOf(
                "context.getSystemService(NotificationManager::class.java).createNotificationChannel(",
                "NotificationChannel(",
                "CHANNEL_MAIL,",
                "context.getString(R.string.notif_channel_mail),",
                "NotificationManager.IMPORTANCE_HIGH,",
                "),",
                ")",
            ),
            memberBody("ensureMailChannel"),
        )
    }

    @Test fun `the scan still sees the three posting sites this lint was written for`() {
        assertEquals(
            "the source scan no longer finds the known posting sites of Notifications.kt — a " +
                "rename or a refactor moved them, and the rule above is now comparing two empty " +
                "lists. Fix the scan (or this list) before trusting it again.",
            listOf("notifySendFailed", "notifyNewMail", "notifyGroupSummary"),
            postingSites().map { it.name }.filter { it in KNOWN_SITES },
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    private data class PostingSite(val name: String, val firstCodeLine: String)

    /** The code lines of `fun [name]`'s body in [NOTIFICATIONS]. */
    private fun memberBody(name: String): List<String> = membersOf(NOTIFICATIONS)
        .firstOrNull { member -> member.any { FUN_DECL.find(it)?.groupValues?.get(1) == name } }
        ?.let { bodyOf(it) }
        ?: error("Notifications.kt has no function named '$name' — did it get renamed?")

    /** Every function of [NOTIFICATIONS] whose body builds a notification on [CHANNEL_MAIL]. */
    private fun postingSites(): List<PostingSite> = membersOf(NOTIFICATIONS).mapNotNull { member ->
        val name = FUN_DECL.find(member.firstOrNull { FUN_DECL.containsMatchIn(it) } ?: "")
            ?.groupValues?.get(1) ?: return@mapNotNull null
        val body = bodyOf(member)
        val posts = body.any { "NotificationCompat.Builder(" in it } && body.any { "CHANNEL_MAIL" in it }
        if (posts) PostingSite(name, body.firstOrNull().orEmpty()) else null
    }

    /**
     * The code lines of a member's body. A block body opens on the first declaration line ending
     * in `{` (the parameter list may span several lines); anything else is an expression body,
     * whose first "line" is what follows the `=`.
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
     * Members are declared at four-space indentation and their bodies deeper, so a member runs
     * from its own declaration (or its KDoc) to the next one; a continuation line — `) {`, `}` —
     * never starts one.
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
        const val ENSURE = "ensureMailChannel(context)"
        val KNOWN_SITES = listOf("notifySendFailed", "notifyNewMail", "notifyGroupSummary")

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
