package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads `ComposeViewModel.kt`, `ComposeScreen.kt` and
 * `SternaApp.kt` as text. What Edit as new (#254) lays in the fields is executed by
 * [EditAsNewFieldsTest] and the route by `ComposeEditAsNewRouteTest`; what is pinned here is what no
 * JVM test can run, that the branch of `prepare` arms nothing a later send, save or discard reads to
 * find a message to destroy or to thread onto.
 *
 * Not covered: a helper the branch would call and that armed one of those fields itself. The rule on
 * the whole branch below is what stands in for it, any new call there has to be added by hand.
 */
class EditAsNewWiringTest {

    @Test fun `the branch names none of the fields that license a destruction or a threading`() {
        val branch = editAsNewBranch()
        val named = FORBIDDEN.filter { Regex("(?<![A-Za-z0-9_])$it").containsMatchIn(branch) }
        assertEquals(
            "the Edit as new branch must not so much as name these: `editingDraft…` and " +
                "`replacedServerDraftId` are what a send, a re-save, an emptied composer and the " +
                "Delete button read to find the message to destroy, and here that message is the " +
                "original; `inReplyTo` and `references` thread the new message onto it; " +
                "`draftId` and `localDraftReplacing` are the draft reopen's own. Branch was:\n$branch",
            emptyList<String>(),
            named,
        )
    }

    @Test fun `the branch, whole`() {
        assertEquals(
            "every statement of the Edit as new branch, in order. The attachments are carried " +
                "before the prefill, on purpose: the editor is drawn the moment the prefill is " +
                "applied, and a message sent from there would leave without its files. And the " +
                "carry's answer is read: a file that would not come across is said, as a forward " +
                "says it, or the composer opens looking complete and the send leaves amputated.",
            listOf(
                "_draftLoading.value = true",
                "viewModelScope.launch {",
                "try {",
                "val credentials = credentials() ?: run {",
                "_draftLoadFailed.value = draftLoadNoticeFor(offline = false)",
                "return@launch",
                "}",
                "val message = repo.fetchEmail(credentials, editAsNewId)",
                "if (!mayEditAsNew(message, reader = null)) {",
                "_draftLoadFailed.value = draftLoadNoticeFor(offline = false)",
                "return@launch",
                "}",
                "preselectReceivingIdentity(credentials.id, message)",
                "if (!carryDraftAttachments(credentials, message)) _notices.tryEmit(R.string.compose_forward_attachment_dropped)",
                "_prefill.value = editAsNewFieldsOf(message)",
                "} catch (t: Throwable) {",
                "val app = getApplication<Application>()",
                "_draftLoadFailed.value = draftLoadNoticeFor(isOfflineFailure(t, online = hasUsableNetwork(app)))",
                "} finally {",
                "_draftLoading.value = false",
                "}",
                "}",
                "return",
            ),
            editAsNewBranch().lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /**
     * The reader's menu withholds the entry on an encrypted message, but a route is replayed after a
     * process death, and the menu offers it on some messages it should not. This is the check that
     * holds then: the composer reads the message as the server holds it, so what it would be filled
     * with is the ciphertext, an armour in the body or the encrypted part as a file.
     */
    @Test fun `an encrypted message is refused before a single file is carried or a field is filled`() {
        val lines = editAsNewBranch().lines().map { it.trim() }
        val read = lines.indexOf("val message = repo.fetchEmail(credentials, editAsNewId)")
        val guard = lines.indexOf("if (!mayEditAsNew(message, reader = null)) {")
        val carry = lines.indexOfFirst { "carryDraftAttachments(" in it }
        val prefill = lines.indexOfFirst { "_prefill" in it }
        val identity = lines.indexOfFirst { "preselectReceivingIdentity(" in it }
        assertTrue("the Edit as new branch no longer refuses an encrypted message. Lines were:\n$lines", guard >= 0)
        assertEquals("the refusal must be the first thing done with the message read", read + 1, guard)
        assertEquals(
            "the refusal must show the existing notice and leave, doing nothing else",
            listOf("_draftLoadFailed.value = draftLoadNoticeFor(offline = false)", "return@launch", "}"),
            lines.subList(guard + 1, guard + 4),
        )
        assertTrue("the refusal must come before the attachments are carried", guard < carry)
        assertTrue("the refusal must come before the prefill", guard < prefill)
        assertTrue("the refusal must come before the sender is chosen", guard < identity)
    }

    @Test fun `the branch asks for no decrypted text`() {
        val branch = editAsNewBranch()
        assertEquals(
            "the Edit as new branch must read the message as the server holds it, and nothing a " +
                "decryption left in memory. Branch was:\n$branch",
            emptyList<String>(),
            listOf("decryptedOriginalToQuote", "decryptedCache", "quoteSource", "decryptMessage", "openMessage").filter { it in branch },
        )
    }

    @Test fun `a short carry is said by the forward's own notice, which the forward keeps`() {
        assertEquals(
            "ComposeViewModel.kt must emit compose_forward_attachment_dropped on exactly these two " +
                "lines: the forward's own count, and the Edit as new branch reading the carry's answer",
            listOf(
                "if (!carryDraftAttachments(credentials, message)) _notices.tryEmit(R.string.compose_forward_attachment_dropped)",
                "if (carried < fileParts.size) _notices.tryEmit(R.string.compose_forward_attachment_dropped)",
            ),
            codeLines(COMPOSE_VIEW_MODEL).map { it.trim() }.filter { "compose_forward_attachment_dropped" in it },
        )
    }

    @Test fun `the wait is raised before the coroutine and lowered from its finally`() {
        val branch = editAsNewBranch()
        val flag = branch.indexOf("_draftLoading.value = true")
        val launch = branch.indexOf("viewModelScope.launch")
        assertTrue("the Edit as new branch no longer raises _draftLoading. Branch was:\n$branch", flag >= 0)
        assertTrue(
            "_draftLoading.value = true must come before viewModelScope.launch: set inside, an " +
                "empty editable composer is on screen for the frames the first suspension delays. " +
                "Branch was:\n$branch",
            flag < launch,
        )
        assertEquals(
            "the finally must lower _draftLoading, or a read that failed waits for good",
            listOf("_draftLoading.value = false"),
            blockAfter(branch, "} finally {").lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /**
     * After the two draft arms, which own `draftId`, and before the three openings of a message
     * with no original behind it: below them, the branch is never reached and the composer opens blank.
     */
    @Test fun `the branch is keyed on its own argument and sits between the draft arms and the blank openings`() {
        val body = prepareBody()
        val at = body.indexOf("if (editAsNewId != null) {")
        assertTrue("prepare() no longer opens the Edit as new branch on `editAsNewId != null`", at >= 0)
        val draftArms = body.indexOf("DraftOpenRoute.NONE -> Unit")
        val linkPrefill = body.indexOf("if (!to.isNullOrBlank()")
        assertTrue("prepare() no longer ends the draft arms on `DraftOpenRoute.NONE -> Unit`", draftArms >= 0)
        assertTrue("prepare() no longer opens the link prefill on `if (!to.isNullOrBlank()`", linkPrefill >= 0)
        assertTrue("the Edit as new branch must come after the draft arms", draftArms < at)
        assertTrue("the Edit as new branch must come before the link prefill and the blank message", at < linkPrefill)
    }

    @Test fun `the id travels from the route to prepare under its own name at every hop`() {
        val app = codeLines(STERNA_APP).map { it.trim() }
        assertEquals(
            "the composer's destination must declare `editAsNew` in its pattern, as an argument, " +
                "and read it into `editAsNewId`. Read into `replyTo` or `draftId`, the same id " +
                "opens a threaded reply or arms the original for destruction.",
            listOf(
                "\"&editAsNew={editAsNew}\" +",
                "navArgument(\"editAsNew\") { type = NavType.StringType; nullable = true; defaultValue = null },",
                "editAsNewId = entry.arguments?.getString(\"editAsNew\")?.let { Uri.decode(it) }?.ifBlank { null },",
            ),
            app.filter { "editAsNew" in it },
        )
        val screen = codeLines(COMPOSE_SCREEN).map { it.trim() }
        assertEquals(
            "ComposeScreen must take `editAsNewId` and hand it to prepare() by name, and to " +
                "nothing else: the title, the Delete button and the caret all key on `draftId`, " +
                "and none of them may be told this is a draft.",
            listOf("editAsNewId: String? = null,", "editAsNewId = editAsNewId,"),
            screen.filter { "editAsNew" in it },
        )
        assertEquals(
            "prepare() must take `editAsNewId` and read it in its own branch only",
            listOf(
                "editAsNewId: String? = null,",
                "if (editAsNewId != null) {",
                "val message = repo.fetchEmail(credentials, editAsNewId)",
                "_prefill.value = editAsNewFieldsOf(message)",
            ),
            codeLines(COMPOSE_VIEW_MODEL).map { it.trim() }.filter { "editAsNew" in it },
        )
    }

    // -- reading the source -----------------------------------------------------------------------

    private fun prepareBody(): String {
        val code = codeLines(COMPOSE_VIEW_MODEL).joinToString("\n")
        val at = code.indexOf("fun prepare(")
        check(at >= 0) { "ComposeViewModel.kt declares no 'prepare(' — did it get renamed?" }
        return blockAfter(code.substring(at), "{")
    }

    private fun editAsNewBranch(): String = blockAfter(prepareBody(), "if (editAsNewId != null) {")

    /** What follows the first [opener] in [code], up to the brace that closes it. */
    private fun blockAfter(code: String, opener: String): String {
        val at = code.indexOf(opener)
        check(at >= 0) { "the source no longer contains `$opener` — did it move or get renamed?" }
        val start = at + opener.length
        var depth = 1
        var i = start
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return code.substring(start, (i - 1).coerceAtLeast(start))
    }

    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
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

    companion object {
        private const val VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        private const val SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        private const val STERNA_APP_PATH = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"

        /** Matched at an identifier's start: `editingDraft` covers the id, its account, its verdict, its numbering and the row. */
        private val FORBIDDEN = listOf(
            "editingDraft",
            "_editingDraft",
            "_savedDraftBehind",
            "replacedServerDraftId",
            "inReplyTo",
            "references",
            "draftId",
            "localDraftReplacing",
        )

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, VIEW_MODEL_PATH) }
        private val COMPOSE_SCREEN: File by lazy { File(root, SCREEN_PATH) }
        private val STERNA_APP: File by lazy { File(root, STERNA_APP_PATH) }
    }
}
