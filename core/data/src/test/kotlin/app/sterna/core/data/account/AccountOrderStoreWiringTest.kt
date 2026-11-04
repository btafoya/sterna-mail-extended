package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The account order from [AccountStore] down to the pure functions executed in [AccountOrderTest].
 * That the store calls them on the list it reads under its lock, and writes only when they answer
 * a new list, is read off the source: this module can build no store.
 */
class AccountOrderStoreWiringTest {

    @Test fun `a one-place move reads and writes under one lock, through the pure function`() {
        assertEquals(
            "AccountStore.moveAccount changed shape. It must hand withAccountMoved the list it just " +
                "read, under @Synchronized, and save only a new list, through saveAccountOrder: a list " +
                "computed by the caller races the background reconcile and loses the first of two quick taps.",
            listOf(
                "@Synchronized",
                "fun moveAccount(id: String, up: Boolean) {",
                "val read = accounts()",
                "val moved = withAccountMoved(read, id, up)",
                "if (moved !== read) saveAccountOrder(moved)",
                "}",
            ),
            block("fun moveAccount(", count = 5, before = 1),
        )
    }

    @Test fun `a drop reads and writes under one lock, through the pure function`() {
        assertEquals(
            "AccountStore.moveAccountTo changed shape. It must hand withAccountDropped the list it " +
                "just read, under @Synchronized, and save only a new list, through saveAccountOrder.",
            listOf(
                "@Synchronized",
                "fun moveAccountTo(id: String, rank: Int) {",
                "val read = accounts()",
                "val moved = withAccountDropped(read, id, rank)",
                "if (moved !== read) saveAccountOrder(moved)",
                "}",
            ),
            block("fun moveAccountTo(", count = 5, before = 1),
        )
    }

    @Test fun `a move pins the account shown before it writes the new order`() {
        assertEquals(
            "AccountStore.saveAccountOrder changed shape. currentId() falls back to the first account " +
                "when KEY_CURRENT is absent or stale: it must be read and pinned before saveAccounts, " +
                "or moving an account to the top switches the account the inbox shows.",
            listOf(
                "private fun saveAccountOrder(moved: List<StoredAccount>) {",
                "val shown = currentId()",
                "if (prefs.getString(KEY_CURRENT, null) != shown) prefs.edit().putString(KEY_CURRENT, shown).apply()",
                "saveAccounts(moved)",
                "}",
            ),
            block("private fun saveAccountOrder(", count = 5),
        )
    }

    @Test fun `the accounts screen rows are drawn from the blocks the moves count in`() {
        assertEquals(
            "StoredAccount.accountsScreenRows no longer derives from accountBlocks: the screen and " +
                "the moves could disagree on what a rank is.",
            listOf("accountBlocks(all).flatMap { block ->"),
            block("accountBlocks(all)", path = STORED_ACCOUNT),
        )
    }

    private fun block(prefix: String, count: Int = 1, before: Int = 0, path: String = ACCOUNT_STORE): List<String> {
        val code = codeLines(path)
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(maxOf(start - before, 0), minOf(start - before + count + before, code.size))
    }

    private fun codeLines(path: String): List<String> {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        return java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private companion object {
        const val ACCOUNT_STORE = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
        const val STORED_ACCOUNT = "core/data/src/main/kotlin/app/sterna/core/data/account/StoredAccount.kt"
    }
}
