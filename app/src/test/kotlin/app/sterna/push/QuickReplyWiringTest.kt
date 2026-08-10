package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, and a narrow one: nothing in this chain runs in a JVM test — the receiver needs
 * an Application and a NotificationManager, the repository a database and a socket. The DECISION
 * is executed next door in [QuickReplyOutcomeTest]; what is pinned here is the WIRING that carries
 * it, whole line by whole line. Never `contains`: a `contains` is blind to every mutation that
 * LENGTHENS the line (adding `to=` to a mailto, widening a timeout, re-adding a cancel).
 *
 * The harm: a quick reply typed into a notification disappeared — sent nowhere, saved nowhere, and
 * the notification dismissed on the way out. Four links close it: the recipient is read through
 * the SAME function the composer uses; the only network call is on a short leash; the notification
 * comes down only after the Outbox took the text; and every other end hands the text back.
 */
class QuickReplyWiringTest {

    @Test fun `mark read and delete still dismiss, exactly as before`() {
        val body = bodyOf(RECEIVER, "onReceive")
        assertEquals(
            "the two server-side actions must keep their unconditional dismissal: their work is " +
                "on the server's copy and is retryable from the list, so nothing is lost by it",
            listOf("dismiss(appContext, accountId, credentials, notifId)"),
            codeLinesNaming(body, "dismiss("),
        )
        assertEquals(
            "Reply must leave the common path BEFORE that dismissal — put it back in the `when` " +
                "and the notification is taken down whatever happened to the text",
            listOf("if (action == ACTION_REPLY) {"),
            codeLinesNaming(body, "ACTION_REPLY"),
        )
        assertEquals(
            "and the two actions themselves stay where they were",
            listOf(
                "ACTION_MARK_READ -> container.mailRepository.setRead(credentials, emailId, true)",
                "ACTION_DELETE -> container.mailRepository.delete(credentials, emailId)",
            ),
            codeLinesNaming(body, "container.mailRepository."),
        )
    }

    @Test fun `the reply notification comes down only once the outbox has the text`() {
        val body = bodyOf(RECEIVER, "reply")
        assertEquals(
            "the recipient and the subject come from the CACHE — no notification's reply may wait " +
                "on a socket to learn where it is going",
            listOf("runCatching { repo.cachedEmail(accountId, emailId) }.getOrNull()"),
            codeLinesNaming(body, "cachedEmail("),
        )
        assertEquals(
            "the queueing must be the guarded thing: a throw from enqueueSend (require() on an " +
                "empty recipient, a full disk) has to read as NOT queued",
            listOf("runCatching { send(repo, credentials, emailId, outcome) }.isSuccess"),
            codeLinesNaming(body, "runCatching { send("),
        )
        assertEquals(
            "what happens after the attempt is DECIDED (QuickReplyOutcomeTest executes it), not " +
                "arranged here: the branches are what got swapped by hand and stayed green",
            listOf("when (val act = quickReplyAct(outcome, queued)) {"),
            codeLinesNaming(body, "quickReplyAct("),
        )
        assertEquals(
            "each arm carries its action ON ITS OWN LINE, so swapping two arms cannot pass as the " +
                "same set of lines. This is the shape the lint can hold; the ORDER is held next door",
            listOf(
                "QuickReplyAct.DoNothing -> Unit",
                "QuickReplyAct.Dismiss -> dismiss(context, accountId, credentials, notifId)",
                "is QuickReplyAct.HandBack -> Notifications.notifySendFailed(context, act.subject, act.body)",
            ),
            codeLinesNaming(body, "QuickReplyAct."),
        )
        assertEquals(
            "the whole file must cancel in ONE place, the helper that also refreshes the group " +
                "summary (#56/#92) — a second cancel anywhere is the bug walking back in",
            listOf("NotificationManagerCompat.from(context).cancel(notifId)"),
            codeLinesNaming(RECEIVER.readText(), "cancel(notifId)"),
        )
    }

    @Test fun `the fetch is abandoned on the budget, not waited for`() {
        // The leash that was not one: `withTimeoutOrNull { repo.fetchEmail(…) }` only ever regains
        // control at a SUSPENSION point, and fetchEmail blocks its thread all the way down (IMAP
        // opens the session with timeout = 0 and retries once; JMAP does an OkHttp execute()). A
        // reachable-but-silent server — captive portal, dropped VPN, stale pooled connection — then
        // held the receiver far past goAsync's ~10 s, and the enqueue below it never ran.
        val body = bodyOf(RECEIVER, "fetchWithinBudget")
        assertEquals(
            "the blocking call must run in a job of its OWN scope — a child would keep the parent " +
                "waiting on the very socket the budget exists to escape",
            listOf("val fetching = CoroutineScope(SupervisorJob() + Dispatchers.IO)"),
            codeLinesNaming(body, "CoroutineScope("),
        )
        assertEquals(
            "and it is the only fetch left on this path",
            listOf(".async { repo.fetchEmail(credentials, emailId) }"),
            codeLinesNaming(body, "fetchEmail("),
        )
        assertEquals(
            "the budget is spent on await() — a real suspension point — never on the blocking " +
                "call itself, or the timeout is decoration",
            listOf(
                "val fetched = runCatching { withTimeoutOrNull(FETCH_BUDGET_MS) { fetching.await() } }.getOrNull()",
            ),
            codeLinesNaming(body, "withTimeoutOrNull"),
        )
        assertEquals(
            "and the abandoned job is cancelled rather than left to run to completion",
            listOf("fetching.cancel()"),
            codeLinesNaming(body, "fetching.cancel("),
        )
        assertEquals(
            "the leash is a named constant, and it is short — threading headers are never worth " +
                "the text",
            listOf("private const val FETCH_BUDGET_MS = 5_000L"),
            codeLinesNaming(RECEIVER.readText(), "FETCH_BUDGET_MS ="),
        )
    }

    @Test fun `the queued message carries the typed text, and no dead shortcut guards it`() {
        val body = bodyOf(RECEIVER, "send")
        assertEquals(
            "the send asks for the threading headers through the bounded helper, and sends " +
                "whatever it got — including nothing",
            listOf("val fetched = fetchWithinBudget(repo, credentials, emailId)"),
            codeLinesNaming(body, "fetchWithinBudget("),
        )
        assertEquals(
            "the recipient, subject and body handed to the Outbox are the decided ones",
            listOf("to = listOf(outcome.to),", "subject = outcome.subject,", "body = outcome.body,"),
            codeLinesNaming(body, "outcome."),
        )
        assertEquals(
            "⛔ the `cached row already has a Message-Id` shortcut described a path that CANNOT " +
                "run: EmailEntity has no messageId/references column and EmailMapper.toEmail never " +
                "fills them, so cachedIds was always empty. Dead code that documents a lie",
            emptyList<String>(),
            codeLinesNaming(RECEIVER.readText(), "cachedIds"),
        )
    }

    @Test fun `the recipient is chosen by the composer's own function, not a second copy`() {
        assertEquals(
            "the choice of who a reply answers must exist ONCE (ComposeText.replyRecipient), or " +
                "the next change to it — Reply-To — has to be made twice and will be made once",
            listOf("val to = cached?.let { replyRecipient(it) }.orEmpty().trim()"),
            codeLinesNaming(QUICK_REPLY.readText(), "replyRecipient("),
        )
    }

    @Test fun `the handed-back text travels as a body-only mailto`() {
        val source = NOTIFICATIONS.readText()
        assertEquals(
            "the salvaged text must reach the composer through the existing mailto: route — no " +
                "new screen, no new string. NO `to=` beside the body: MailTo.parse hands back an " +
                "address with an EMPTY body when both are given, and here there is no address",
            listOf(
                """Uri.parse("mailto:?subject=${'$'}{Uri.encode(subject)}&body=${'$'}{Uri.encode(body)}")""",
            ),
            codeLinesNaming(source, "mailto:"),
        )
        assertEquals(
            "the new parameter is optional, so today's caller (ScheduledSendWorker) is untouched",
            listOf("""fun notifySendFailed(context: Context, subject: String, body: String = "") {"""),
            codeLinesNaming(source, "fun notifySendFailed"),
        )
        assertEquals(
            "the CALL SITE's arguments are pinned too, not just the shape of the URI: " +
                "draftUri(subject, \"\") compiles, opens an empty composer, and reads as fixed",
            listOf(
                "Intent(Intent.ACTION_VIEW, draftUri(subject, body), context, MainActivity::class.java)",
                "private fun draftUri(subject: String, body: String): Uri =",
            ),
            codeLinesNaming(source, "draftUri("),
        )
        assertEquals(
            "a notification holding salvaged text must NOT auto-cancel: the mailto: is consumed by " +
                "the NavHost, which is only composed when signed in, so the hand-back raised for a " +
                "signed-out account opens nothing — and an auto-cancelled tap would then be the " +
                "second and final loss of the text. Unchanged for a body-less failure",
            listOf(".setAutoCancel(body.isBlank())"),
            // Scoped to this one builder: new-mail and snooze notifications auto-cancel on tap
            // and must keep doing so.
            codeLinesNaming(bodyOf(NOTIFICATIONS, "notifySendFailed"), "setAutoCancel("),
        )
        assertEquals(
            "and the notification is keyed on the body too: two salvaged texts under one key " +
                "replace each other under FLAG_UPDATE_CURRENT, which loses the first",
            listOf("""val key = if (body.isBlank()) "sendfail:${'$'}subject" else "sendfail:${'$'}subject:${'$'}body""""),
            codeLinesNaming(source, "val key ="),
        )
    }

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
        private const val RECEIVER_PATH = "$PUSH_SOURCES/NotificationActionReceiver.kt"
        private const val QUICK_REPLY_PATH = "$PUSH_SOURCES/QuickReply.kt"
        private const val NOTIFICATIONS_PATH = "$PUSH_SOURCES/Notifications.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, RECEIVER_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val RECEIVER: File by lazy { File(root, RECEIVER_PATH) }
        private val QUICK_REPLY: File by lazy { File(root, QUICK_REPLY_PATH) }
        private val NOTIFICATIONS: File by lazy { File(root, NOTIFICATIONS_PATH) }
    }
}
