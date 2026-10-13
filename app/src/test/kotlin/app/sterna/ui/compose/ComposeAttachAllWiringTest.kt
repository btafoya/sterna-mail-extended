package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint for the multi-file pick, not a behaviour test: `ComposeScreen.kt` and
 * `ComposeViewModel.kt` are read as text, because neither the launcher nor the ViewModel runs in a
 * JVM test. The sequence itself is executed by `ComposeAttachAllTest`. Holes: a pinned line says
 * nothing about when it runs; `attachAll` is cut at the next member, so a helper it calls is not
 * read; and nothing here proves the system picker honours the multiple-selection extra.
 */
class ComposeAttachAllWiringTest {

    @Test fun `the picker asks for several files and hands the whole list over`() {
        val screen = lines(SCREEN)
        assertTrue(
            "the picker must be declared with exactly this line:\n  $PICKER",
            PICKER in screen,
        )
        assertTrue("the picked list must go to attachAll:\n  $PICKED", PICKED in screen)
        assertEquals(
            "a single-file contract left anywhere in the composer. Lines found:",
            emptyList<String>(),
            screen.filter { "ActivityResultContracts.GetContent()" in it },
        )
    }

    @Test fun `files shared into the app go through attachAll, not one launch each`() {
        val screen = lines(SCREEN)
        assertTrue("the share handoff must call:\n  $SHARED", SHARED in screen)
        assertEquals(
            "a forEach over the shared files starts one coroutine per file. Lines found:",
            emptyList<String>(),
            screen.filter { "shared.forEach" in it },
        )
    }

    @Test fun `attachAll runs the pick in one coroutine through attachInSequence`() {
        val body = memberBody("fun attachAll(")
        assertEquals(
            "attachAll must launch exactly once; a launch per file breaks the order. Lines found:",
            listOf("viewModelScope.launch {"),
            body.filter { "launch" in it },
        )
        assertEquals(
            "attachAll must hand its whole list to attachInSequence. Lines found:",
            listOf("attachInSequence("),
            body.filter { "attachInSequence" in it },
        )
        assertTrue("the list handed over must be the uris themselves:\n$body", "uris," in body)
        assertTrue(
            "each file must be read by the helper attach shares, staged on the phone when the " +
                "composer was not settled in time:\n$body",
            "attachOne = { uri -> attachOneFile(uri, stageLocally = !settled) }," in body,
        )
        assertEquals(
            "no loop over the uris of its own: the sequence is attachInSequence's. Lines found:",
            emptyList<String>(),
            body.filter { "uris" in it && ("forEach" in it || it.startsWith("for (")) },
        )
    }

    /**
     * After a process death the picker's result arrives before `prepare` (see `ComposerBinding`):
     * the whole pick waits for the binding, as `attachCapture` does, and only under the lock that
     * keeps two picks from interleaving on the one status line.
     */
    @Test fun `attachAll waits its turn, then the binding, then runs the pick`() {
        val body = memberBody("fun attachAll(")
        val order = listOf(
            "attachAllLock.withLock {",
            "val settled = composerBinding.awaitSettled(CAPTURE_BINDING_WAIT_MS)",
            "attachInSequence(",
        )
        val at = order.map { line -> body.indexOf(line) }
        assertTrue("attachAll must carry, in this order:\n${order.joinToString("\n")}\nfound:\n$body",
            at.all { it >= 0 } && at == at.sorted())
        val all = File(repoRoot(), VIEW_MODEL).readLines().map { it.trim() }
        assertEquals(
            "one lock, declared once, a Mutex. Lines found:",
            listOf("private val attachAllLock = Mutex()"),
            all.filter { "attachAllLock =" in it },
        )
    }

    @Test fun `attachAll words one failure like attach and several as a count`() {
        val body = memberBody("fun attachAll(")
        assertEquals(
            "Lines found:",
            listOf("app.getString(composedAttachFailure(f.error, online = f.online), f.error.message ?: \"error\")"),
            body.filter { "composedAttachFailure(" in it },
        )
        assertEquals(
            "the connection is asked once, at each failure, through attachInSequence. Lines found:",
            listOf("online = { hasUsableNetwork(app) },"),
            body.filter { "hasUsableNetwork" in it },
        )
        assertEquals("Lines found:", emptyList<String>(), body.filter { "isOfflineFailure(" in it })
        assertTrue(
            "several failures must be counted with the plural, failed count first:\n$body",
            "app.resources.getQuantityString(R.plurals.status_attach_some_failed, failed, failed, word.total)" in body,
        )
    }

    @Test fun `the photo still goes through attach`() {
        val body = memberBody("fun attachCapture(")
        assertEquals(
            "attachCapture must stay on attach, whose finally runs afterRead. Lines found:",
            listOf("attach(uri, afterRead, stageLocally = !settled)"),
            body.filter { "attach" in it && "(" in it && "attachCapture" !in it },
        )
    }

    @Test fun `an attached file marks the draft changed, after it is listed`() {
        val body = memberBody("private suspend fun attachOneFile(")
        val listed = body.indexOf("_attachments.value = _attachments.value + part")
        val touched = body.indexOf("_attachmentsTouched.value = true")
        assertTrue("attachOneFile must list the part:\n$body", listed >= 0)
        assertTrue(
            "attachOneFile must arm _attachmentsTouched after listing the part, or leaving the " +
                "composer discards a draft whose only change is its attachments:\n$body",
            touched > listed,
        )
    }

    /**
     * Read item by item: the parity test compares the set of arguments a name carries across all its
     * items, so one item that lost `%2$d` or said `%1$d` twice would pass it.
     */
    @Test fun `every item of the count plural carries each argument exactly once`() {
        val offenders = mutableListOf<String>()
        for (dir in LANGUAGES) {
            val text = File(repoRoot(), "app/src/main/res/$dir/strings.xml").readText()
            val block = Regex("""<plurals name="status_attach_some_failed">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)
            if (block == null) { offenders += "$dir: no status_attach_some_failed"; continue }
            val items = Regex("""<item quantity="(\w+)">(.*?)</item>""").findAll(block).map { it.groupValues[1] to it.groupValues[2] }.toList()
            if (items.none { it.first == "other" }) offenders += "$dir: no other item"
            for ((quantity, item) in items) {
                val first = Regex(Regex.escape("%1\$d")).findAll(item).count()
                val second = Regex(Regex.escape("%2\$d")).findAll(item).count()
                if (first != 1 || second != 1) offenders += "$dir/$quantity: $item"
            }
        }
        assertEquals("each item must say the failed count once and the pick's size once:", emptyList<String>(), offenders)
    }

    @Test fun `the plural exists in the default strings`() {
        val strings = File(repoRoot(), "app/src/main/res/values/strings.xml").readText()
        assertTrue("<plurals name=\"status_attach_some_failed\">" in strings)
    }

    private fun memberBody(signature: String): List<String> {
        val all = File(repoRoot(), VIEW_MODEL).readLines()
        val start = all.indexOfFirst { it.trim().startsWith(signature) }
        assertTrue("ComposeViewModel must declare $signature", start >= 0)
        val rest = all.drop(start + 1)
        val end = rest.indexOfFirst {
            it.startsWith("    fun ") || it.startsWith("    private ") || it.startsWith("    /**")
        }.let { if (it < 0) rest.size else it }
        return rest.take(end).map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }
    }

    private fun lines(path: String) = File(repoRoot(), path).readLines().map { it.trim() }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, SCREEN).isFile }
            ?: error("cannot locate $SCREEN from ${File("").absolutePath}")

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        const val PICKER =
            "val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->"
        const val PICKED = "viewModel.attachAll(uris)"
        const val SHARED = "viewModel.attachAll(shared)"
        val LANGUAGES = listOf(
            "values", "values-de", "values-es", "values-fr", "values-hu", "values-it",
            "values-nl", "values-pl", "values-pt", "values-ru", "values-zh-rCN",
        )
    }
}
