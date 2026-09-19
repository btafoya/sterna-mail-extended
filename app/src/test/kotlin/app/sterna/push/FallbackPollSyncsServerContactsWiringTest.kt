package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, the last resort: a `CoroutineWorker` is not instantiable on the JVM. What it pins is
 * where the address-book step sits in `MailFetchWorker`: once, under its own `runCatching`, in a loop
 * over every configured account whatever its notifications, and run right before each
 * `return Result.success()` of `doWork`, so after the mail and also when no account is polled. What
 * it reads and writes is executed in `MailAccountsSyncsServerContactsTest` and `ServerContactsRefreshTest`.
 */
class FallbackPollSyncsServerContactsWiringTest {

    private val code: List<String> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("cannot locate the checkout from ${File("").absolutePath}")
        File(root, "app/src/main/kotlin/app/sterna/push/MailFetchWorker.kt").readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }
    }

    private val step = "runCatching { container.mailRepository.accounts.syncServerContacts(credentials) }"
    private val stored = "val stored = container.accountStore.accounts()"
    private val every = "val readable = stored.mapNotNull { container.accountStore.credentials(it.id) }"
    private val leftOut = "addressBookLeftOutLine(stored, readable.map { it.id })?.let { Log.w(TAG, it) }"
    private val loop = "for (credentials in readable) {"
    private val call = "syncServerContactsOfEveryAccount(container)"

    @Test fun `every configured account gets the address-book step, in a loop of its own`() {
        assertEquals(1, code.count { "syncServerContacts(" in it })
        val at = code.indexOf(step)
        assertTrue("MailFetchWorker must call, as a whole line: $step", at >= 0)
        assertTrue("its failure is logged, not thrown", code[at + 1].startsWith(".onFailure {"))
        assertEquals(
            "the step's loop must walk every configured account, not only those with notifications",
            loop, code.subList(0, at).last { it.startsWith("for (") },
        )
        val head = code.subList(0, at)
        val loopAt = head.indexOf(loop)
        assertEquals(
            "the store is read once, and what the loop walks is every configured account whose " +
                "credentials read: a second read of `accounts()` would call an account added in between " +
                "unreadable",
            listOf(stored, every, leftOut, loop),
            head.subList(loopAt - 3, loopAt + 1),
        )
        assertEquals(1, code.count { "container.accountStore.accounts()" in it })
        assertEquals(0, code.count { "allCredentials()" in it && "container.accountStore" in it })
        assertEquals(
            "each outcome is logged at info, the release build strips Log.d and Log.v",
            ".onSuccess { outcome -> serverContactsLine(credentials.id, outcome)?.let { Log.i(TAG, it) } }",
            code[at + 2],
        )
        assertTrue(
            "the step must stand in the function `$call` names",
            code.subList(0, at).last { it.contains(" fun ") || it.startsWith("fun ") }
                .startsWith("private suspend fun syncServerContactsOfEveryAccount("),
        )
    }

    @Test fun `the step runs after the mail, on every way out of doWork`() {
        val doWork = code.indexOf("override suspend fun doWork(): Result {")
        val next = code.withIndex().first { it.index > doWork && " fun " in " ${it.value}" }.index
        val body = code.subList(doWork, next)
        val exits = body.withIndex().filter { it.value == "return Result.success()" }.map { it.index }
        assertEquals("doWork has two ways out: no account to poll, and after the loop", 2, exits.size)
        exits.forEach { exit ->
            assertEquals("the line before each `return Result.success()`", call, body[exit - 1])
        }
        assertEquals(2, body.count { call in it })
    }
}
