package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `SettingsRepository.kt` and `InboxViewModel.kt` as
 * text. It proves nothing about what the app does, and it would still pass if everything it points at
 * were broken inside. [PageKeyFlowTest] is the real test of the fix — read it first: it EXECUTES
 * [pageKeyFlow] and counts what it emits, member by member.
 *
 * Two sections, for the two things that fix has which no JVM test can reach.
 *
 * **1. The two settings flows are deduped.** `SettingsRepository.sortOrder` and `.conversationView`
 * are `dataStore.data.map { … }`, and DataStore republishes the WHOLE `Preferences` on every write,
 * whatever key was touched. Left undeduped they re-emit their old value on every unrelated setting
 * write, and every consumer of them — not just the list — is handed a change that is not one.
 * Reaching that from a JVM test would need a `SettingsRepository`, which needs a `Context` and a real
 * DataStore file: the `app` module has no `androidTest`, no Robolectric and no instrumented suite.
 *
 * **2. The six flows are wired to the right parameters.** This section exists because extracting the
 * key builder into [pageKeyFlow] turned an expression written IN PLACE into a call with six flow
 * parameters — among them two `Flow<Boolean>` and two `Flow<String?>`, interchangeable to the
 * compiler. [PageKeyFlowTest] proves the DECISION, passing its own flows; it never reads
 * `InboxViewModel`, so it cannot see what the real call site hands over. Two mutations compile and
 * leave the whole suite green without this section:
 *  - `currentAccountId = selectionAccountId` — a `StateFlow<String?>` that is null until something is
 *    selected and never moves when the account changes. Two same-server JMAP accounts whose inboxes
 *    share a mailbox id then build an EQUAL key, the dedupe swallows it, the pager is not rebuilt, and
 *    the screen keeps showing the neighbour account's mail with the neighbour's credentials in the
 *    `RemoteMediator`. Codeberg #121 back, in silence;
 *  - the two `Flow<Boolean>` swapped — the list filters unread by the conversation setting and
 *    collapses threads by the unread filter.
 * `InboxViewModel` is an `AndroidViewModel`: not instantiable here, so reading the source is what is
 * left.
 *
 * ⛔ Every rule compares a WHOLE unit — a whole declaration, a whole `name = argument` pair — never a
 * fragment. A `contains` on a piece is blind to any mutation that LENGTHENS it, which has bitten this
 * repo three times, and here it is not theoretical: `currentAccountId = selectionAccountId` CONTAINS
 * `currentAccountId =`, so a rule on the parameter name alone would wave the first mutation through.
 * The price is that a legitimate reformat reddens this test. That direction of error is loud and one
 * line to fix; the other direction is a fix that silently comes undone.
 *
 * ⛔ Deliberately does NOT reuse the `codeLines()` helper the other source lints here carry: that one
 * drops any line whose first non-blank character is `*`, which also drops a continuation line written
 * operator-first — exactly the shape a guard chained on the next line takes. The reader below carries
 * the block-comment state across lines instead. The eleven existing copies of that helper are out of
 * scope and stay as they are.
 *
 * Scope, stated plainly: only the TWO settings flows. `SettingsRepository` holds twenty-seven flows in
 * the same shape; deduping them all is a different change and is not this one.
 */
class PageKeyWiringLintTest {

    // -- 1. the two settings flows the paging key is built from ---------------------------------

    @Test
    fun `sortOrder is deduped, and is nothing but the read and the guard`() {
        val text = declaration(SETTINGS, "sortOrder")
        assertTrue(
            "SettingsRepository.sortOrder must end on .distinctUntilChanged(). Without it, ANY " +
                "preference write — \"always show images from this sender\", a swipe action, a " +
                "signature — republishes the whole Preferences, this flow re-emits the sort order it " +
                "already had, the list's paging key is rebuilt equal, and flatMapLatest throws away " +
                "the running Pager for one that starts at the first page. Declaration was:\n$text",
            text.endsWith(GUARD),
        )
        assertEquals(
            "SettingsRepository.sortOrder must be exactly the preference read plus the guard, and " +
                "nothing else: a stage added after the guard re-emits past it and the guard stops " +
                "meaning anything. Compared whole precisely so a mutation that makes the line LONGER " +
                "cannot hide. Declaration was:\n$text",
            "val sortOrder: Flow<SortOrder> = dataStore.data.map { prefs -> " +
                "prefs[KEY_SORT_ORDER]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } " +
                "?: SortOrder.DATE_DESC }$GUARD",
            text,
        )
    }

    @Test
    fun `conversationView is deduped, and is nothing but the read and the guard`() {
        val text = declaration(SETTINGS, "conversationView")
        assertTrue(
            "SettingsRepository.conversationView must end on .distinctUntilChanged() — same reason " +
                "as sortOrder: it is the second settings flow the list's paging key is built from, so " +
                "an undeduped re-emission here rebuilds the pager on its own. Declaration was:\n$text",
            text.endsWith(GUARD),
        )
        assertEquals(
            "SettingsRepository.conversationView must be exactly the preference read plus the guard. " +
                "Declaration was:\n$text",
            "val conversationView: Flow<Boolean> = dataStore.data.map " +
                "{ it[KEY_CONVERSATION_VIEW] ?: true }$GUARD",
            text,
        )
    }

    // -- 2. what the ViewModel actually hands pageKeyFlow ---------------------------------------

    @Test
    fun `the browse list passes each of the seven flows to its own parameter`() {
        val arguments = pageKeyFlowArguments()
        assertEquals(
            "InboxViewModel must hand pageKeyFlow these seven flows and no others, each NAMED and each " +
                "matched with its own parameter. Compared as whole pairs, because the parameter name " +
                "on its own proves nothing: 'currentAccountId = selectionAccountId' contains " +
                "'currentAccountId =' and would satisfy a looser rule, while selectionAccountId is " +
                "null until something is selected and never moves on an account switch — two " +
                "same-server accounts sharing a mailbox id would then build an EQUAL key, the dedupe " +
                "would swallow it, and the list would keep showing the other account's mail (#121). " +
                "The same comparison is what stops the two Flow<Boolean> being swapped, and the two " +
                "Flow<List<Pair<String, String>>> with it — unifiedInboxScopes and unreadScopes are " +
                "the same type and adjacent in the key, so the swap compiles and the unread view " +
                "would page every account's Inbox. Call site was:" +
                "\n${arguments.joinToString("\n")}",
            EXPECTED_ARGUMENTS.sorted(),
            arguments.sorted(),
        )
    }

    /**
     * ⛔ SOURCE LINT, and the guard on the destructive half of Codeberg #126.
     *
     * What the list SHOWS and what "Select all" TAKES have to be the same set, and the only way to
     * be sure of that is for both to ask one function. [ListUnreadOnlyTest] runs that function; this
     * rule is the only thing that can see whether the two call sites use it, because `pagedEmails`
     * and `selectAll` both live in an `AndroidViewModel`.
     *
     * The mutation it exists for is `listUnreadOnly(selection.value, unreadOnly.value)` →
     * `unreadOnly.value` in `selectAll`. It compiles, it reads like the line that was there for
     * years, and every other test in this module stays green: the list draws twelve unread rows
     * while `selectableIds` is asked for the folders UNFILTERED, and "Select all" then "Delete"
     * moves every read message of the Inbox, the Archive and each user folder to the Trash — mail
     * nobody was shown. Whole lines, because a fragment match cannot tell the two apart.
     */
    @Test
    fun `the list's unread filter is decided once, and the selection asks the same question`() {
        val readers = codeText(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { "unreadOnly.value" in it || "listUnreadOnly(" in it }
        assertEquals(
            "Every place that describes WHAT THE LIST SHOWS must ask listUnreadOnly(...) — the three " +
                "pager branches and selectAll's `filtered`. The only line allowed to touch " +
                "unreadOnly.value on its own is the toolbar toggle, which describes the BUTTON. A " +
                "raw read in selectAll is the destructive half of #126 rebuilt. Lines found were:" +
                "\n${readers.joinToString("\n")}",
            listOf(
                "repo.pagedFolder(credentials, id, key.sort, listUnreadOnly(key.sel, key.unreadOnly), key.conversationView, sent)",
                "repo.pagedMailbox(key.unifiedScopes, key.sort, listUnreadOnly(key.sel, key.unreadOnly), key.conversationView, sent)",
                "repo.pagedMailbox(key.unreadScopes, key.sort, listUnreadOnly(key.sel, key.unreadOnly), key.conversationView, sent)",
                "unreadOnly.value = !unreadOnly.value",
                "val filtered = listUnreadOnly(selection.value, unreadOnly.value)",
            ),
            readers.sorted(),
        )
    }

    /**
     * ⛔ SOURCE LINT. The rule above pins that the unread view's scope is HANDED to the paging key;
     * this one pins that anything ever puts a value in it. Drop the one line in the `mailboxes`
     * `onEach` that fills it and every other rule here stays green while the view pages an empty
     * scope list for ever — `pagedMailbox` answers `PagingData.empty()`, so the screen is simply
     * blank and nothing fails.
     *
     * Whole assignments, arguments included. `unreadViewScopes(store.currentId(), folders)` would
     * compile and read plausibly, and it is the #121 mistake: the folder list in hand belongs to the
     * account the flow was scoped to, and `store.currentId()` is whatever the store says NOW.
     *
     * ⛔ It must also stay TWO. A third writer is a third resolution, and the clearing one exists
     * only because `onAccountChanged` holds the previous account's folder list.
     */
    @Test
    fun `the unread scope has exactly two writers, and each says what it writes`() {
        val writes = codeText(INBOX_VIEW_MODEL).map { it.trim() }.filter { it.startsWith("unreadScopes.value") }
        assertEquals(
            "InboxViewModel must fill the unread view's scope from the folder list the [mailboxes] " +
                "flow just delivered, with the account THAT FLOW is scoped to (currentAccountId.value, " +
                "not a fresh store read — #121), and clear it on an account switch, where the folder " +
                "list in hand is still the previous account's. Nothing else may write it. Writes " +
                "found were:\n${writes.joinToString("\n")}",
            listOf(
                "unreadScopes.value = emptyList()",
                "unreadScopes.value = unreadViewScopes(currentAccountId.value, folders)",
            ),
            writes.sorted(),
        )
    }

    /**
     * ⛔ SOURCE LINT, and a LAST RESORT — read this before trusting it.
     *
     * What it pins is the DECISION of the `Sel.Unread` branch of `pagedEmails`: which scope list the
     * pager is built from, and that its unread filter is the literal `true` rather than the toolbar's
     * toggle. That decision cannot be executed here — `pagedEmails` is a property of an
     * `AndroidViewModel`, and the pager under it needs Room. What CAN be executed is the scope the
     * branch is handed ([unreadViewScopes], run in `UnreadViewScopesTest`) and the key it is handed it
     * through ([pageKeyFlow], run in `PageKeyFlowTest`); this rule is the one link between them that
     * no JVM test can reach, and it is written as a whole ARGUMENT LIST, never as "the call exists".
     *
     * The forced `true` is the point. This view is called "Unread": with the funnel off, `key.unreadOnly`
     * is false and the very same screen lists the account's whole mail under that name (WYSIWYG). The
     * mutation `unreadOnly = true` → `key.unreadOnly` compiles, changes nothing anywhere else, and is
     * exactly the defect — so it has to redden something, and this is the only thing it can redden.
     */
    @Test
    fun `each multi-folder pager is built from its own scope list, and the unread one forces the filter`() {
        assertEquals(
            "InboxViewModel must build exactly two multi-folder pagers — the unified inbox and the " +
                "unread scope — with these arguments. Compared as whole lists: 'key.unifiedScopes' in " +
                "the unread branch pages every account's Inbox under a name that promises one " +
                "account's unread mail, and 'key.unreadOnly' in place of listUnreadOnly(...) lists " +
                "that account's READ mail too the moment the toolbar funnel is off. Calls found were:" +
                "\n${pagedMailboxCalls().joinToString("\n")}",
            listOf(
                listOf(
                    "key.unifiedScopes", "key.sort", "listUnreadOnly(key.sel, key.unreadOnly)",
                    "key.conversationView", "sent",
                ),
                listOf(
                    "key.unreadScopes", "key.sort", "listUnreadOnly(key.sel, key.unreadOnly)",
                    "key.conversationView", "sent",
                ),
            ),
            // Sorted, so reordering the branches of the `when` is not an error — which of the two
            // is written first says nothing.
            pagedMailboxCalls().sortedBy { it.joinToString(",") },
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /**
     * The arguments of the single `pageKeyFlow(` call in `InboxViewModel.kt`, one string per argument,
     * whitespace collapsed — so a call the formatter spreads over eight lines reads the same as one
     * written on a line. Split on the commas that sit at the call's own bracket depth, so a nested
     * call's arguments could not be mistaken for the call's own.
     *
     * Fails loudly on anything but exactly one call: a second one would have to be told apart here
     * rather than silently picked.
     */
    private fun pageKeyFlowArguments(): List<String> {
        val text = codeText(INBOX_VIEW_MODEL).joinToString("\n")
        val calls = Regex("""\bpageKeyFlow\s*\(""").findAll(text).toList()
        assertEquals(
            "InboxViewModel is expected to call pageKeyFlow exactly once — the browse list's paging " +
                "key. Found ${calls.size}.",
            1, calls.size,
        )
        return splitArguments(balanced(text, calls.single().range.last))
    }

    /**
     * The arguments of EVERY `repo.pagedMailbox(` call in `InboxViewModel.kt`, one list per call, in
     * source order. Fails loudly on anything but exactly two: the unified inbox's and the unread
     * scope's. A third would be a third multi-folder view, which has to be judged here rather than
     * silently ignored.
     */
    private fun pagedMailboxCalls(): List<List<String>> {
        val text = codeText(INBOX_VIEW_MODEL).joinToString("\n")
        val calls = Regex("""\brepo\.pagedMailbox\s*\(""").findAll(text).toList()
        assertEquals(
            "InboxViewModel is expected to build exactly two pagers over a list of (account, folder) " +
                "scopes — the unified inbox and the unread scope. Found ${calls.size}.",
            2, calls.size,
        )
        return calls.map { splitArguments(balanced(text, it.range.last)) }
    }

    /** [inside] split on the commas that sit at its own bracket depth, whitespace collapsed — so a
     *  call the formatter spread over eight lines reads the same as one written on a line, and a
     *  nested call's arguments cannot be mistaken for the call's own. */
    private fun splitArguments(inside: String): List<String> {
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in inside) {
            when {
                c == '(' || c == '{' || c == '[' -> { depth++; current.append(c) }
                c == ')' || c == '}' || c == ']' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { pieces += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        pieces += current.toString()
        return pieces.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
    }

    /** [text] from the first `(` at or after [from], up to the `)` that balances it. */
    private fun balanced(text: String, from: Int): String {
        val start = text.indexOf('(', from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start))
    }

    /**
     * The declaration of `val` [name] in [file], comments removed and whitespace collapsed to single
     * spaces: from its own line up to the line where its brackets balance AND the next line is not a
     * continuation of it.
     *
     * The continuation clause is not decoration. `conversationView` fits on one line, so its brackets
     * balance there and a reader that stopped on balance alone would hand back the declaration WITHOUT
     * the `.distinctUntilChanged()` chained underneath — the guard this test exists to hold, reported
     * missing while it is right there. Which is the same blindness, one layer up, as the `codeLines()`
     * helper that drops any line starting with an operator.
     *
     * Fails loudly when the declaration is not found: a rename must redden these rules rather than
     * satisfy them against an empty string.
     */
    private fun declaration(file: File, name: String): String {
        val lines = codeText(file)
        val start = lines.indexOfFirst { Regex("""\bval\s+$name\b""").containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no 'val $name' — did it get renamed?" }
        val out = mutableListOf<String>()
        var depth = 0
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            i++
            if (depth > 0) continue
            val next = lines.getOrNull(i) ?: break
            if (!CONTINUATION.containsMatchIn(next)) break
        }
        // A member-access continuation is glued back on with no space, so a call chained on the next
        // line reads exactly as the same chain written on one — the rules must not care which the
        // formatter chose. Any other continuation (an elvis, a boolean) keeps its space.
        val text = out.fold(StringBuilder()) { sb, line ->
            val piece = line.trim()
            if (sb.isNotEmpty() && !GLUED.containsMatchIn(piece)) sb.append(' ')
            sb.append(piece)
        }
        return text.toString().replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * [file]'s non-blank lines with every comment taken out.
     *
     * Written as a scanner over the whole file rather than a per-line test, because the per-line
     * shortcut is what makes the other lints here blind: dropping a line because it STARTS with `*`
     * also drops a `.distinctUntilChanged()`-shaped continuation, and a guard chained on the next line
     * is precisely such a continuation. So the block-comment state is carried across lines, and a `//`
     * is only honoured outside a double-quoted string.
     */
    private fun codeText(file: File): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in file.readLines()) {
            val code = StringBuilder()
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
            if (code.isNotBlank()) out += code.toString().trim()
        }
        return out
    }

    companion object {
        private const val GUARD = ".distinctUntilChanged()"

        /** A line that carries on the previous one because it opens with an operator — `.map { }`,
         *  `.distinctUntilChanged()`, an elvis, a boolean. Kotlin needs no `\` for these, so a
         *  bracket-balanced declaration can still be continued on the line below. */
        private val CONTINUATION = Regex("""^\s*(\.|\?:|\?\.|\+|&&|\|\||,)""")

        /** The continuations that are re-joined WITHOUT a space: a member access, safe or not. */
        private val GLUED = Regex("""^\s*\??\.""")

        /**
         * The seven pairs the call site must spell. Whole pairs, and the ORDER is not compared: naming
         * every argument is what makes the call readable, and a reordered named call is the same call.
         * What is compared is that each parameter gets its own flow and no other.
         */
        private val EXPECTED_ARGUMENTS = listOf(
            "selection = selection",
            "unifiedInboxScopes = unifiedInboxScopes",
            "unreadViewScopes = unreadScopes",
            "sortOrder = settings.sortOrder",
            "unreadOnly = unreadOnly",
            "conversationView = settings.conversationView",
            "currentAccountId = currentAccountId",
        )

        private const val SETTINGS_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"
        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val SETTINGS: File by lazy { File(root, SETTINGS_PATH) }
        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
