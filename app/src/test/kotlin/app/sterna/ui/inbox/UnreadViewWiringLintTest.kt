package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 *
 * It reads `InboxScreen.kt` and `InboxViewModel.kt` as text and proves nothing about what the app
 * draws. There is no Compose harness in this repo, `InboxScreen` is not instantiable on the JVM and
 * `InboxViewModel` is an `AndroidViewModel` over Room; the drawer entry that makes the "unread" view
 * reachable at all is therefore out of reach of any executing test. What CAN be executed is what the
 * entry shows ([unreadViewCount], run in `UnreadViewCountTest`) and what an empty list of it says
 * ([emptyListScene], run in `EmptyListSceneTest`) — this file pins the wiring between them, as whole
 * ARGUMENTS, never as "the call exists".
 *
 * The mutations it exists for, every one of which compiles and leaves everything else green:
 *  - `selected = ui.unified` on the entry: the tick sits on "All inboxes" while the unread view is
 *    the one on screen;
 *  - the entry moved inside `if (accounts.size > 1)`: a per-account view that a single-account
 *    install can never reach — and this branch was written from the unified entry, where that guard
 *    is right;
 *  - the toolbar funnel left visible in the view: it is drawn OFF over a list [listUnreadOnly] has
 *    filtered, and tapping it changes nothing;
 *  - `unreadView = sel is Sel.Unified` in the state: every one of the three decisions above then
 *    describes the wrong view, in one word, in a file no executing test can reach;
 *  - the badge folded in from another flow, or read from another field, or never folded in at all —
 *    three type-correct one-word edits, none of which any executing test can see;
 *  - `atInbox` answering true for a view that has no folder, which turns Back into "leave the app";
 *  - the search hint left on its two arms, promising a search of the Inbox over a search of the
 *    whole account.
 *
 * ⛔ Every rule compares a WHOLE unit — the whole argument, the whole condition, the whole line.
 * A `contains` on a fragment is blind to any mutation that LENGTHENS it: `selected = ui.unreadView`
 * is contained in `selected = ui.unreadView && false`. The price is that a reformat reddens this
 * file; that direction of error is loud and one line to fix.
 */
class UnreadViewWiringLintTest {

    // -- the drawer entry -------------------------------------------------------------------------

    @Test
    fun `the drawer entry says which view it selects, and selects it`() {
        val entry = unreadEntryCall()
        assertEquals(
            "The drawer entry for the unread view must be wired exactly like the unified one it was " +
                "written from, and to ITS OWN view: 'selected = ui.unified' puts the tick on the " +
                "wrong row, and an onClick that closes the drawer without calling selectUnread() " +
                "leaves the entry inert. Compared as whole arguments — 'selected = ui.unreadView' is " +
                "contained in 'selected = ui.unreadView && false'. Arguments found were:" +
                "\n${argumentsOf(entry).joinToString("\n")}",
            EXPECTED_ENTRY_ARGUMENTS,
            argumentsOf(entry),
        )
    }

    @Test
    fun `the entry carries the count, and drops it when there is none`() {
        val block = unreadEntryBlock()
        assertEquals(
            "The label must be picked on the count itself. Zero unread has to fall back to the bare " +
                "label, exactly as the unified entry does — 'Unread  (0)' is a promise of nothing. " +
                "Compared as the whole condition. Conditions found in the entry were:" +
                "\n${ifConditions(block).joinToString("\n")}",
            listOf(EXPECTED_COUNT_CONDITION),
            ifConditions(block),
        )
        assertEquals(
            "The counted arm must be handed the SAME count the condition tested — a second source " +
                "(ui.unreadCount is the unified aggregate, and is the neighbouring entry's) would " +
                "print one account's view under another's number. ⭐ Unlike that neighbour, this " +
                "entry carries its number whether or not it is the view on screen, and that is a " +
                "CHOICE: K-9 and Gmail badge a mailbox permanently, and the badge is only useful " +
                "before you tap it. The unified entry hides its own outside its view for a reason " +
                "of its own — ui.unreadCount holds the unified total only while unified is selected. " +
                "Compared as whole calls, in source order: counted arm first, bare label second. " +
                "Calls found were:\n${callsTo("stringResource", block).joinToString("\n")}",
            EXPECTED_LABEL_CALLS,
            callsTo("stringResource", block),
        )
    }

    /**
     * ⭐ The one difference from the unified entry, and the reason this rule exists: the unread view
     * is a view of ONE account, so it has a meaning on an install that has only one. Written inside
     * the `accounts.size > 1` guard it would be dead for exactly the readers it was built for.
     */
    @Test
    fun `the entry is not hidden behind a second account`() {
        val blocks = multiAccountBlocks()
        assertEquals(
            "Exactly one 'accounts.size > 1' block is expected to hold the unified entry — this rule " +
                "is anchored on it, so any other answer means the guard moved and the rule below " +
                "stopped guarding anything. Blocks were:\n${blocks.joinToString("\n")}",
            1,
            blocks.count { Regex("""\bselectUnified\s*\(""").containsMatchIn(it) },
        )
        assertEquals(
            "The unread entry must stay OUT of every multi-account guard: it is one account's unread " +
                "mail across its own folders, and a single-account install is precisely where the " +
                "drawer has no other way to reach it. Blocks were:\n${blocks.joinToString("\n")}",
            0,
            blocks.count { Regex("""\bselectUnread\s*\(""").containsMatchIn(it) },
        )
    }

    // -- the toolbar ------------------------------------------------------------------------------

    @Test
    fun `the unread funnel is not offered in a view that is already filtered`() {
        val actions = toolbarActions()
        assertEquals(
            "The toolbar's unread funnel must be hidden in the unread view. [listUnreadOnly] forces " +
                "the filter on there, so the button would be drawn in its OFF colour over a filtered " +
                "list (it reads ui.unreadOnly, which the scope does not touch) and tapping it would " +
                "change nothing on screen. Compared as whole conditions: an empty answer means the " +
                "guard was dropped, and a longer one means it grew a term. Conditions mentioning the " +
                "view, found in the toolbar's actions, were:\n${viewConditions(actions).joinToString("\n")}",
            listOf(EXPECTED_FUNNEL_GUARD),
            viewConditions(actions),
        )
        assertEquals(
            "The toolbar's actions are expected to hold exactly one toggleUnreadOnly() — the funnel " +
                "the rule above guards. Actions block was:\n$actions",
            1,
            Regex("""\btoggleUnreadOnly\s*\(""").findAll(actions).count(),
        )
    }

    // -- the state the screen reads ---------------------------------------------------------------

    /**
     * ⚠ The regex is `\bunreadView(Count)?\b`, and the parenthesis is not decoration: `\bunreadView\b`
     * does NOT match `unreadViewCount` (there is no word boundary between "w" and "C"), so a rule
     * written that way leaves every line that carries the NUMBER unguarded — `= base.unread` in
     * place of `= base.unreadViewCount` is two `Int`s, compiles, and puts the current folder's count
     * on the entry. It must not match `unreadViewScopes` either, which is volet 1's and is pinned by
     * `PageKeyWiringLintTest`.
     */
    @Test
    fun `the view flag and the badge are carried whole, from their own state`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { Regex("""\bunreadView(Count)?\b""").containsMatchIn(it) }
        assertEquals(
            "MailUi.unreadView is what the drawer tick, the title, the search hint and the hidden " +
                "funnel are all decided on, and 'sel is Sel.Unified' would compile in its place; " +
                "MailUi.unreadViewCount is the number on the entry, and any other Int compiles " +
                "there. Whole lines, because a fragment is blind to a term added. Lines found were:" +
                "\n${lines.joinToString("\n")}",
            EXPECTED_VIEW_FLAG_LINES,
            lines.sorted(),
        )
    }

    /**
     * ⛔ SOURCE LINT, and the rule the one above cannot be: it pins WHERE THE VALUES COME FROM.
     *
     * Three mutations live here, all of them type-correct and all of them silent:
     *  - `combine(baseState, unifiedInboxScopes, currentAccountId)` — same type, and the entry then
     *    announces the unread of the INBOXES over a list that pages every folder;
     *  - `combine(baseState, …)` where the state reads `badgedState` — the badge is then never
     *    written and the entry never carries a number at all;
     *  - a fourth flow spliced in, or the five-flow `baseState` re-ordered.
     */
    @Test
    fun `the badge is folded in from the unread scope, and the state reads the folded flow`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { Regex("""\b(baseState|badgedState)\b""").containsMatchIn(it) }
        assertEquals(
            "The three declarations that carry the drawer badge must stay exactly as they are. " +
                "The scope handed to the fold is the one the PAGER is built from (unreadScopes, " +
                "account-pinned pairs — #121); the flow the UI state is built from is the folded " +
                "one. Whole lines, arguments and lambda headers included. Lines found were:" +
                "\n${lines.joinToString("\n")}",
            EXPECTED_STATE_LINES,
            lines.sorted(),
        )
        val calls = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { Regex("""\bunreadViewCount\s*\(""").containsMatchIn(it) }
        assertEquals(
            "The badge must be summed by unreadViewCount(...) — run by UnreadViewCountTest — from " +
                "the account, the scope and the folder list the state already carries. Calls found " +
                "were:\n${calls.joinToString("\n")}",
            EXPECTED_BADGE_CALL,
            calls,
        )
    }

    /**
     * ⛔ Not this volet's feature, but this volet's accident. `atInbox` drives the system Back
     * gesture: true means "this IS the inbox", and Back then LEAVES THE APP.
     *
     * Written as `(sel as? Sel.Folder)?.id == store.inboxMailboxId()`, the unread view answers
     * `null == null` on an account whose inbox has never been synced — no folder selected on one
     * side, no cached inbox id on the other — and the view silently becomes an exit. The structural
     * form cannot: a selection that is not a folder is not the inbox, whatever the store knows.
     * `Sel.Folder(null)` keeps the answer it always had, deliberately.
     */
    @Test
    fun `no view without a folder can pass for the inbox`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
            .filter { Regex("""\batInbox\s*=""").containsMatchIn(it) }
        assertEquals(
            "atInbox must be decided structurally — 'sel is Sel.Folder && sel.id == …' — never by " +
                "'(sel as? Sel.Folder)?.id == …', which is true for ANY non-folder selection the " +
                "moment the cached inbox id is null. It is the Back gesture: true here and Back " +
                "leaves the app from a view the reader chose. Lines found were:" +
                "\n${lines.joinToString("\n")}",
            EXPECTED_AT_INBOX_LINES,
            lines.sorted(),
        )
    }

    /**
     * The search field's hint says what the search covers. In this view it covers the whole account
     * ([InboxViewModel.searchAccounts] answers the current account, and the scope is its folders),
     * while the label falls back to `ui.mailboxName` — which `selectUnread()` has just set to the
     * INBOX's name. "Search in Inbox", over a search of everything.
     */
    @Test
    fun `the search hint names the view, not the folder last visited`() {
        val block = searchScopeLabel()
        assertEquals(
            "The hint's arms must be the title's arms: the unread view first (it selects no folder " +
                "either, so it falls through to the folder name otherwise), then the unified inbox, " +
                "then the folder by its role. Compared as whole conditions. Conditions found were:" +
                "\n${ifConditions(block).joinToString("\n")}",
            listOf("ui.unreadView", "ui.unified"),
            ifConditions(block),
        )
        assertEquals(
            "Each arm must name its own scope, and the unread one reuses the title's string — no " +
                "tenth translation for the same word. Compared as whole calls, in source order. " +
                "Calls found were:\n${scopeCalls(block).joinToString("\n")}",
            EXPECTED_SEARCH_SCOPE_CALLS,
            scopeCalls(block),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The `NavigationDrawerItem(...)` call that selects the unread view, whole. */
    private fun unreadEntryCall(): String {
        val text = codeText(INBOX_SCREEN)
        val entries = Regex("""\bNavigationDrawerItem\s*\(""").findAll(text)
            .map { callAt(text, it.range.first) }
            .filter { Regex("""\bselectUnread\s*\(""").containsMatchIn(it) }
            .toList()
        check(entries.size == 1) {
            "InboxScreen.kt is expected to hold exactly ONE NavigationDrawerItem calling " +
                "selectUnread() — the drawer's entry into the unread view. Found ${entries.size}: " +
                "was the entry removed, renamed, or is there now a second way in that would have to " +
                "be told apart here?"
        }
        return entries.single()
    }

    /** The label statement plus the entry it labels: from `val unreadLabel` to the end of the call. */
    private fun unreadEntryBlock(): String {
        val text = codeText(INBOX_SCREEN)
        val start = text.indexOf("val unreadLabel")
        check(start >= 0) {
            "InboxScreen.kt is expected to build the unread entry's label in a local named " +
                "'unreadLabel', as the unified entry does with 'unifiedLabel' — this rule reads the " +
                "text between that name and the entry it feeds."
        }
        val entry = unreadEntryCall()
        val end = text.indexOf(entry, start)
        check(end > start) { "the unread drawer entry does not follow 'val unreadLabel'" }
        return text.substring(start, end + entry.length)
    }

    /** Every body of `if (accounts.size > 1)`, i.e. what only a second account makes reachable. */
    private fun multiAccountBlocks(): List<String> {
        val text = codeText(INBOX_SCREEN)
        val hits = Regex("""\bif\s*\(\s*accounts\.size\s*>\s*1\s*\)""").findAll(text).toList()
        check(hits.isNotEmpty()) {
            "InboxScreen.kt is expected to guard at least one block on 'accounts.size > 1' — the " +
                "unified entry's. Found none; was the guard rewritten?"
        }
        return hits.map { balancedFrom(text, it.range.last) }
    }

    /** The `actions = { … }` lambda of the browse toolbar (never the search bar's). */
    private fun toolbarActions(): String {
        val text = codeText(INBOX_SCREEN)
        val bars = Regex("""\bMediumTopAppBar\s*\(""").findAll(text).toList()
        check(bars.size == 1) {
            "InboxScreen.kt is expected to call MediumTopAppBar exactly once — the browse toolbar, " +
                "the one carrying the unread funnel. Found ${bars.size}."
        }
        val bar = callAt(text, bars.single().range.first)
        val at = bar.indexOf("actions =")
        check(at >= 0) { "the browse toolbar has no actions block:\n$bar" }
        return balancedFrom(bar, at)
    }

    /**
     * The `val scopeLabel = …` statement of the search bar, up to the statement that follows it.
     *
     * Anchored on both names rather than on `TopAppBar(`: `InboxScreen.kt` builds two of those, and
     * a rule read over the wrong one would be satisfied by the browse bar's title. Fails loudly if
     * either name moves — a rule that quietly reads an empty string guards nothing.
     */
    private fun searchScopeLabel(): String {
        val text = codeText(INBOX_SCREEN)
        val start = text.indexOf("val scopeLabel")
        val end = text.indexOf("val focusManager", start + 1)
        check(start >= 0 && end > start) {
            "InboxScreen.kt is expected to build the search field's hint in a local named " +
                "'scopeLabel', followed by 'val focusManager' — this rule reads the text between " +
                "them. Found scopeLabel at $start, focusManager at $end."
        }
        return text.substring(start, end)
    }

    /** The scope-naming calls of [block], whole, in source order. */
    private fun scopeCalls(block: String): List<String> =
        Regex("""\b(stringResource|mailboxDisplayName)\s*\(""").findAll(block)
            .map { callAt(block, it.range.first) }
            .toList()

    /** Every `if` condition of [block] that mentions the unread VIEW, each one whole. */
    private fun viewConditions(block: String): List<String> =
        ifConditions(block).filter { Regex("""\bunreadView\b""").containsMatchIn(it) }

    /** Every `if` condition in [block], whole, in source order. */
    private fun ifConditions(block: String): List<String> =
        Regex("""\bif\s*\(""").findAll(block).map { hit ->
            val call = callAt(block, hit.range.first)
            call.substring(call.indexOf('(') + 1, call.length - 1).trim()
        }.toList()

    /** Every call to [name] in [block], whole, arguments included, in source order. */
    private fun callsTo(name: String, block: String): List<String> =
        Regex("""\b${Regex.escape(name)}\s*\(""").findAll(block)
            .map { callAt(block, it.range.first) }
            .toList()

    /** The arguments of the call [text], split at the commas of its own depth, blanks dropped. */
    private fun argumentsOf(text: String): List<String> {
        val open = text.indexOf('(')
        check(open >= 0) { "not a call: $text" }
        val args = mutableListOf<String>()
        var depth = 0
        var start = open + 1
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(', '{', '[' -> depth++
                ')', ']', '}' -> depth--
            }
            if (depth == 0) {
                args += text.substring(start, i)
                break
            }
            if (depth == 1 && text[i] == ',') {
                args += text.substring(start, i)
                start = i + 1
            }
            i++
        }
        return args.map { it.trim() }.filter { it.isNotEmpty() }
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
     * [file] as ONE line of code: comments taken out, runs of whitespace collapsed — so a call the
     * formatter spreads over eight lines reads like one written on a line. Same scanner as
     * `UnifiedTitleWiringLintTest`'s, and for the same reason: a `//` inside a string literal is not
     * a comment, and a line starting with `*` is not always a comment either.
     */
    private fun codeText(file: File): String = strippedLines(file).joinToString(" ")
        .replace(Regex("""\s+"""), " ").trim()

    /** The same source, comments removed, but kept LINE BY LINE for the whole-line rules. */
    private fun codeLines(file: File): List<String> = strippedLines(file)

    private fun strippedLines(file: File): List<String> {
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
            out += code.toString()
        }
        return out
    }

    companion object {
        /** The drawer entry, argument by argument — the gabarit of the unified one, its own view. */
        private val EXPECTED_ENTRY_ARGUMENTS = listOf(
            "icon = { Icon(Icons.Filled.MarkEmailUnread, contentDescription = null) }",
            "label = { Text(unreadLabel) }",
            "selected = ui.unreadView",
            "onClick = { viewModel.selectUnread() scope.launch { drawerState.close() } }",
            "modifier = Modifier.padding(horizontal = 12.dp)",
        )

        /** No count, no number — the unified entry's rule, on this entry's own count. */
        private const val EXPECTED_COUNT_CONDITION = "ui.unreadViewCount > 0"

        private val EXPECTED_LABEL_CALLS = listOf(
            "stringResource(R.string.inbox_unread_view_unread, ui.unreadViewCount)",
            "stringResource(R.string.inbox_unread_view)",
        )

        /** The funnel's guard, whole. */
        private const val EXPECTED_FUNNEL_GUARD = "!ui.unreadView"

        /** Every line of the ViewModel that names the flag, or the number, the screen reads. */
        private val EXPECTED_VIEW_FLAG_LINES = listOf(
            "base.copy(accountId = accountId, unreadViewCount = unreadViewCount(accountId, scopes, base.mailboxes))",
            "unreadView = base.unreadView,",
            "unreadView = sel is Sel.Unread,",
            "unreadViewCount = base.unreadViewCount,",
            "val unreadView: Boolean = false,",
            "val unreadView: Boolean,",
            "val unreadViewCount: Int = 0,",
            "val unreadViewCount: Int = 0,",
        )

        /** The three declarations the badge travels through, whole. */
        private val EXPECTED_STATE_LINES = listOf(
            "private val badgedState = combine(baseState, unreadScopes, currentAccountId) { base, scopes, accountId ->",
            "private val baseState = combine(mailboxes, selection, meta, status, unifiedUnread) { mailboxes, sel, meta, status, unifiedUnread ->",
            "val state: StateFlow<MailUi> = combine(badgedState, searchState, settings.sortOrder, unreadOnly, connectivity.online) { base, search, sortOrder, unreadOnly, online ->",
        )

        /** The badge, computed once, from the scope the pager uses. */
        private val EXPECTED_BADGE_CALL = listOf(
            "base.copy(accountId = accountId, unreadViewCount = unreadViewCount(accountId, scopes, base.mailboxes))",
        )

        /** Back's answer, structural where it has to be, unchanged where it was already right. */
        private val EXPECTED_AT_INBOX_LINES = listOf(
            "atInbox = base.atInbox,",
            "atInbox = sel is Sel.Unified || (sel is Sel.Folder && sel.id == store.inboxMailboxId()),",
            "atInbox = selection.value is Sel.Unified || (selection.value as? Sel.Folder)?.id == store.inboxMailboxId(),",
        )

        /** The search hint's three scopes, in source order. */
        private val EXPECTED_SEARCH_SCOPE_CALLS = listOf(
            "stringResource(R.string.inbox_unread_view)",
            "stringResource(R.string.inbox_all_inboxes)",
            "mailboxDisplayName(searchRole, ui.mailboxName)",
        )

        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

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
        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
