package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint. A linked JMAP account the server stopped granting is removed by
 * `MailAccounts.reconcileLinkedAccounts`, with no sign-out and no screen involved, and a revocation
 * clears the whole search history: a search carries no account, so which ones were about the
 * account that left cannot be told. A duplicate record pruned beside its twin does not clear it.
 * Both are executed by `MailAccountsPruneFollowsTheStoreTest`; what is read here is the plug from
 * `DataFactory` down to `MailAccounts`, and that the clear runs once per reconcile, not per id.
 */
class PrunedAccountForgetsSearchesWiringTest {

    @Test fun `a revocation clears the search history once, after the per-account purges`() {
        val body = functionBody(MAIL_ACCOUNTS, "internal suspend fun reconcileLinkedAccounts(")
        val before = body.indexOf("val linkedBefore = accountStore.linkedAccounts(loginId)")
        val store = body.indexOfFirst { it.startsWith("val pruned = runCatching { accountStore.reconcileLinkedAccounts(") }
        val guard = body.indexOf("if (revokedLinkedIds(linkedBefore, discovered).any { it in pruned }) {")
        assertTrue(
            "the linked records must be read before the store prunes them, and the revocation " +
                "judged over them after. Body was:\n" + body.joinToString("\n"),
            before in 0 until store && store < guard,
        )
        assertEquals(
            listOf("bgScope.launch { runCatching { searchHistory?.clear() } }"),
            body.filter { "searchHistory" in it },
        )
        assertEquals("bgScope.launch { runCatching { searchHistory?.clear() } }", body[guard + 1])
    }

    @Test fun `the history is handed down to MailAccounts, not left at its default`() {
        assertEquals(
            listOf("searchHistory = searchHistory,"),
            constructorCall(MAIL_REPOSITORY, "val accounts: MailAccounts = MailAccounts(").filter { "searchHistory" in it },
        )
        assertEquals(
            "DataFactory must hand the history to MailRepository: a null there silently turns the clear off",
            listOf("searchHistory = searchHistory,"),
            codeLines(DATA_FACTORY).filter { it.startsWith("searchHistory =") },
        )
    }

    private fun constructorCall(file: File, opening: String): List<String> {
        val code = codeLines(file)
        val start = code.indexOfFirst { it == opening }
        assertTrue("$opening not found in ${file.name}", start >= 0)
        val end = (start until code.size).first { code[it] == ")" }
        return code.subList(start, end + 1)
    }

    private fun functionBody(file: File, signature: String): List<String> {
        val code = codeLines(file)
        val start = code.indexOfFirst { it.startsWith(signature) }
        assertTrue("$signature not found in ${file.name}", start >= 0)
        var depth = 0
        var i = start
        var opened = false
        val out = mutableListOf<String>()
        while (i < code.size) {
            val line = code[i]
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            out += line
            i++
            if (opened && depth <= 0) break
        }
        return out
    }

    private fun codeLines(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private companion object {
        const val DATA = "core/data/src/main/kotlin/app/sterna/core/data"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, DATA).isDirectory }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
        val MAIL_ACCOUNTS: File by lazy { File(root, "$DATA/mail/MailAccounts.kt") }
        val MAIL_REPOSITORY: File by lazy { File(root, "$DATA/mail/MailRepository.kt") }
        val DATA_FACTORY: File by lazy { File(root, "$DATA/DataFactory.kt") }
    }
}
