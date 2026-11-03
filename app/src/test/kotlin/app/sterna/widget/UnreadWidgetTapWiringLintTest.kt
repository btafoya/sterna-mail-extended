package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test, same instrument and the same disclaimer as
 * [UnreadWidgetPushWiringLintTest] next door. The decisions themselves are executed elsewhere:
 */
class UnreadWidgetTapWiringLintTest {

    /**
     * The `Intent` behind the widget's `PendingIntent` carries an action of its own, and that is
     * not a nicety.
     */
    @Test fun `the widget's tap intent carries its own action, so it cannot collide with a notification's`() {
        assertEquals(
            "the widget's PendingIntent must be built from an Intent that sets its own action. " +
                "PendingIntent identity ignores extras, notification intents carry no action at " +
                "all, and their request codes cover every Int — so the action is the ONLY thing " +
                "keeping FLAG_UPDATE_CURRENT from rewriting a notification's target (#112).",
            listOf(
                "private fun tapIntent(context: Context, kind: UnreadWidgetKind, target: WidgetTapTarget): PendingIntent {",
                "val intent = Intent(context, MainActivity::class.java)",
                ".setAction(kind.action)",
                ".addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)",
                "UnreadWidgetTap.extraOf(target)?.let { intent.putExtra(it, true) }",
                "return PendingIntent.getActivity(",
                "context,",
                "kind.requestCode,",
                "intent,",
                "PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,",
                ")",
            ),
            functionBody(WIDGET_DRAW, "private fun tapIntent("),
        )
        assertEquals(
            "the actions must be real, app-owned strings, one per counter. Emptied or nulled they " +
                "stop separating the widget's intent from the notifications'; shared, the two " +
                "counters rewrite each other's extra.",
            listOf(
                "private const val ACTION_WIDGET_TAP = \"app.sterna.widget.TAP\"",
                "private const val ACTION_WIDGET_TAP_UNREAD = \"app.sterna.widget.TAP_UNREAD\"",
            ),
            codeLines(WIDGET_KIND).filter { it.startsWith("private const val ACTION_") },
        )
    }

    /**
     * The tap target is set on the shared `RemoteViews`, once, where the cell is drawn, the same
     */
    @Test fun `the drawn cell is given that tap target`() {
        assertEquals(
            "render() must attach the tap intent to the cell's root view. Without it the widget " +
                "draws the number and swallows every touch (#112).",
            listOf("setOnClickPendingIntent(R.id.widget_unread_root, tapIntent(context, kind, target))"),
            codeLines(WIDGET_DRAW).filter { it.startsWith("setOnClickPendingIntent(") },
        )
    }

    /**
     * Guard 1, and it is the reason the three existing extras are parsed inside this `if`.
     */
    @Test fun `the widget's extra is only read on a real launch`() {
        assertEquals(
            "MainActivity must parse the widget's extra inside the savedInstanceState == null " +
                "guard, beside the other one-shot payloads. Outside it, every rotation and every " +
                "locale change re-opens All inboxes over what the user is doing.",
            listOf(
                "if (savedInstanceState == null) {",
                "pendingMailto.value = parseMailto(intent) ?: parseShare(intent)",
                "pendingEmailOpen.value = parseEmailOpen(intent)",
                "pendingUnifiedOpen.value = parseUnifiedOpen(intent)",
                "pendingUnreadOpen.value = parseUnreadOpen(intent)",
                "consumeOAuthRedirect()",
                // The composer's resume slots, swept here for the same reason the three parses sit
                // here: with no saved state nothing can consume any of them, so a file left over is
                // a crash's orphan. Swept outside the guard it would delete the file a rotation is
                // about to read. See ComposerBodyLeavesTheParcelTest.
                "ComposerResumeSlot.sweep(filesDir)",
                "}",
            ),
            blockContaining(MAIN_ACTIVITY, "pendingUnifiedOpen.value = parseUnifiedOpen(intent)", "if (savedInstanceState == null) {"),
        )
    }

    /**
     * Guard 2: the one-shot strip, belt to the guard above's braces. Whatever re-reads the
     * retained intent later, it must no longer carry an order already carried out.
     */
    @Test fun `the widget's extra is stripped once consumed, on its own`() {
        assertEquals(
            "MainActivity needs its own stripUnifiedOpenPayload(), removing only the widget's " +
                "extra. Folded into another payload's strip it would cancel a pending one; " +
                "dropped altogether, a re-read of the retained intent re-opens All inboxes.",
            listOf(
                "private fun stripUnifiedOpenPayload() {",
                "val i = intent ?: return",
                "i.removeExtra(EXTRA_OPEN_UNIFIED)",
            ),
            functionBody(MAIN_ACTIVITY, "private fun stripUnifiedOpenPayload()"),
        )
        assertEquals(
            "the consumption callback handed to SternaApp must clear the held state AND strip the " +
                "intent — one without the other leaves the order live.",
            listOf(
                "onUnifiedOpenConsumed = {",
                "pendingUnifiedOpen.value = false",
                "stripUnifiedOpenPayload()",
                "},",
            ),
            blockContaining(MAIN_ACTIVITY, "onUnifiedOpenConsumed = {", "onUnifiedOpenConsumed = {"),
        )
    }

    /**
     * The path a widget tap almost always takes: the app is already running, `singleTask` routes
     */
    @Test fun `onNewIntent reads the widget's extra too`() {
        assertEquals(
            "onNewIntent must pick up the widget's extra beside the mailto and notification " +
                "payloads: with the app already running it is the ONLY path a tap takes.",
            listOf(
                "override fun onNewIntent(intent: Intent) {",
                "super.onNewIntent(intent)",
                "setIntent(intent)",
                "(parseMailto(intent) ?: parseShare(intent))?.let { pendingMailto.value = it }",
                "parseEmailOpen(intent)?.let { pendingEmailOpen.value = it }",
                "if (parseUnifiedOpen(intent)) pendingUnifiedOpen.value = true",
                "if (parseUnreadOpen(intent)) pendingUnreadOpen.value = true",
                "consumeOAuthRedirect()",
            ),
            functionBody(MAIN_ACTIVITY, "override fun onNewIntent(intent: Intent)"),
        )
    }

    /**
     * The consumption goes through `InboxViewModel.selectUnified()`, the very call the drawer's
     */
    @Test fun `the consumption calls selectUnified, waits for the list, and unwinds on the whole stack`() {
        assertEquals(
            "SternaApp must consume the widget's request by calling InboxViewModel.selectUnified() " +
                "— the drawer's own call, with everything it does beyond moving the selection. " +
                "The order must be CONSUMED and HELD when the list is not composed yet (the cold " +
                "start, bench C1) and only then delivered, through WidgetTapRelay's three arms: an " +
                "effect that merely returns on a null list is never re-run, and the tap opens " +
                "whatever view the app reopened on (#179) instead of All inboxes. And it must ASK " +
                "WidgetTapNavigation whether to pop, handing it the WHOLE back stack: " +
                "selectUnified() moves a list that is one destination below the reader, so without " +
                "the pop the user taps the counter, comes back to a message and sees nothing " +
                "change (#112) — while a pop decided on the top entry alone would take a composer " +
                "or a search screen buried underneath with it.",
            listOf(
                "LaunchedEffect(pendingUnifiedOpen, heldUnifiedOpen, listViewModel) {",
                "when (WidgetTapRelay.step(pendingUnifiedOpen, heldUnifiedOpen, listViewModel != null)) {",
                "WidgetTapStep.Ignore -> return@LaunchedEffect",
                "WidgetTapStep.Hold -> {",
                "onUnifiedOpenConsumed()",
                "heldUnifiedOpen = true",
                "}",
                "WidgetTapStep.Deliver -> {",
                "onUnifiedOpenConsumed()",
                "heldUnifiedOpen = false",
                "val list = listViewModel ?: return@LaunchedEffect",
                "list.selectUnified()",
                "unwindReaderToList()",
                "}",
                "}",
                "}",
            ),
            blockContaining(STERNA_APP, "list.selectUnified()", "LaunchedEffect(pendingUnifiedOpen, heldUnifiedOpen, listViewModel) {"),
        )
        assertEquals(
            "the unwind both orders share must ask WidgetTapNavigation with the whole stack.",
            listOf(
                "fun unwindReaderToList() {",
                "val stack = nav.currentBackStack.value.map { it.destination.route }",
                "if (WidgetTapNavigation.from(stack) == WidgetTapReturn.BackToList) {",
                "nav.popBackStack(\"inbox\", inclusive = false)",
                "}",
            ),
            functionBody(STERNA_APP, "fun unwindReaderToList()"),
        )
        assertEquals(
            "the held order must be remember, NEVER rememberSaveable: surviving a rotation, a " +
                "locale change or a font-size change, a held `true` re-navigates to All inboxes " +
                "over the screen the user is on — the same defect MainActivity's " +
                "savedInstanceState == null guard exists to prevent.",
            listOf("var heldUnifiedOpen by remember { mutableStateOf(false) }"),
            codeLines(STERNA_APP).filter { it.startsWith("var heldUnifiedOpen") },
        )
        assertEquals(
            "⛔ RAW LINES, indentation and all — and that is the whole point of this one. Every " +
                "other rule in this file trims, so nesting is invisible to them: wrapping the held " +
                "order and its effect in `if (listViewModel != null) {` restores bench C1 entire — " +
                "nothing is consumed, nothing is held, and a tap that starts the app dies exactly " +
                "as it did — while every trimmed rule above goes on agreeing with itself, the " +
                "extracted block being byte-for-byte the same. These two lines belong to " +
                "MainNavHost's own body, at its indentation, under no condition whatever.",
            listOf(
                "    var heldUnifiedOpen by remember { mutableStateOf(false) }",
                "    LaunchedEffect(pendingUnifiedOpen, heldUnifiedOpen, listViewModel) {",
            ),
            STERNA_APP.readLines().filter {
                it.trimStart().startsWith("var heldUnifiedOpen") ||
                    it.trimStart().startsWith("LaunchedEffect(pendingUnifiedOpen,")
            },
        )
        assertEquals(
            "the activity must hand its own flag down to SternaApp, and nothing else in this file " +
                "watches that line: the rules above pin the parsing, the strip and onNewIntent, " +
                "all of which stay perfectly intact around a flag that is never passed on. A " +
                "literal `false` here, or another payload's state, kills the widget's tap outright.",
            listOf("pendingUnifiedOpen = pendingUnifiedOpen.value,"),
            codeLines(MAIN_ACTIVITY).filter { it.startsWith("pendingUnifiedOpen = ") },
        )
    }

    @Test fun `the unread shortcut's order takes the widget's path to selectUnreadFromShortcut`() {
        assertEquals(
            listOf(
                "private fun stripUnreadOpenPayload() {",
                "val i = intent ?: return",
                "i.removeExtra(EXTRA_OPEN_UNREAD)",
            ),
            functionBody(MAIN_ACTIVITY, "private fun stripUnreadOpenPayload()"),
        )
        assertEquals(
            listOf("private fun parseUnreadOpen(intent: Intent?): Boolean =", "intent?.getBooleanExtra(EXTRA_OPEN_UNREAD, false) == true"),
            codeLines(MAIN_ACTIVITY).let { lines ->
                val at = lines.indexOfFirst { it.startsWith("private fun parseUnreadOpen(") }
                if (at < 0) emptyList() else lines.subList(at, at + 2)
            },
        )
        assertEquals(
            listOf(
                "onUnreadOpenConsumed = {",
                "pendingUnreadOpen.value = false",
                "stripUnreadOpenPayload()",
                "},",
            ),
            blockContaining(MAIN_ACTIVITY, "onUnreadOpenConsumed = {", "onUnreadOpenConsumed = {"),
        )
        assertEquals(
            listOf("pendingUnreadOpen = pendingUnreadOpen.value,"),
            codeLines(MAIN_ACTIVITY).filter { it.startsWith("pendingUnreadOpen = ") },
        )
        assertEquals(
            "SternaApp must hand the order down to MainNavHost, or it is never delivered.",
            listOf("pendingUnreadOpen = pendingUnreadOpen,", "onUnreadOpenConsumed = onUnreadOpenConsumed,"),
            codeLines(STERNA_APP).filter { it.startsWith("pendingUnreadOpen = ") || it.startsWith("onUnreadOpenConsumed = ") },
        )
        assertEquals(
            listOf(
                "LaunchedEffect(pendingUnreadOpen, heldUnreadOpen, listViewModel) {",
                "when (WidgetTapRelay.step(pendingUnreadOpen, heldUnreadOpen, listViewModel != null)) {",
                "WidgetTapStep.Ignore -> return@LaunchedEffect",
                "WidgetTapStep.Hold -> {",
                "onUnreadOpenConsumed()",
                "heldUnreadOpen = true",
                "}",
                "WidgetTapStep.Deliver -> {",
                "onUnreadOpenConsumed()",
                "heldUnreadOpen = false",
                "val list = listViewModel ?: return@LaunchedEffect",
                "list.selectUnreadFromShortcut()",
                "unwindReaderToList()",
                "}",
                "}",
                "}",
            ),
            blockContaining(STERNA_APP, "LaunchedEffect(pendingUnreadOpen, heldUnreadOpen, listViewModel) {", "LaunchedEffect(pendingUnreadOpen, heldUnreadOpen, listViewModel) {"),
        )
        assertEquals(
            "the drawer's entry keeps selectUnread(): its reach follows the view it replaces, the " +
                "shortcut's does not (#266).",
            listOf("list.selectUnreadFromShortcut()"),
            codeLines(STERNA_APP).filter { it.contains("selectUnread") },
        )
        assertEquals(
            "raw lines, for the reason given in the widget's rule above: remember, never " +
                "rememberSaveable, in MainNavHost's own body under no condition.",
            listOf(
                "    var heldUnreadOpen by remember { mutableStateOf(false) }",
                "    LaunchedEffect(pendingUnreadOpen, heldUnreadOpen, listViewModel) {",
            ),
            STERNA_APP.readLines().filter {
                it.trimStart().startsWith("var heldUnreadOpen") ||
                    it.trimStart().startsWith("LaunchedEffect(pendingUnreadOpen,")
            },
        )
    }

    /**
     * The rule of [app.sterna.ui.WidgetTapNavigation] names two routes, `inbox` and `message`,
     * and its own test runs it against strings: nothing in that pair notices the day the graph
     * renames either one, so the literals are pinned here against the graph.
     */
    @Test fun `the popped destination and the floor are still the graph's own routes`() {
        assertEquals(
            "SternaApp's reader route was renamed. WidgetTapNavigation matches on the route name " +
                "'message', so a rename silently disables the unwind and no behaviour test sees it.",
            listOf("route = \"message/{emailId}?accountId={accountId}&index={index}&src={src}&thread={thread}&list={list}\","),
            codeLines(STERNA_APP).filter { it.startsWith("route = \"message") },
        )
        assertEquals(
            "the rule's route name must be the bare 'message' the declaration above starts with.",
            listOf("private const val MESSAGE = \"message\""),
            codeLines(TAP_NAVIGATION).filter { it.startsWith("private const val MESSAGE") },
        )
        assertEquals(
            "the graph's start destination is the list the widget unwinds to. Renamed here alone, " +
                "the rule stops finding a floor in the stack and the pop never fires again.",
            listOf("startDestination = \"inbox\","),
            codeLines(STERNA_APP).filter { it.startsWith("startDestination =") },
        )
        assertEquals(
            "the rule's floor must be that same word — and the same one the caller pops back to.",
            listOf("private const val INBOX = \"inbox\""),
            codeLines(TAP_NAVIGATION).filter { it.startsWith("private const val INBOX") },
        )
    }

    /**
     * The lines of [file], comments dropped whole and trailing `//` cut, blanks removed. Without
     * this every rule above would also match the prose written to explain it.
     */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
    }

    /** [line] up to its `//` comment, ignoring a `//` inside a string literal. */
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

    /**
     * The code lines of the function opening with [signature], its own closing brace excluded.
     */
    private fun functionBody(file: File, signature: String): List<String> {
        val lines = codeLines(file)
        val start = lines.indexOfFirst { it.startsWith(signature) }
        check(start >= 0) { "${file.name} no longer declares `$signature` — was it renamed?" }
        val body = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            val next = depth + line.count { it == '{' } - line.count { it == '}' }
            if (opened && next == 0) return body
            body += line
            depth = next
            if (depth > 0) opened = true
        }
        error("`$signature` in ${file.name} never closes — the slice would be the rest of the file")
    }

    /**
     * The whole brace-balanced block that opens with [opener] and contains [marker].
     */
    private fun blockContaining(file: File, marker: String, opener: String): List<String> {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { marker in it }
        if (at < 0) return emptyList()
        val start = (at downTo 0).firstOrNull { lines[it] == opener }
            ?: error("no `$opener` above `$marker` in ${file.name}")
        val block = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            block += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth == 0) return block
        }
        error("`$opener` in ${file.name} never closes")
    }

    private companion object {
        const val ANCHOR = "app/src/main/kotlin/app/sterna/MainActivity.kt"

        /** Repo root, walked up from the module's working directory. */
        val ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, ANCHOR).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val MAIN_ACTIVITY: File by lazy { File(ROOT, ANCHOR) }
        val STERNA_APP: File by lazy { File(ROOT, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt") }
        val WIDGET_DRAW: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/widget/UnreadWidgetDraw.kt")
        }
        val WIDGET_KIND: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/widget/UnreadWidgetKind.kt")
        }
        val TAP_NAVIGATION: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/ui/WidgetTapNavigation.kt")
        }
    }
}
