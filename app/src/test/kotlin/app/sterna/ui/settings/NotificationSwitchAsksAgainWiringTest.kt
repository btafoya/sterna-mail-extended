package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint over `AccountsViewModel.setNotificationsEnabled`, one of the two fronts that reopen
 * the UnifiedPush distributor question after it was refused (#204); the other is asking for a
 * relay address, pinned in `RelayWiringTest`. It reads the file as text: it proves the call is
 * written and where it sits, never that it runs. `AccountsViewModel` is an
 * `AndroidViewModel` reading `application.container`, so nothing in this module can execute it,
 * and the write it makes is a device-wide preference needing a `Context`.
 *
 * Of the four holes in CONTRIBUTING, the third is why this file exists: a pinned line says nothing
 * about its position in a control structure. Hence the whole-body pin and [insideBlock], whose own
 * decision is executed below. What it does not cover: that the two switches on screen reach this
 * function at all, and that the preference written here survives to the next `ensureDistributor`.
 */
class NotificationSwitchAsksAgainWiringTest {

    @Test fun `the way back from a refused picker sits in the branch that switches push back on`() {
        val body = bodyLines()
        assertTrue(
            "setNotificationsEnabled must call allowDistributorChoice() INSIDE its 'if (enabled)' " +
                "branch. Outside it, the switch clears the refusal on its way down as well, and " +
                "the picker comes back for someone who just refused it; above " +
                "store.setNotificationsEnabled the write it belongs to has not happened yet. " +
                "Body:\n" + body.joinToString("\n"),
            insideBlock(body, "if (enabled) {", CLEAR_CALL),
        )
        assertTrue(
            "and it must come AFTER store.setNotificationsEnabled(id, enabled)",
            body.indexOf("store.setNotificationsEnabled(id, enabled)") < body.indexOf(CLEAR_CALL),
        )
    }

    @Test fun `the branch check answers no to a line that has left the branch`() {
        val body = listOf("{", "store.write()", "if (enabled) {", "inside()", "}", "outside()", "}")
        assertTrue(insideBlock(body, "if (enabled) {", "inside()"))
        assertFalse(insideBlock(body, "if (enabled) {", "outside()"))
        assertFalse(insideBlock(body, "if (enabled) {", "store.write()"))
    }

    @Test fun `setNotificationsEnabled is these lines, whole and in order`() {
        assertEquals(
            "AccountsViewModel.setNotificationsEnabled is no longer, line for line, what this " +
                "test was written against. An INSERTED line is what this pin exists to catch: an " +
                "early return above the clear, or a second branch around it, leaves every other " +
                "rule here green while a person who refused the picker BEFORE her account was " +
                "ever enrolled keeps no way to UnifiedPush at all — ensureRegistered writes no " +
                "record before ensureDistributor, so tearDownAfterSyncOff never fires for her.",
            listOf(
                "{",
                "store.setNotificationsEnabled(id, enabled)",
                "if (enabled) {",
                "NewMailNotifier.clear(getApplication(), id)",
                CLEAR_CALL,
                "}",
                "refresh()",
                "PushController.apply(getApplication(), userInitiated = true)",
                "}",
            ),
            bodyLines(),
        )
    }

    @Test fun `the clear is written on exactly two fronts in the whole app`() {
        val found = mainSources().flatMap { file ->
            file.readLines().map { it.trim() }
                .filter { "allowDistributorChoice" in it && !it.startsWith("//") }
                .map { "${file.name}: $it" }
        }.sorted()
        assertEquals(
            "allowDistributorChoice may be named on exactly these three lines of src/main: its " +
                "declaration, and the two taps that call it. A second call site on a replayed " +
                "level — PushController.apply's sweep, ensureDistributor, a worker — clears the " +
                "refusal on every pass, so the picker is raised again on the pass after it was " +
                "refused, for ever. Both taps here are fronts a person performs once: this " +
                "switch, and 'Get a relay address', which is a request for push in so many words " +
                "and used to sit behind the refusal saying nothing but 'Waiting for an address'. " +
                "Lines found:\n" + found.joinToString("\n"),
            listOf(
                "AccountsViewModel.kt: $CLEAR_CALL",
                "UnifiedPushManager.kt: allowDistributorChoice()",
                "UnifiedPushManager.kt: fun allowDistributorChoice() {",
            ),
            found,
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /** Trimmed body of `setNotificationsEnabled`, braces included, comment-only lines dropped. */
    private fun bodyLines(): List<String> {
        val source = ACCOUNTS_VM.readText()
        val start = source.indexOf("fun setNotificationsEnabled(")
        check(start >= 0) { "no setNotificationsEnabled in AccountsViewModel.kt — renamed?" }
        var depth = 0
        var i = source.indexOf('{', start)
        val open = i
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(open, i).lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") }
    }

    private fun mainSources(): List<File> =
        File(root, MAIN).walkTopDown().filter { it.extension == "kt" }.toList()

    private companion object {
        const val ACCOUNTS_VM_PATH = "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt"
        const val MAIN = "app/src/main/kotlin"
        const val CLEAR_CALL =
            "getApplication<Application>().container.unifiedPushManager.allowDistributorChoice()"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, ACCOUNTS_VM_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }

        val ACCOUNTS_VM: File by lazy { File(root, ACCOUNTS_VM_PATH) }
    }
}

/** Is [needle] nested inside the block opened by [opener], both whole trimmed lines? */
private fun insideBlock(lines: List<String>, opener: String, needle: String): Boolean {
    var depth = 0
    var open = false
    for (line in lines) {
        if (!open) {
            if (line == opener) open = true else continue
        }
        depth += line.count { it == '{' } - line.count { it == '}' }
        if (line == needle) return depth > 0
        if (depth <= 0) return false
    }
    return false
}
