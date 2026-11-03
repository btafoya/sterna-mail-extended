package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, the last resort: neither a `CoroutineWorker` nor [PushService] is instantiable on the
 * JVM. What it pins is that every background sync downloads the inbox ahead (#157), a message that
 * arrived with the app closed being otherwise never readable offline, and does so after the fetch
 * that decides the notification, never before it. What the pass reads and caches is executed in
 * `DownloadAheadTest`.
 */
class DownloadAheadAfterTheFetchWiringTest {

    @Test fun `the fallback poll downloads ahead once, after every account's fetch and before the address book`() {
        val code = codeOf("MailFetchWorker.kt")
        val call = "container.mailRepository.downloadInboxesAhead(accounts)"
        assertEquals(listOf(call), code.filter { "downloadInboxesAhead(" in it })
        val at = code.indexOf(call)
        assertEquals(
            "after the per-account loop has closed, so no account's notification waits on another's download",
            listOf("}", call, "syncServerContactsOfEveryAccount(container)", "return Result.success()"),
            code.subList(at - 1, at + 3),
        )
        assertEquals(
            "and below the last FetchAndNotify of doWork",
            true,
            code.indexOfLast { "FetchAndNotify.run(" in it } < at,
        )
    }

    @Test fun `the push worker hands the download ahead to work of its own, only after its fetch came back`() {
        val code = codeOf("PushFetchWorker.kt")
        assertEquals(
            "the push worker never runs the pass itself: its unique name is kept while it runs, " +
                "and a push arriving during the pass would be dropped",
            emptyList<String>(),
            code.filter { "downloadInboxesAhead(" in it },
        )
        val at = code.indexOfFirst { it.startsWith("val fetched =") }
        assertEquals(
            listOf(
                "val fetched = runCatching { FetchAndNotify.run(applicationContext, credentials, resetBaselines = reset) }",
                "if (fetched.isSuccess) DownloadAheadWorker.enqueue(applicationContext, accountId)",
                "return fetched",
            ),
            code.subList(at, at + 3),
        )
    }

    @Test fun `the download-ahead work has a name of its own, keeps the one queued, and waits for a network`() {
        val code = codeOf("DownloadAheadWorker.kt")
        assertEquals(
            listOf("container.mailRepository.downloadInboxesAhead(listOf(credentials))"),
            code.filter { "downloadInboxesAhead(" in it },
        )
        assertEquals(
            listOf(".enqueueUniqueWork(\"download-ahead-\$accountId\", ExistingWorkPolicy.KEEP, request)"),
            code.filter { "enqueueUniqueWork(" in it },
        )
        assertEquals(
            listOf(".setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())"),
            code.filter { "setConstraints(" in it },
        )
        assertEquals(
            "not the push worker's name, or the two would share one queue again",
            listOf(".enqueueUniqueWork(\"push-fetch-\$accountId\", ExistingWorkPolicy.KEEP, builder.build())"),
            codeOf("PushFetchWorker.kt").filter { "enqueueUniqueWork(" in it },
        )
    }

    @Test fun `the push service launches it after the arm's baseline pass and after each wake's pass`() {
        val code = codeOf("PushService.kt")
        assertEquals(
            listOf(
                "scope.launch { repo.downloadInboxesAhead(group) }",
                "scope.launch { application.container.mailRepository.downloadInboxesAhead(listOf(credentials)) }",
            ),
            code.filter { "downloadInboxesAhead(" in it },
        )
        val arm = code.indexOf("scope.launch { repo.downloadInboxesAhead(group) }")
        assertEquals(
            "after the group's baseline loop has closed, and before the push connection is opened",
            listOf(".onFailure { Log.w(TAG, \"Baseline pass failed for account \${credentials.id}\", it) }", "}"),
            code.subList(arm - 2, arm),
        )
        assertEquals(true, arm < code.indexOf("val handle = repo.openAccountPush("))
    }

    private fun codeOf(file: String): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("cannot locate the checkout from ${File("").absolutePath}")
        return File(root, "app/src/main/kotlin/app/sterna/push/$file").readLines()
            .map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
    }
}
