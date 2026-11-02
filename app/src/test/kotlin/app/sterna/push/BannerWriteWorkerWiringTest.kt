package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint: [BannerWriteWorker] needs WorkManager and the application container, and the
 * dependency that runs a worker in a JVM test is not in this build. What it decides is executed in
 * [BannerWriteTest]; pinned here is that the worker hands that decision its real inputs and obeys
 * its answers, and that a banner given back is given back in silence.
 */
class BannerWriteWorkerWiringTest {

    @Test fun `the worker counts its runs and obeys the outcome`() {
        val body = bodyOf("doWork")
        assertEquals(
            "the attempt is WorkManager's own count: a constant there is a write retried forever " +
                "or given up on at once",
            listOf("attempt = runAttemptCount,"),
            codeLinesNaming(body, "attempt ="),
        )
        assertEquals(
            "each outcome on its own line, so two swapped arms cannot pass as the same lines",
            listOf(
                "BannerWriteOutcome.Done -> Result.success()",
                "BannerWriteOutcome.Retry -> Result.retry()",
                "BannerWriteOutcome.GaveUp -> Result.failure()",
            ),
            codeLinesNaming(body, "BannerWriteOutcome."),
        )
        assertEquals(
            "giving up is what puts the banner back, for the account and message queued",
            listOf("runCatching { repostBanner(container, accountId, emailId) }"),
            codeLinesNaming(body, "repostBanner("),
        )
        assertEquals(
            "no credentials is decided on whether the account is still in the store, for the " +
                "account queued: `credentials` is null for a secret that did not read as well",
            listOf(
                "val store = container.accountStore",
                "val credentials = store.credentials(accountId)",
                "?: return leaveUnwritten(bannerUnwrittenWithoutCredentials(accountKnown = store.account(accountId) != null), container, accountId, emailId)",
            ),
            codeLinesNaming(body, "").filter { Regex("""\bstore\b""").containsMatchIn(it) },
        )
        assertEquals(
            "a queued Delete is decided again on the two cache reads, for the account and message " +
                "queued, before the write, and its answer obeyed",
            listOf(
                "if (write == BannerWrite.DELETE) {",
                "val reads = bannerDeleteReadsFromCache(repo, accountId, emailId)",
                "queuedDeleteUnwritten(reads.role, reads.hasTrash)?.let { return leaveUnwritten(it, container, accountId, emailId) }",
            ),
            codeLinesNaming(body, "").filter {
                it.startsWith("if (write") || "bannerDeleteReadsFromCache(" in it || "queuedDeleteUnwritten(" in it
            },
        )
        assertEquals(
            "past that decision the delete moves: `DoNothing` here finishes the run as done with " +
                "the message in its folder and its banner gone",
            listOf("act = BannerDeleteAct.MoveToTrash,"),
            codeLinesNaming(body, "act ="),
        )
    }

    @Test fun `a write left unwritten gives the banner back only when told to, and is never retried`() {
        val body = bodyOf("leaveUnwritten")
        assertEquals(
            listOf(
                "if (unwritten == BannerUnwritten.GiveBannerBack) runCatching { repostBanner(container, accountId, emailId) }",
                "return Result.success()",
            ),
            codeLinesNaming(body, "").filter { it.startsWith("if (") || it.startsWith("return") },
        )
    }

    @Test fun `each input is read under the key enqueue wrote it under`() {
        assertEquals(
            "a key swapped here reads the account id as the message id: the write goes nowhere " +
                "and the run ends as done",
            listOf(
                "val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.success()",
                "val emailId = inputData.getString(KEY_EMAIL_ID) ?: return Result.success()",
                "val write = BannerWrite.ofKey(inputData.getString(KEY_WRITE)) ?: return Result.success()",
            ),
            codeLinesNaming(bodyOf("doWork"), "inputData"),
        )
        assertEquals(
            listOf(".setInputData(workDataOf(KEY_ACCOUNT_ID to accountId, KEY_EMAIL_ID to emailId, KEY_WRITE to write.key))"),
            codeLinesNaming(bodyOf("enqueue"), "KEY_"),
        )
        assertEquals(
            "three keys, three distinct names",
            listOf(
                "private const val KEY_ACCOUNT_ID = \"accountId\"",
                "private const val KEY_EMAIL_ID = \"emailId\"",
                "private const val KEY_WRITE = \"write\"",
            ),
            codeLinesNaming(WORKER.readText(), "const val KEY_"),
        )
    }

    @Test fun `the banner given back is the cached message, silent, read under the setting`() {
        val body = bodyOf("repostBanner")
        assertEquals(
            "the cached row or nothing: the cache has lost it when the message or the account is gone",
            listOf("val email = container.mailRepository.cachedEmail(accountId, emailId) ?: return"),
            codeLinesNaming(body, "cachedEmail("),
        )
        assertEquals(
            "EVERY argument of the post, enumerated: `silent = false` rings the phone for a message " +
                "the user already dealt with; the content read above, not a constant, is #84; the " +
                "account's drawer label, not its login, is #193",
            listOf(
                "Notifications.notifyNewMail(",
                "applicationContext,",
                "email,",
                "accountId,",
                "silent = true,",
                "accountCount = container.accountStore.accounts().size,",
                "accountLabel = accountLabel,",
                "folderName = null,",
                "mailboxId = email.mailboxId,",
                "content = content,",
                "summarised = Notifications.summaryShownFor(live.size),",
                ")",
            ),
            callLines(body, "Notifications.notifyNewMail("),
        )
        assertEquals(
            "and the summary refreshed over it, silent as well, counting the banner just posted",
            listOf(
                "Notifications.updateGroupSummary(",
                "applicationContext, accountId, accountLabel, content,",
                "silent = true,",
                "expectedChildIds = live,",
                "cancelledChildIds = emptySet(),",
                ")",
            ),
            callLines(body, "Notifications.updateGroupSummary("),
        )
        assertEquals(
            listOf(
                "val content = runCatching { container.settingsRepository.notificationContent.first() }",
                ".getOrDefault(NotificationContent.NONE)",
                "val accountLabel = container.accountStore.account(accountId)?.label()",
                "val live = Notifications.liveChildIdsAfter(active, accountId, emptyList(), listOf(emailId))",
            ),
            codeLinesNaming(body, "").filter {
                it.startsWith("val content") || it.startsWith(".getOrDefault") ||
                    it.startsWith("val accountLabel") || it.startsWith("val live")
            },
        )
        assertEquals(
            "no other `silent` anywhere in the worker",
            listOf("silent = true,", "silent = true,"),
            codeLinesNaming(WORKER.readText(), "silent"),
        )
    }

    @Test fun `the write is queued once per tap, on a network, expedited where that is safe`() {
        val body = bodyOf("enqueue")
        assertEquals(
            listOf(
                ".setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())",
                ".setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)",
                ".setInputData(workDataOf(KEY_ACCOUNT_ID to accountId, KEY_EMAIL_ID to emailId, KEY_WRITE to write.key))",
                "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {",
                "builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)",
                "bannerWriteWorkName(accountId, emailId, write), ExistingWorkPolicy.KEEP, builder.build(),",
            ),
            codeLinesNaming(body, "").filter {
                it.startsWith(".set") || it.startsWith("if (") || it.startsWith("builder.") ||
                    it.startsWith("bannerWriteWorkName(")
            },
        )
        assertEquals(
            "the queueing is awaited: the receiver calls `pending.finish()` after it, and a " +
                "process frozen on a request still in memory loses the write of a banner already down",
            listOf(").await()"),
            codeLinesNaming(body, "await"),
        )
        assertEquals(
            "no getForegroundInfo, so nothing below Android 12 may ask for expedited work",
            emptyList<String>(),
            codeLinesNaming(WORKER.readText(), "getForegroundInfo"),
        )
    }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.isBlank() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The lines of the call opened by [opening], up to its closing paren alone on a line. */
    private fun callLines(body: String, opening: String): List<String> {
        val lines = codeLinesNaming(body, "")
        val start = lines.indexOf(opening)
        require(start >= 0) { "no `$opening` in this body" }
        val end = lines.subList(start, lines.size).indexOf(")")
        require(end >= 0) { "`$opening` is never closed on a line of its own" }
        return lines.subList(start, start + end + 1)
    }

    private fun bodyOf(name: String): String {
        val source = WORKER.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${WORKER.name} has no function named '$name', was it renamed?")
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
        error("Unbalanced braces in ${WORKER.name}.$name")
    }

    companion object {
        private const val WORKER_PATH = "app/src/main/kotlin/app/sterna/push/BannerWriteWorker.kt"

        private val WORKER: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, WORKER_PATH) }.firstOrNull { it.isFile }
                ?: error("cannot locate $WORKER_PATH from ${File("").absolutePath}")
        }
    }
}
