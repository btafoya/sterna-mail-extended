package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, and a narrow one: nothing in this chain runs in a JVM test, the receiver needs
 */
class QuickReplyWiringTest {

    @Test fun `mark read and delete take the banner down at once, and hand the write over`() {
        val body = bodyOf(RECEIVER, "onReceive")
        assertEquals(
            "ONE dismissal on this path, and it is handed to `carryOutBannerTap`, which runs it " +
                "only on a decided hand-over (BannerWriteTest executes it). A dismissal written " +
                "beside it is a Delete that moved nothing taking the banner down all the same (#171)",
            listOf("dismiss = { dismiss(appContext, accountId, credentials, emailId, notifId) },"),
            codeLinesNaming(body, "dismiss("),
        )
        assertEquals(
            "the write handed over is the one the intent's action names: a literal here makes " +
                "both buttons the same write",
            listOf(
                "val action = intent.action ?: return",
                "val write = BannerWrite.ofAction(action)",
            ),
            codeLinesNaming(bodyOf(RECEIVER, "onReceive"), "").filter { it.startsWith("val action") || it.startsWith("val write") },
        )
        assertEquals(
            "Reply must leave the common path BEFORE that dismissal — put it back in the hand-over " +
                "and the notification is taken down whatever happened to the text",
            listOf("if (action == ACTION_REPLY) {"),
            codeLinesNaming(body, "ACTION_REPLY"),
        )
        assertEquals(
            "what happens to the banner is DECIDED, with the intent's write, whether there is " +
                "anyone to act for, and the delete's own decision as arguments: a literal in any " +
                "of the three is a rule that answers the same thing forever",
            listOf("bannerTap(write, signedIn = credentials != null, deleteAct),"),
            codeLinesNaming(body, "bannerTap("),
        )
        assertEquals(
            "the write is QUEUED, for the account and the message of the intent, and that is all " +
                "this receiver does with the server. Done here instead, the cancel waits on a cold " +
                "session again and the banner outlives the process that should have taken it down",
            listOf("enqueue = { BannerWriteWorker.enqueue(appContext, accountId, emailId, it) },"),
            codeLinesNaming(body, "enqueue"),
        )
        assertEquals(
            "…and EVERY use of the repository in onReceive, enumerated whole: the reply hand-off " +
                "and the delete's cache-only decision. A `setRead` or a `delete` added beside them " +
                "is the server write back inside the broadcast window",
            listOf(
                "appContext, container.mailRepository, container.settingsRepository,",
                "bannerDeleteActFromCache(container.mailRepository, accountId, emailId)",
            ),
            codeLinesNaming(body, "container.mailRepository"),
        )
    }

    /**
     * The end where there is nobody to act for: the decision keeps the banner (BannerWriteTest),
     * and what is pinned here is that the decision is told so.
     */
    @Test fun `with no credentials nothing runs, so nothing may claim it did`() {
        val body = bodyOf(RECEIVER, "onReceive")
        assertEquals(
            "the decision hears whether credentials were found: a `signedIn = true` puts the " +
                "banner of a signed-out account down for a write nothing will ever carry out",
            listOf(
                "val credentials = container.accountStore.credentials(accountId)",
                "container.accountStore, credentials, accountId, emailId, notifId, replyText,",
                "val deleteAct = if (credentials != null && write == BannerWrite.DELETE) {",
                "bannerTap(write, signedIn = credentials != null, deleteAct),",
                "dismiss = { dismiss(appContext, accountId, credentials, emailId, notifId) },",
            ),
            codeLinesNaming(body, "credentials"),
        )
    }

    /**
     * The banner's Delete asks (locally) the question the screen asks over the socket: would this
     * MOVE anything. The receiver and the worker ask it through the same function.
     */
    @Test fun `the banner's delete asks the local caches, and only the local caches`() {
        val body = bodyOf(WORKER, "bannerDeleteReadsFromCache")
        assertEquals(
            "the message's own folder comes from the CACHE, scoped to the account (#31: an id is " +
                "unique only inside its account)",
            listOf("val cached = runCatching { repo.cachedEmail(accountId, emailId) }.getOrNull()"),
            codeLinesNaming(body, "cachedEmail("),
        )
        assertEquals(
            "and its role from the FOLDER cache — `mailboxRole` is a `roleForId` on the local " +
                "table, arguments pinned: the account and the folder of the row just read",
            listOf("val role = runCatching { repo.mailboxRole(accountId, cached?.mailboxId) }.getOrNull()"),
            codeLinesNaming(body, "mailboxRole("),
        )
        assertEquals(
            "and whether the account has a Trash from the same cache, NEVER accountHasTrash — " +
                "and it falls back to `true`, the ordinary path: a failed read must never turn " +
                "into a refusal to delete",
            listOf("val hasTrash = runCatching { repo.hasCachedTrash(accountId) }.getOrDefault(true)"),
            codeLinesNaming(body, "hasCachedTrash("),
        )
        assertEquals(
            "the two reads are handed back as read, in their own places",
            listOf("return BannerDeleteReads(role, hasTrash)"),
            codeLinesNaming(body, "return"),
        )
        assertEquals(
            "what happens next is DECIDED (BannerDeleteActTest executes it), on those reads, and " +
                "it is the one end of the receiver's question",
            listOf(
                "val reads = bannerDeleteReadsFromCache(repo, accountId, emailId)",
                "return bannerDeleteAct(reads.role, reads.hasTrash)",
            ),
            codeLinesNaming(bodyOf(WORKER, "bannerDeleteActFromCache"), "").filter { it.startsWith("val ") || it.startsWith("return") },
        )
        assertEquals(
            "the receiver reads that decision for the intent's account and message, and only " +
                "for a Delete of an account it can act for",
            listOf("bannerDeleteActFromCache(container.mailRepository, accountId, emailId)"),
            codeLinesNaming(bodyOf(RECEIVER, "onReceive"), "bannerDeleteActFromCache("),
        )
        assertEquals(
            "no arm of the decision is carried out in the receiver: the flight and the move are " +
                "the worker's, and a delete written here goes out inside the broadcast window",
            emptyList<String>(),
            listOf("BannerDeleteAct.", "deleteHoldingItsRow(", ".delete(", ".setRead(", "beginRemoval(")
                .flatMap { codeLinesNaming(RECEIVER.readText(), it) },
        )
    }

    @Test fun `nothing under push resolves a role over the network`() {
        val offenders = File(root, PUSH_SOURCES).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val source = file.readText()
                (codeLinesNaming(source, "deleteWouldDestroy(") + codeLinesNaming(source, "accountHasTrash("))
                    .map { "${file.name}: $it" }
            }
            .toList()
        assertEquals(
            "⛔ both of these resolve the Trash over the SOCKET (roleMailboxId: an IMAP LIST, a " +
                "JMAP session). A broadcast receiver has ~10 s of goAsync for everything it " +
                "does, and a reachable-but-silent server spends all of it — the process is then " +
                "killed mid-action. Every question this package asks about a folder is answered " +
                "from the local caches or not asked at all",
            emptyList<String>(),
            offenders,
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
            listOf("runCatching { send(repo, store, credentials, accountId, emailId, cached, outcome) }.isSuccess"),
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
                "QuickReplyAct.Dismiss -> dismiss(context, accountId, credentials, emailId, notifId)",
                "is QuickReplyAct.HandBack -> {",
            ),
            codeLinesNaming(body, "QuickReplyAct."),
        )
        assertEquals(
            "the hand-back banner is posted with the setting this path READ — a constant, or the " +
                "old three-argument call under a restored default, is the subject back on the lock " +
                "screen of a user who asked for 'Sender only'",
            listOf("Notifications.notifySendFailed(context, act.subject, content, act.body)"),
            codeLinesNaming(body, "notifySendFailed("),
        )
        assertEquals(
            "and that read is GUARDED. A corrupt settings file now reads as the defaults, " +
                "notification at its quietest, but a read that fails for another reason may still " +
                "make `first()` throw; onReceive's catch would swallow it and NO banner would be " +
                "posted — the typed text, which lives in the broadcast intent and nowhere else, " +
                "would die there",
            listOf("val content = runCatching { settings.notificationContent.first() }"),
            codeLinesNaming(body, "notificationContent"),
        )
        assertEquals(
            "…and it falls back to the QUIETEST position: an unreadable setting must make the " +
                "banner say too little, never too much",
            listOf(".getOrDefault(NotificationContent.NONE)"),
            codeLinesNaming(body, "getOrDefault(NotificationContent."),
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
        // control at a suspension point, and fetchEmail blocks its thread all the way down (IMAP
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

    @Test fun `the quick reply is sent as the identity the decision picked, of the notification's account`() {
        val body = bodyOf(RECEIVER, "send")
        assertEquals(
            "EVERY line of `send` naming the sender, enumerated: the decision, then its answer in " +
                "BOTH fields. A missing `fromEmail` is the server's first identity again, on JMAP",
            listOf(
                "val from = quickReplyFrom(accountId, identities, store.defaultIdentityId(accountId), cached)",
                "fromName = from?.name,",
                "fromEmail = from?.email,",
            ),
            codeLinesNaming(body, "from"),
        )
        val receiver = RECEIVER.readText()
        assertEquals(
            "the identities are those of the INTENT's account, argument pinned: " +
                "`AccountStore.identities(null)` is the current account, and a notification of " +
                "account B would answer as account A",
            listOf("val identities = store.identities(accountId)"),
            codeLinesNaming(receiver, "identities("),
        )
        assertEquals(
            "…and so is the default identity, for the same reason",
            listOf("val from = quickReplyFrom(accountId, identities, store.defaultIdentityId(accountId), cached)"),
            codeLinesNaming(receiver, "defaultIdentityId("),
        )
        assertEquals(
            "the send is handed the account and the cached row `reply` already read: no second read",
            listOf("runCatching { send(repo, store, credentials, accountId, emailId, cached, outcome) }.isSuccess"),
            codeLinesNaming(bodyOf(RECEIVER, "reply"), "runCatching { send("),
        )
    }

    @Test fun `the sender is chosen by the composer's own functions, not a second copy`() {
        val decision = bodyOf(QUICK_REPLY, "quickReplyFrom")
        assertEquals(
            "the address the mail came to (#81) is the composer's `receivingFromOption`, which " +
                "reads To then Cc through `receivingAddress`",
            listOf("val received = cached?.let { receivingFromOption(options, accountId, it) }"),
            codeLinesNaming(decision, "receivingFromOption("),
        )
        assertEquals(
            "and without one, the composer's `defaultFromOption`: default identity, then the first",
            listOf("return (received ?: defaultFromOption(options, accountId, defaultIdentityId))?.identity"),
            codeLinesNaming(decision, "defaultFromOption("),
        )
        val source = QUICK_REPLY.readText()
        assertEquals(
            "no second walk over the recipients or the identities in this file: a copy is the rule " +
                "changed in the composer and not here",
            emptyList<String>(),
            listOf("receivingAddress(", ".cc", "firstOrNull").flatMap { codeLinesNaming(source, it) },
        )
        val prepare = bodyOf(COMPOSE_VM, "prepare")
        assertEquals(
            "the composer opens on the same function, with the account it prefers",
            listOf("_selectedFrom.value = defaultFromOption(options, preferred, defaultIdentityId)"),
            codeLinesNaming(prepare, "defaultFromOption("),
        )
        assertEquals(
            "…and keeps no copy of it beside the call",
            emptyList<String>(),
            codeLinesNaming(prepare, "firstOrNull { it.accountId == preferred"),
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
            "the salvaged text stays optional (a scheduled send has none), but [content] must NOT " +
                "get a default: a default is how a future caller leaks the subject in silence, and " +
                "it is exactly how the snooze wake-up leaked sender and subject in #84",
            listOf(
                """fun notifySendFailed(context: Context, subject: String, content: NotificationContent, body: String = "") {""",
            ),
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

    /**
     * The banner of a failed send is the one notification that never asked what it was allowed to
     * show: it put the subject in the collapsed line and in the expanded one, at IMPORTANCE_HIGH,
     * whatever the notification-content setting said. The decision is executed next door
     * ([MailNotificationTextTest]); pinned here is that the banner is built from that decision's
     * result and from nothing else, the leak cannot come back through the builder.
     */
    @Test fun `the failure banner shows the decided line, never the raw subject`() {
        val body = bodyOf(NOTIFICATIONS, "notifySendFailed")
        assertEquals(
            "the stand-in is resolved here and handed IN: MailNotificationText holds no resources",
            listOf("val noSubject = context.getString(R.string.message_no_subject)"),
            codeLinesNaming(body, "message_no_subject"),
        )
        assertEquals(
            "and the line is DECIDED, with the read setting and the real subject as arguments — " +
                "pinning the call without its arguments would let `content` be swapped for a constant",
            listOf("val line = MailNotificationText.sendFailureLine(content, subject, noSubject)"),
            codeLinesNaming(body, "sendFailureLine("),
        )
        assertEquals(
            "the collapsed line is the decided one. `subject` here is the leak itself",
            listOf("setContentText(line)"),
            codeLinesNaming(body, "setContentText("),
        )
        assertEquals(
            "so is the expanded one — the lock screen reads THIS, and the first fix of this kind " +
                "left bigText behind",
            listOf("setStyle(NotificationCompat.BigTextStyle().bigText(line))"),
            codeLinesNaming(body, "bigText("),
        )
        assertEquals(
            "and when the decision says nothing may be shown, NEITHER line is set at all: the " +
                "title alone already says the message was not sent",
            listOf("if (line != null) {"),
            codeLinesNaming(body, "if (line != null)"),
        )
        assertEquals(
            "the title never varies — no new string was added for this, and none is needed",
            listOf(".setContentTitle(context.getString(R.string.notif_send_failed_title))"),
            codeLinesNaming(body, "setContentTitle("),
        )
        assertEquals(
            "⛔ AND THIS IS THE WHOLE OF IT: every line of the builder that names `subject`, " +
                "enumerated. The pins above hold the lines they name, and are blind by " +
                "construction to a line ADDED beside them — `.setSubText(subject)` or " +
                "`.setTicker(subject)` puts the subject back in the notification header, " +
                "collapsed, expanded and on the lock screen, and would pass every other assertion " +
                "in this file. The four lines below are the only ones allowed to touch it, and " +
                "not one of them reaches the screen: an id, a mailto: inside a PendingIntent (which " +
                "shows nobody its Intent), a resource name, and the argument handed to the decision",
            listOf(
                """val key = if (body.isBlank()) "sendfail:${'$'}subject" else "sendfail:${'$'}subject:${'$'}body"""",
                "Intent(Intent.ACTION_VIEW, draftUri(subject, body), context, MainActivity::class.java)",
                "val noSubject = context.getString(R.string.message_no_subject)",
                "val line = MailNotificationText.sendFailureLine(content, subject, noSubject)",
            ),
            codeLinesNaming(body, "subject"),
        )
    }

    /**
     * Both callers, and the same failure for both: a scheduled send that gave up leaks the subject
     */
    @Test fun `the scheduled send reads the setting too, and passes what it read`() {
        val body = bodyOf(SCHEDULED, "doWork")
        assertEquals(
            "read from the settings repository, like every other notification path " +
                "(NewMailNotifier.options) — never a constant, never a default. GUARDED, and for " +
                "a reason of its own: this read sits INSIDE the catch, so a throw would escape " +
                "doWork() and skip both the banner and the deleteScheduledSend under it, leaving " +
                "the overdue row the comment above exists to clear",
            listOf("val content = runCatching { container.settingsRepository.notificationContent.first() }"),
            codeLinesNaming(body, "notificationContent"),
        )
        assertEquals(
            "…and to the same quietest position as the quick-reply path: the two must not drift",
            listOf(".getOrDefault(NotificationContent.NONE)"),
            codeLinesNaming(body, "getOrDefault(NotificationContent."),
        )
        assertEquals(
            "and handed to the banner as it was read",
            listOf("Notifications.notifySendFailed(applicationContext, row.subject, content)"),
            codeLinesNaming(body, "notifySendFailed("),
        )
        assertEquals(
            "these two are the only callers in the whole app, BY FILE and not by count: a third " +
                "written with a direct import (`import …Notifications.notifySendFailed`) names no " +
                "`Notifications.` at all, and a count would never have seen it",
            listOf("NotificationActionReceiver.kt", "Notifications.kt", "ScheduledSendWorker.kt"),
            File(root, "app/src/main/kotlin").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { codeLinesNaming(it.readText(), "notifySendFailed").isNotEmpty() }
                .map { it.name }.toList().sorted(),
        )
    }

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

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
        private const val PUSH_SOURCES = "app/src/main/kotlin/app/sterna/push"
        private const val RECEIVER_PATH = "$PUSH_SOURCES/NotificationActionReceiver.kt"
        private const val QUICK_REPLY_PATH = "$PUSH_SOURCES/QuickReply.kt"
        private const val NOTIFICATIONS_PATH = "$PUSH_SOURCES/Notifications.kt"
        private const val SCHEDULED_PATH = "app/src/main/kotlin/app/sterna/send/ScheduledSendWorker.kt"
        private const val WORKER_PATH = "$PUSH_SOURCES/BannerWriteWorker.kt"
        private const val COMPOSE_VM_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

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
        private val SCHEDULED: File by lazy { File(root, SCHEDULED_PATH) }
        private val WORKER: File by lazy { File(root, WORKER_PATH) }
        private val COMPOSE_VM: File by lazy { File(root, COMPOSE_VM_PATH) }
    }
}
