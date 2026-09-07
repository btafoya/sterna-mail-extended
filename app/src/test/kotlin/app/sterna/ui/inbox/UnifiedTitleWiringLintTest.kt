package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `InboxScreen.kt` as text and proves nothing about what
 * the app draws. There is no Compose test harness in this repo and `InboxScreen` is not instantiable
 * on the JVM, so the wiring of the top bar's title is out of reach of any executing test; this file
 * is the last resort the house pattern allows, and it pins ARGUMENTS, not just the names of the
 * functions called.
 *
 * What it guards, all three of them one-word mutations that compile and leave the rest green:
 *  - the title argument — with the unified arm gone, the title falls back to
 *    `mailboxDisplayName(null, "All inboxes")`, i.e. the internal [UNIFIED_LABEL] that
 *    `SelectionMemory.kt` puts in the top-level [Meta] (it used to live in `InboxViewModel`'s
 *    private companion, and both moved out so a JVM test could run the decision), shown raw in all
 *    nine locales while the drawer entry that leads there is translated;
 *  - the subtitle guard — in the unified view `accountName` is ALSO the internal label, so dropping
 *    `!ui.unified` prints the title twice, one under the other;
 *  - the role arm — swapping `mailboxDisplayName(selectedRole, ui.mailboxName)` for the unified
 *    string in EVERY case would render Sent / Trash / Drafts in raw English instead.
 *
 * Every rule compares a WHOLE unit — the whole argument, the whole condition, the whole call —
 * never a fragment. A `contains` on a piece is blind to any mutation that LENGTHENS it:
 * `if (ui.unified)` is contained in `if (ui.unified && false)`, and
 * `mailboxDisplayName(selectedRole, …)` contains `mailboxDisplayName(`. The price is that a reformat
 * reddens this test; that direction of error is loud and one line to fix.
 *
 * Every rule is anchored on the `MediumTopAppBar` block, never on the file: `InboxScreen.kt` holds a
 * second bar (the search field) that calls `mailboxDisplayName` too, and a rule read over the whole
 * file could be satisfied by the wrong one.
 */
class UnifiedTitleWiringLintTest {

    @Test
    fun `the title reads the translated string in the unified view`() {
        val block = titleBlock()
        val texts = Regex("""\bText\s*\(""").findAll(block).toList()
        assertEquals(
            "The MediumTopAppBar title Column is expected to hold exactly two Text calls — the " +
                "title, then the account subtitle — so 'the first one' names the title without " +
                "ambiguity. Found ${texts.size} in:\n$block",
            2, texts.size,
        )
        assertEquals(
            "The title of the unified view must come from the translated resource, not from the " +
                "name carried in the UI state: in the unified view no mailbox is selected, so " +
                "mailboxDisplayName has no role to key on and hands back the raw name, which is the " +
                "internal literal UNIFIED_LABEL = \"All inboxes\" — English in all nine locales, " +
                "under a drawer entry that is translated. It is resolved HERE and not in the " +
                "ViewModel because a ViewModel outlives a configuration change and would keep the " +
                "old language after a hot locale switch. Compared as the whole argument, because " +
                "'if (ui.unified)' is contained in 'if (ui.unified && false)'. The unread view is " +
                "the same problem read from the other end: it selects no mailbox either, so without " +
                "an arm of its own the title falls through to mailboxDisplayName and names THE LAST " +
                "FOLDER VISITED over a list that spans the account. Title block was:" +
                "\n$block",
            EXPECTED_TITLE_ARGUMENT,
            firstArgumentAt(block, texts.first().range.first),
        )
    }

    @Test
    fun `the account subtitle stays hidden in the unified view`() {
        val block = titleBlock()
        val conditions = ifConditions(block)
        assertEquals(
            "The guard on the account subtitle must stay exactly as it is. In the unified view " +
                "accountName holds the SAME internal label as the mailbox name, so a guard that no " +
                "longer excludes the unified view prints the title twice, one line under the other. " +
                "Compared as the whole condition — 'if (!ui.unified && ui.accountName.isNotBlank())' " +
                "is contained in a longer condition that adds a third term, and a null below means " +
                "the guard was dropped altogether. The unread view KEEPS the line, which is why no " +
                "third term belongs here: it is a view of one account, and that line is the only " +
                "thing on screen saying which. Conditions found in the title block: " +
                "$conditions\nTitle block was:\n$block",
            EXPECTED_SUBTITLE_CONDITION,
            conditions.singleOrNull { it.contains("accountName") },
        )
    }

    @Test
    fun `every other folder is still named by its role`() {
        val block = titleBlock()
        val calls = Regex("""\bmailboxDisplayName\s*\(""").findAll(block).toList()
        assertEquals(
            "The MediumTopAppBar title is expected to call mailboxDisplayName exactly once — the " +
                "arm taken when a mailbox IS selected. Zero means the fix for the unified title was " +
                "applied to every case, which renders Sent / Trash / Drafts in raw English. Found " +
                "${calls.size} in:\n$block",
            1, calls.size,
        )
        assertEquals(
            "The non-unified arm must keep naming the folder by its role AND pass the state's own " +
                "mailbox name as the fallback. Compared as the whole call, arguments included, " +
                "because 'mailboxDisplayName(' contains the function's name and would wave through " +
                "a call given the wrong role or a hardcoded name. Title block was:\n$block",
            EXPECTED_ROLE_CALL,
            callAt(block, calls.single().range.first),
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /**
     * The `MediumTopAppBar` call up to the brace that balances its first `{`, i.e. its `title`
     * lambda: comments removed and whitespace collapsed.
     *
     * Fails loudly when the call is not found, so a rename or a swap to another bar reddens these
     * rules rather than satisfying them against an empty string. Matched on the call — `Name(` —
     * because the import line names it too.
     */
    private fun titleBlock(): String {
        val text = codeText(INBOX_SCREEN)
        val hits = Regex("""\bMediumTopAppBar\s*\(""").findAll(text).toList()
        check(hits.size == 1) {
            "InboxScreen.kt is expected to call MediumTopAppBar exactly once — the inbox's own top " +
                "bar, the one that carries the title this test guards. Found ${hits.size}: was it " +
                "renamed, or was a second one added that would have to be told apart here?"
        }
        return balancedFrom(text, hits.single().range.first)
    }

    /**
     * The first argument of the call whose `(` comes at or after [from]: from that `(` to the `,`
     * that closes the argument at depth 1, or to the `)` of a one-argument call.
     *
     * Depth counts brackets of all three kinds, so a conditional written with braces and a nested
     * call are read as one argument.
     */
    private fun firstArgumentAt(text: String, from: Int): String {
        val open = text.indexOf('(', from)
        check(open >= 0) { "no argument list opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(', '{', '[' -> depth++
                ')', ']' -> depth--
                '}' -> depth--
            }
            if (depth == 0) return text.substring(open + 1, i).trim()
            if (depth == 1 && text[i] == ',') return text.substring(open + 1, i).trim()
            i++
        }
        error("the argument list opened at offset $open never closes")
    }

    /** The whole call starting at [from], up to the `)` that balances its first `(`. */
    private fun callAt(text: String, from: Int): String {
        val open = text.indexOf('(', from)
        check(open >= 0) { "no argument list opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /** Every `if` condition in [block], each one whole, in source order. */
    private fun ifConditions(block: String): List<String> =
        Regex("""\bif\s*\(""").findAll(block).map { hit ->
            val call = callAt(block, hit.range.first)
            call.substring(call.indexOf('(') + 1, call.length - 1).trim()
        }.toList()

    /** [text] from [from] up to the `}` that balances the first `{` at or after it. */
    private fun balancedFrom(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) { "no block opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /**
     * [file] as ONE line of code: every comment taken out, every run of whitespace collapsed to a
     * single space — so a call the formatter spreads over eight lines reads the same as one written
     * on a line, and the rules do not care which the formatter chose.
     *
     * Written as a scanner over the whole file rather than per line: dropping a line because it
     * starts with `*` — what the older lints here do — also drops a continuation written
     * operator-first, and a `//` inside a string literal is not a comment.
     */
    private fun codeText(file: File): String {
        val code = StringBuilder()
        var inBlockComment = false
        for (raw in file.readLines()) {
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            code.append('\n')
        }
        return code.toString().replace(Regex("""\s+"""), " ").trim()
    }

    companion object {
        /** The only title the top bar may spell, all three arms of it. */
        private const val EXPECTED_TITLE_ARGUMENT =
            "if (ui.unreadView) { stringResource(R.string.inbox_unread_view) } " +
                "else if (ui.unified) { stringResource(R.string.inbox_all_inboxes) } " +
                "else { mailboxDisplayName(selectedRole, ui.mailboxName) }"

        /** The guard that keeps the account line off the unified view. */
        private const val EXPECTED_SUBTITLE_CONDITION =
            "!ui.unified && ui.accountName.isNotBlank()"

        /** The arm every OTHER folder still goes through. */
        private const val EXPECTED_ROLE_CALL =
            "mailboxDisplayName(selectedRole, ui.mailboxName)"

        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
    }
}
