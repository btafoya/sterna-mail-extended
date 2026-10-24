package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, not a behaviour test, it reads a source file as text, same instrument and
 * disclaimer as every source lint in this tree: it proves nothing about behaviour.
 */
class ScheduleMenuWiringTest {

    @Test fun `the tap decides again instead of scheduling what composition captured`() {
        assertTrue(
            "the preset tap must take the decision again, at the tap: '$DECISION'. Without it the " +
                "instant drawn when the menu opened is what gets scheduled, and once it has lapsed " +
                "enqueue coerces the delay to zero — the message is sent, irreversibly. Both the " +
                "drawn instant and the fresh clock are arguments: dropping either one loses a case " +
                "PresetMillisAtTapTest pins.",
            DECISION in lines(COMPOSE_SCREEN),
        )
    }

    @Test fun `nothing stands between the tap and the decision`() {
        val all = lines(COMPOSE_SCREEN)
        val list = all.indexOf(LIST_FROM_STAMP)
        assertTrue("the preset loop is missing entirely — see the drawing test below", list >= 0)
        val relative = all.drop(list).indexOf(TAP_HEAD)
        assertTrue("the preset item's '$TAP_HEAD' is missing", relative >= 0)
        assertEquals(
            "the FIRST line inside the preset item's onClick must be '$DECISION'. Anything run " +
                "before it is outside every rule below — `scheduleMenu = false` slipped in here " +
                "closes the menu on EVERY tap, refusals included: nothing is scheduled, nothing is " +
                "said, and the user walks away believing the message is queued.",
            DECISION,
            all.getOrNull(list + relative + 1),
        )
    }

    @Test fun `the refusal guard sits immediately on the decision`() {
        val all = lines(COMPOSE_SCREEN)
        val decision = all.indexOf(DECISION)
        assertTrue("the decision is missing entirely — see the test above", decision >= 0)
        assertEquals(
            "the line after '$DECISION' must be '$GUARD'. An instant that is decided and then used " +
                "unconditionally is the same defect with an extra line: null means the entry has " +
                "lapsed, and nothing below may run.",
            GUARD,
            all[decision + 1],
        )
    }

    @Test fun `closing and scheduling both live inside the guard, and nothing else does`() {
        assertEquals(
            "everything between '$GUARD' and '$ELSE' must be exactly the schedule path, in this " +
                "order. Moving the menu close out takes the choice off screen having scheduled " +
                "nothing. And no confirmation of its own here: the Undo snackbar is opened by " +
                "`scheduleSend` once the row is written, so a toast here would be a second, " +
                "earlier sentence about a write that may still fail.",
            listOf(
                "scheduleMenu = false",
                PRESET_SCHEDULE,
            ),
            guardedBlock(),
        )
    }

    /**
     * The other path, on the free picker three hundred lines above, the same rule, and it has no
     * `else` branch to bound it, so it is read as the lines that follow the schedule.
     */
    @Test fun `the hand-picked schedule is the last thing its tap does`() {
        val all = lines(COMPOSE_SCREEN)
        val at = all.indexOf(PICKER_SCHEDULE)
        assertTrue("the hand-picked schedule must read '$PICKER_SCHEDULE'", at >= 0)
        assertEquals(
            "the picker's `onPicked` must end on the schedule: close the clock, remember the " +
                "hour, schedule, close the lambda. A toast after it announces a write that has not " +
                "happened yet; the Undo snackbar is opened by the view model once it has.",
            listOf(
                "onPicked = { millis, hour, minute ->",
                "viewModel.clearScheduleTime()",
                "viewModel.rememberScheduleTime(hour, minute)",
                PICKER_SCHEDULE,
                "},",
            ),
            all.subList((at - 3).coerceAtLeast(0), (at + 2).coerceAtMost(all.size)),
        )
    }

    @Test fun `the composer toasts nothing but the view model's notices`() {
        assertEquals(
            "the only toast left in ComposeScreen is the notices collector: a scheduled send is " +
                "confirmed by the Undo snackbar on the list, and a toast beside it says the same " +
                "thing with nothing to tap. Toast lines were:",
            listOf("viewModel.notices.collect { res -> Toast.makeText(context, res, Toast.LENGTH_LONG).show() }"),
            lines(COMPOSE_SCREEN).filter { "Toast." in it },
        )
    }

    @Test fun `the refused tap re-stamps the menu instead of closing it`() {
        assertEquals(
            "the else branch must be exactly 'menuOpenedAt = System.currentTimeMillis()'. The wall " +
                "clock is not snapshot state, so a refused tap recomposes nothing on its own: " +
                "without the re-stamp the lapsed entry stays on screen, tappable and silent. Its " +
                "disappearance IS the message — deliberately no new string, no ten translations " +
                "for a window a few seconds wide. And closing the menu here would hide the choice.",
            listOf("menuOpenedAt = System.currentTimeMillis()"),
            elseBlock(),
        )
    }

    @Test fun `the menu is drawn from the stamp taken when it opened`() {
        val all = lines(COMPOSE_SCREEN)
        assertTrue(
            "the composer must hold the instant the list was drawn for: '$STAMP_STATE'.",
            STAMP_STATE in all,
        )
        assertTrue(
            "opening the menu must stamp that instant: '$STAMP_ON_OPEN'. Left at 0 the list is " +
                "built for 1 Jan 1970, where all four entries are offered and none ever lapses: " +
                "the menu never changes, and at the tap the three absolute entries are refused " +
                "forever (their drawn instant is in 1970) — taps that do nothing and say nothing.",
            STAMP_ON_OPEN in all,
        )
        assertTrue(
            "the list must be built from the stamp and carry BOTH the preset and the instant it " +
                "was drawn for out to the tap: '$LIST_FROM_STAMP'. Dropping the third field (`_`) " +
                "leaves the tap no choice but to re-compute the absolute entries, which moves them " +
                "a whole day across midnight.",
            LIST_FROM_STAMP in all,
        )
    }

    @Test fun `the label table pairs each preset with its own string`() {
        // The table itself is `schedulePresetLabel`, run by SchedulePresetLabelTest; this pins what
        // it is handed. Hard-wire the Triple's first field, or hand the label another instant, and
        // the menu names one hour while the tap schedules another.
        assertEquals(
            "schedulePresets' label table and its Triple must be exactly these lines, in order",
            LABEL_TABLE,
            presetsBody().dropWhile { it != LABEL_TABLE.first() },
        )
    }

    @Test fun `the list is built for the instant handed in, not for another one`() {
        assertEquals(
            "schedulePresets' first line must be '$PRESETS_CALL'. Handed anything else — 0L, a " +
                "literal — the list is drawn for that instant instead of for the menu's stamp: at " +
                "the epoch all four entries are offered forever, the lapsed one never disappears, " +
                "and re-stamping after a refusal redraws exactly the same menu.",
            PRESETS_CALL,
            presetsBody().firstOrNull(),
        )
    }

    @Test fun `schedulePresets takes the clock and no longer reads it`() {
        val body = presetsBody()
        assertEquals(
            "schedulePresets must not read the clock in its own body; found: " +
                "${body.filter { CLOCK in it }}. Reading it there ties the drawn list to " +
                "recomposition, which a tap does not cause — the lapsed entry would never go away.",
            emptyList<String>(),
            body.filter { CLOCK in it },
        )
    }

    @Test fun `no third schedule call hides in the composer`() {
        // Exactly the two production call sites, each fed by its own decision taken at the tap: the
        // free picker's `millis` (pickedScheduleMillis, asked again inside the tap) and the preset
        // menu's `sendAt`. A third one added with a captured instant would reopen the defect while
        // every line pinned above stays present.
        val calls = lines(COMPOSE_SCREEN).filter { "viewModel.scheduleSend(" in it }
        assertEquals(
            "ComposeScreen must schedule from exactly two places, both deciding at the tap. Found: $calls",
            listOf(PICKER_SCHEDULE, PRESET_SCHEDULE).sorted(),
            calls.sorted(),
        )
    }

    @Test fun `the overflow entry only asks the decision, and is drawn in every mode`() {
        val all = lines(COMPOSE_SCREEN)
        val entry = all.indexOf(SCHEDULE_ENTRY_TEXT)
        assertTrue("the overflow's schedule entry must read '$SCHEDULE_ENTRY_TEXT'", entry >= 0)
        assertEquals(
            "the entry stays lit without a recipient and under OpenPGP, so a tap can say why it is " +
                "refused; greyed, it says nothing",
            listOf(SCHEDULE_ENTRY_TEXT, QuoteInClearWiringTest.SCHEDULE_TAP, "enabled = !sending,", ")"),
            all.subList(entry, entry + 4),
        )
        val block = all.indexOf("if (draftSaveAllowed(pgpMode)) {")
        assertTrue("the draft entry's block is missing", block >= 0)
        assertTrue(
            "the schedule entry must sit outside 'if (draftSaveAllowed(pgpMode))': inside, an " +
                "encrypted message has no entry at all and never hears why",
            entry > closingOf(all, block),
        )
    }

    @Test fun `the view model refuses at its choke point with the same decision`() {
        val vm = lines(COMPOSE_VIEW_MODEL)
        val head = vm.indexOf("fun mayScheduleOrSay(to: String, cc: String, bcc: String): Boolean {")
        assertTrue("ComposeViewModel.mayScheduleOrSay is missing", head >= 0)
        assertEquals(
            listOf(
                "val refusal = decryptedQuoteRefusal(decryptedQuote.held.value, _pgpMode.value) ?: scheduleRefusal(to, cc, bcc, _pgpMode.value) ?: return true",
                "_notices.tryEmit(refusal)",
                "return false",
                "}",
            ),
            vm.subList(head + 1, head + 5),
        )
        val body = scheduleSendBody()
        assertEquals(
            "scheduleSend must refuse first, itself: the entry is a convenience, and a signed " +
                "message let through here goes out unsigned with nothing said",
            "if (!mayScheduleOrSay(to, cc, bcc)) return false",
            body.firstOrNull(),
        )
    }

    @Test fun `a failed schedule says so, not that a send failed`() {
        val body = scheduleSendBody()
        assertEquals(
            "both failures of the schedule coroutine must be marked whileScheduling",
            listOf(
                "_state.value = ComposeState.Error(",
                "getApplication<Application>().getString(R.string.compose_schedule_attachment_unavailable, e.name ?: \"?\"),",
                "whileScheduling = true,",
                ")",
                "} catch (t: Throwable) {",
                "_state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName, whileScheduling = true)",
            ),
            body.dropWhile { it != "} catch (e: ScheduledAttachmentFetchFailed) {" }.drop(1).take(6),
        )
        assertEquals(
            listOf("text = stringResource(composeErrorWording(it), it.message),"),
            lines(COMPOSE_SCREEN).filter { "it.message" in it && "stringResource(" in it },
        )
    }

    @Test fun `an immediate send's failure is never worded as a schedule's`() {
        val vm = lines(COMPOSE_VIEW_MODEL)
        val head = vm.indexOf("private fun sendInternal(args: SendArgs, interactionResult: Intent?) {")
        assertTrue("ComposeViewModel.sendInternal is missing", head >= 0)
        val body = vm.drop(head + 1).takeWhile { !it.startsWith("fun mayScheduleOrSay(") }
        assertEquals(
            "a message that failed to go now, worded \"Couldn't schedule\", reads as nothing having been tried",
            listOf("_state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)"),
            body.filter { "ComposeState.Error(" in it },
        )
    }

    /** The code lines of `scheduleSend`'s body, up to the next declaration. */
    private fun scheduleSendBody(): List<String> {
        val vm = lines(COMPOSE_VIEW_MODEL)
        val head = vm.indexOf("fun scheduleSend(")
        assertTrue("ComposeViewModel.scheduleSend is missing", head >= 0)
        val open = vm.drop(head).indexOf("): Boolean {")
        return vm.drop(head + open + 1).takeWhile { !it.startsWith("private suspend fun bodiesForSend(") }
    }

    /** Index of the line that closes the block opened on [open], braces counted outside strings. */
    private fun closingOf(all: List<String>, open: Int): Int {
        var depth = 0
        for (i in open until all.size) {
            val line = all[i].replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "")
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth == 0) return i
        }
        return all.size
    }

    // -- locating the pieces ---------------------------------------------------------------------

    /** `schedulePresets`' body: it is the last declaration in the file, so it runs to the end. */
    private fun presetsBody(): List<String> {
        val all = lines(COMPOSE_SCREEN)
        val signature = all.indexOf(PRESETS_SIGNATURE)
        assertTrue("the label resolver must take the instant: '$PRESETS_SIGNATURE'", signature >= 0)
        return all.drop(signature + 1)
    }

    /** The code lines strictly between [guard] and [else], i.e. the path that schedules. */
    private fun guardedBlock(): List<String> = tapBranches().first

    /** The code lines strictly between [else] and the brace that closes it. */
    private fun elseBlock(): List<String> = tapBranches().second

    private fun tapBranches(): Pair<List<String>, List<String>> {
        val all = lines(COMPOSE_SCREEN)
        val decision = all.indexOf(DECISION)
        if (decision < 0 || all.getOrNull(decision + 1) != GUARD) {
            // Reported by the two tests above; here the branches are simply empty, so the equality
            // fails on content rather than on an index out of bounds.
            return emptyList<String>() to emptyList()
        }
        val rest = all.drop(decision + 2)
        val elseAt = rest.indexOf(ELSE)
        if (elseAt < 0) return rest.takeWhile { it != "}" } to emptyList()
        val after = rest.drop(elseAt + 1)
        return rest.take(elseAt) to after.takeWhile { it != "}" }
    }

    // -- reading the sources ---------------------------------------------------------------------

    /** [file]'s code as trimmed whole lines, comments cut. Rules compare with equality, never
     *  contains: a mutation that lengthens a line must change the line. */
    private fun lines(file: File): List<String> = code(file).lines().map { it.trim() }

    /** [file]'s code as one string, comments cut, same reader as [OutgoingDateWiringTest]. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

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
        private const val CLOCK = "System.currentTimeMillis()"
        private const val TAP_HEAD = "onClick = {"
        private const val DECISION =
            "val sendAt = presetMillisAtTap(preset, drawnAt, System.currentTimeMillis(), java.time.ZoneId.systemDefault())"
        private const val GUARD = "if (sendAt != null) {"

        private const val PICKER_SCHEDULE =
            "viewModel.scheduleSend(to, cc, bcc, subject.text, rich, millis, requestReceipt)"
        private const val PRESET_SCHEDULE =
            "viewModel.scheduleSend(to, cc, bcc, subject.text, rich, sendAt, requestReceipt)"
        private const val ELSE = "} else {"
        private const val STAMP_STATE = "var menuOpenedAt by remember { mutableLongStateOf(0L) }"
        private const val STAMP_ON_OPEN = QuoteInClearWiringTest.OPEN_PRESETS
        private const val LIST_FROM_STAMP =
            "schedulePresets(context, menuOpenedAt, lastScheduleMinutes).forEach { (preset, label, drawnAt) ->"
        private const val PRESETS_SIGNATURE =
            "private fun schedulePresets(context: android.content.Context, nowMillis: Long, lastScheduleMinutes: Int?): " +
                "List<Triple<SchedulePreset, String, Long>> ="
        private const val PRESETS_CALL = "schedulePresetsAt(nowMillis, java.time.ZoneId.systemDefault())"

        /** From the `when` that resolves the labels to the end of the file, whole lines. */
        private val LABEL_TABLE = listOf(
            "val label = schedulePresetLabel(",
            "preset,",
            "millis,",
            "{ android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)) },",
            "{ DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH) },",
            "{ res, arg -> if (arg == null) context.getString(res) else context.getString(res, arg) },",
            ")",
            "Triple(preset, label, millis)",
            "}",
        )

        private const val SCHEDULE_ENTRY_TEXT = "text = { Text(stringResource(R.string.compose_schedule_send)) },"

        private const val COMPOSE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt") }
    }
}
