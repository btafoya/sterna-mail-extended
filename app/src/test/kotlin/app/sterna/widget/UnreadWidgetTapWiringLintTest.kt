package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 * [UnreadWidgetPushWiringLintTest] next door. The decisions themselves are EXECUTED elsewhere:
 * [UnreadWidgetTapTest] runs `UnreadWidgetTap.of`, [app.sterna.ui.WidgetTapRelayTest] runs
 * `WidgetTapRelay.step` frame by frame, and [app.sterna.ui.WidgetTapNavigationTest] runs
 * `WidgetTapNavigation.from` against real stacks. This file holds only what none of them can see,
 * because it cannot be built in this suite — `PendingIntent`, `Intent`, `MainActivity` and a
 * Compose `LaunchedEffect` all want Android, and there is no Robolectric here. What it pins is the
 * WIRING: that the shipped effect calls those rules, with those arguments, in that order.
 *
 * It REPLACES the guard that used to live next door ("nothing in the widget package sets a
 * PendingIntent yet"), which was explicitly temporary: this is the pane that adds one, so the rule
 * becomes what the shipped tap must be rather than its absence.
 *
 * ⛔ Whole lines, never a `contains`: a substring rule is satisfied by every mutation that LENGTHENS
 * the line. The price is that reformatting any of these calls reddens this test — loud, and one
 * line to fix.
 */
class UnreadWidgetTapWiringLintTest {

    /**
     * ⛔ The `Intent` behind the widget's `PendingIntent` carries an ACTION of its own, and that is
     * not a nicety.
     *
     * A `PendingIntent`'s identity is `(requestCode, Intent as compared by filterEquals, flags)`,
     * and `filterEquals` IGNORES extras. Every new-mail notification is built as
     * `Intent(context, MainActivity::class.java)` with no action and no data, so they are all
     * filterEquals-identical to one another and are told apart by their request code alone — which
     * is `Notifications.childId(accountId, emailId)`, a `"$accountId $emailId".hashCode()` spread
     * over the whole `Int` range. No number can be reserved against that.
     *
     * The action is what makes the widget's intent diverge whatever the number collides: without
     * it, the first `childId` that lands on the widget's request code has its extras REWRITTEN by
     * `FLAG_UPDATE_CURRENT`, and the user taps the banner announcing a message and gets the unified
     * inbox instead of the message. The constant request code is a belt on top of that, not the
     * mechanism.
     */
    @Test fun `the widget's tap intent carries its own action, so it cannot collide with a notification's`() {
        assertEquals(
            "the widget's PendingIntent must be built from an Intent that sets its own action. " +
                "PendingIntent identity ignores extras, notification intents carry no action at " +
                "all, and their request codes cover every Int — so the action is the ONLY thing " +
                "keeping FLAG_UPDATE_CURRENT from rewriting a notification's target (#112).",
            listOf(
                "private fun tapIntent(context: Context, target: WidgetTapTarget): PendingIntent {",
                "val intent = Intent(context, MainActivity::class.java)",
                ".setAction(ACTION_WIDGET_TAP)",
                ".addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)",
                "if (target == WidgetTapTarget.UnifiedInbox) {",
                "intent.putExtra(MainActivity.EXTRA_OPEN_UNIFIED, true)",
                "}",
                "return PendingIntent.getActivity(",
                "context,",
                "TAP_REQUEST_CODE,",
                "intent,",
                "PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,",
                ")",
            ),
            functionBody(WIDGET_DRAW, "private fun tapIntent("),
        )
        assertEquals(
            "the action must be a real, app-owned string. Emptied or nulled it stops separating " +
                "the widget's intent from the notifications'.",
            listOf("private const val ACTION_WIDGET_TAP = \"app.sterna.widget.TAP\""),
            codeLines(WIDGET_DRAW).filter { "ACTION_WIDGET_TAP =" in it },
        )
    }

    /**
     * The tap target is SET on the shared `RemoteViews`, once, where the cell is drawn — the same
     * reason the draw itself has one home: a second `RemoteViews` built elsewhere would be a cell
     * that shows the number and does nothing when touched.
     */
    @Test fun `the drawn cell is given that tap target`() {
        assertEquals(
            "render() must attach the tap intent to the cell's root view. Without it the widget " +
                "draws the number and swallows every touch (#112).",
            listOf("setOnClickPendingIntent(R.id.widget_unread_root, tapIntent(context, target))"),
            codeLines(WIDGET_DRAW).filter { it.startsWith("setOnClickPendingIntent(") },
        )
    }

    /**
     * ⛔ GUARD 1, and it is the reason the three existing extras are parsed inside this `if`.
     *
     * The activity is `singleTask`: the intent that opened it stays its intent FOR GOOD. Parsed
     * outside this block, the widget's order is re-read on every recreation — rotation, theme,
     * locale, font size, split screen — and each one re-navigates to the unified inbox on top of
     * whatever the user was reading, piling up back-stack entries they then have to unwind.
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
                "consumeOAuthRedirect()",
                "}",
            ),
            blockContaining(MAIN_ACTIVITY, "pendingUnifiedOpen.value = parseUnifiedOpen(intent)", "if (savedInstanceState == null) {"),
        )
    }

    /**
     * ⛔ GUARD 2: the one-shot strip, belt to the guard above's braces. Whatever re-reads the
     * retained intent later, it must no longer carry an order already carried out.
     *
     * And it has its OWN function on purpose, like `stripEmailOpenPayload` and `stripMailtoPayload`
     * before it: consuming one payload must never cancel another that is genuinely pending. ⛔ Not
     * to be folded into the email-open strip.
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
     * the new intent to `onNewIntent`, and `onCreate` never runs again. Read only there, the extra
     * would work exactly once — on a cold start — and do nothing for the rest of the day.
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
                "consumeOAuthRedirect()",
            ),
            functionBody(MAIN_ACTIVITY, "override fun onNewIntent(intent: Intent)"),
        )
    }

    /**
     * ⛔ The consumption goes through `InboxViewModel.selectUnified()`, the very call the drawer's
     * own entry makes — never a hand-rolled equivalent. Not for the push flag:
     * `PushController.unifiedInboxVisible` is set by a collector on `selection` in
     * `InboxViewModel`'s init, so any path that moves the selection sets it. For everything else
     * `selectUnified()` does around that assignment — collapse the expanded threads, re-derive the
     * unified inbox scopes from the account store, rewrite the header meta to the "All inboxes"
     * label and total, and `refresh()` every account. A hand-rolled "just move the selection"
     * compiles and leaves the header naming the previous folder over a list nothing re-fetched.
     *
     * ⛔ AND THE ORDER IS TAKEN BEFORE THE LIST IS THERE, which is the half this pin gained after
     * bench cell C1 (2026-08-17). `listViewModel` is looked up imperatively, so on a COLD start it
     * is null in the composition that receives the tap; the effect that returned there returned for
     * good, and the activity's flag stayed `true`, so every later tap was a `true → true`
     * assignment that recomposed nothing. The three arms of [app.sterna.ui.WidgetTapRelay] are the
     * fix — `Hold` consumes and keeps, `Deliver` consumes and acts — and both arms must call
     * `onUnifiedOpenConsumed()`: a `Hold` that keeps the order without consuming it never brings
     * the composition it is waiting for.
     *
     * ⛔ AND `heldUnifiedOpen` IS `remember`, NOT `rememberSaveable` — pinned below as a whole line.
     * Saved across a recreation, a held `true` re-opens All inboxes over what the user is reading on
     * the next rotation, locale or font-size change, which is the defect
     * `savedInstanceState == null` guards against, arrived at from inside.
     *
     * ⛔ AND THE RULE IS HANDED THE WHOLE STACK, which is the third part of this pin.
     * `popBackStack("inbox", inclusive = false)` drops EVERY entry above the list, so a verdict
     * taken on `currentBackStackEntry` alone answers about the top screen and acts on all of them:
     * `inbox → search → message` loses the search results, `inbox → compose → message` loses the
     * draft. `nav.currentBackStack.value` is the list, oldest first, and it is not optional.
     */
    @Test fun `the consumption calls selectUnified, waits for the list, and unwinds on the whole stack`() {
        assertEquals(
            "SternaApp must consume the widget's request by calling InboxViewModel.selectUnified() " +
                "— the drawer's own call, with everything it does beyond moving the selection. " +
                "The order must be CONSUMED and HELD when the list is not composed yet (the cold " +
                "start, bench C1) and only then delivered, through WidgetTapRelay's three arms: an " +
                "effect that merely returns on a null list is never re-run, and the tap opens the " +
                "current account's Inbox instead of All inboxes. And it must ASK " +
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
                "val stack = nav.currentBackStack.value.map { it.destination.route }",
                "if (WidgetTapNavigation.from(stack) == WidgetTapReturn.BackToList) {",
                "nav.popBackStack(\"inbox\", inclusive = false)",
                "}",
                "}",
                "}",
                "}",
            ),
            blockContaining(STERNA_APP, "list.selectUnified()", "LaunchedEffect(pendingUnifiedOpen, heldUnifiedOpen, listViewModel) {"),
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

    /**
     * ⛔ The rule of [app.sterna.ui.WidgetTapNavigation] names TWO routes, `inbox` and `message`,
     * and its own test runs it against strings. Nothing in that pair notices the day the graph
     * renames either one: the rule would quietly stop matching, the pop would quietly stop
     * happening, and every test would stay green. Worse for `inbox` — the rule's floor and the
     * caller's `popBackStack("inbox", …)` are two separate literals, and if only one were renamed
     * the rule would answer about a stack it no longer recognises while the pop still fired.
     * This is the one thing only the source can say: the words must still be the same words.
     */
    @Test fun `the popped destination and the floor are still the graph's own routes`() {
        assertEquals(
            "SternaApp's reader route was renamed. WidgetTapNavigation matches on the route name " +
                "'message', so a rename silently disables the unwind and no behaviour test sees it.",
            listOf("route = \"message/{emailId}?accountId={accountId}&index={index}&src={src}&thread={thread}\","),
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
     * Fails loudly when the signature is gone: a rename must redden the rule rather than satisfy it
     * against an empty list.
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
     *
     * Deliberately returns an empty list when [marker] is nowhere in the file: dropping the block
     * is the mutation the caller exists to catch, and an assertion that shows the expected lines
     * against `[]` says more than an exception would.
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
        val TAP_NAVIGATION: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/ui/WidgetTapNavigation.kt")
        }
    }
}
