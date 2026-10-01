package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: reads `ComposeScreen.kt`, `PickInstantDialogs.kt`, `ComposeViewModel.kt` as text.
 */
class DialogFlagsSurviveRotationTest {

    @Test fun `every dialog flag is a ViewModel flow, and each starts closed`() {
        DIALOGS.forEach { d ->
            assertPinned(
                VIEW_MODEL,
                "private val ${d.backing} = MutableStateFlow(false)",
                "${d.what}: this is the line that carries the INITIAL value, and it must be " +
                    "`false`. A dialog that comes up on its own is a question — or a delete door — " +
                    "offered the moment the composer opens.",
            )
            assertPinned(
                VIEW_MODEL,
                "val ${d.flow}: StateFlow<Boolean> = ${d.backing}.asStateFlow()",
                "${d.what}: the flag has to be READABLE from the screen as a flow, in the same " +
                    "shape as the ~20 flows beside it, or the screen keeps a copy of its own and " +
                    "the rotation defect is back.",
            )
        }
    }

    @Test fun `the screen reads those flows instead of owning the flags`() {
        DIALOGS.forEach { d ->
            assertPinned(
                SCREEN,
                "val ${d.local} by viewModel.${d.flow}.collectAsStateWithLifecycle()",
                "${d.what}: the composer must READ the flag from the ViewModel, which survives the " +
                    "rotation that recreates MainActivity. Owning it here is the measured defect: " +
                    "the dialog disappears and the gesture has to be made again. The local name " +
                    "`${d.local}` is kept deliberately — other lints pin lines that name it.",
            )
        }
    }

    @Test fun `each door does what its name says, and they are not swapped`() {
        DIALOGS.forEach { d ->
            assertBody(
                VIEW_MODEL, "fun ${d.ask}() {", "${d.backing}.value = true",
                "${d.what}: the ASKING door must raise the flag. If it lowered it — or if the two " +
                    "bodies were swapped — the gesture that opens this dialog would do nothing at " +
                    "all, and either the question would never be asked or the dialog could never " +
                    "be dismissed.",
            )
            assertBody(
                VIEW_MODEL, "fun ${d.clear}() {", "${d.backing}.value = false",
                "${d.what}: the CLOSING door must lower the flag. If it raised it, the dialog " +
                    "could not be dismissed: Cancel, a tap outside and the confirmed action would " +
                    "all leave it redrawn on itself, with no way back to the message.",
            )
        }
    }

    @Test fun `only those two doors ever write each flag`() {
        DIALOGS.forEach { d ->
            // Every line naming the backing flow, not only `.value =`: an `update { false }`, a
            // `tryEmit(false)` or an alias would all be writes this rule must see too.
            val writers = codeLines(VIEW_MODEL).filter { d.backing in it }
            assertEquals(
                "${d.what}: ⛔ exactly FOUR lines of $VIEW_MODEL may so much as NAME " +
                    "`${d.backing}` — its declaration, its public flow, and the bodies of the two " +
                    "doors, in this order. Any third writer is a way for the " +
                    "flag to fall back behind the screen's back, and the measured defect returns " +
                    "with this suite still green. The one that nearly happened: a reset placed in " +
                    "`prepare()`, which `ComposeScreen` calls from a LaunchedEffect on EVERY " +
                    "activity recreation — put before `if (prepared) return` it runs on every " +
                    "rotation, and the dialog vanishes without a word, exactly as it did on " +
                    "2026-08-15. Writers found:",
                listOf(
                    "private val ${d.backing} = MutableStateFlow(false)",
                    "val ${d.flow}: StateFlow<Boolean> = ${d.backing}.asStateFlow()",
                    "${d.backing}.value = true",
                    "${d.backing}.value = false",
                ),
                writers,
            )
        }
    }

    @Test fun `nothing may own a dialog flag in remember or rememberSaveable again`() {
        val names = DIALOGS.flatMap { listOf(it.local, it.flow) }.toSet()
        val offenders = codeLines(SCREEN).filter { line ->
            names.any { it in line } && REMEMBER.containsMatchIn(line)
        }
        assertEquals(
            "⛔ no line of $SCREEN may declare a dialog flag with `remember` OR with " +
                "`rememberSaveable`.\n" +
                "  - `remember` is the measured defect: a rotation recreates MainActivity (no " +
                "`configChanges` in the manifest), the composition restarts, and the flag falls " +
                "back to false — the dialog vanishes without a word.\n" +
                "  - `rememberSaveable` is REFUSED too, and it is the trap of this fix: it also " +
                "survives PROCESS DEATH, which the target of these dialogs does not. After a kill " +
                "the ViewModel's editingDraft/editingLocalDraftId are gone and the body is still " +
                "being restored, so a restored flag would redraw the dialog over a loading or " +
                "dead-end screen with the wrong body, and the delete dialog's Delete would reach " +
                "the branch where takeEditingDraft() returns null: nothing happens, and nothing " +
                "is said.\n" +
                "A flag belongs where what it acts on lives — the ViewModel, which survives a " +
                "configuration change and dies with its navigation entry. What the user TYPED is " +
                "the other case and stays `rememberSaveable`. Offending lines:",
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `the screen never writes a flag itself, and exactly one gesture asks`() {
        DIALOGS.forEach { d ->
            val code = codeLines(SCREEN)
            val assigns = code.filter { Regex("""\b${d.local}\s*=[^=]""").containsMatchIn(it) }
            assertEquals(
                "${d.what}: ⛔ no line of $SCREEN may ASSIGN `${d.local}` — it is now a value read " +
                    "from the ViewModel, and a local write is either dead code or the rotation " +
                    "defect put back one line at a time. Lines found:",
                emptyList<String>(), assigns,
            )
            val asks = code.filter { "viewModel.${d.ask}()" in it }
            assertEquals(
                "${d.what}: exactly ONE place may raise this dialog (`viewModel.${d.ask}()`). " +
                    "Zero means the question is never asked — the leave dialog gone means edits " +
                    "dropped in silence, the pre-send guards gone means a send with no guard at " +
                    "all. A second site is a raise no test here decides. Call sites found:",
                1, asks.size,
            )
        }
    }

    @Test fun `a tap outside, and the system Back, still close every dialog`() {
        DIALOGS.filter { it.drawnInScreen }.forEach { d ->
            assertPinned(
                SCREEN,
                "onDismissRequest = { viewModel.${d.clear}() },",
                "${d.what}: `onDismissRequest` is what a tap outside the dialog and the system " +
                    "Back gesture land on. Emptied to `{ }` — which is what a careless move of the " +
                    "flag leaves behind, since there is no longer a local to assign — the dialog " +
                    "would stop closing that way and nothing else in the suite would see it. It " +
                    "must call the ViewModel's closing door.",
            )
        }
    }

    @Test fun `the gesture that opens each dialog is the whole condition, not just the call`() {
        assertPinned(
            SCREEN, "val attemptClose = { if (dirty && !sending) viewModel.askDiscard() else cancel() }",
            "the leave dialog is only owed when there is unsaved editing and nothing is being sent. " +
                "Counting the call site is not enough: widening this condition would put \"discard " +
                "without saving?\" in front of a composer with nothing in it, on the way out.",
        )
        assertPinned(
            SCREEN, "if (recipientCount >= MANY_RECIPIENTS) viewModel.askManyRecipients() else sendNow()",
            "the many-recipients warning is owed above the threshold and NOT below it. Loosened, it " +
                "asks on every send; tightened, a wide send goes out with no warning at all.",
        )
        assertPinned(
            SCREEN, "BackHandler(enabled = !showDiscard) { attemptClose() }",
            "while the leave dialog is up it must own the system Back — that is what this `!` says. " +
                "Now that the dialog SURVIVES a rotation, dropping the negation would let Back " +
                "re-ask the same question behind the dialog that is already asking it.",
        )
    }

    @Test fun `each send guard lowers its flag BEFORE it lets the send through`() {
        listOf(
            "viewModel.clearForgotAttachment()" to "proceedAfterAttachment()",
            "viewModel.clearManyRecipients()" to "sendNow()",
        ).forEach { (lower, proceed) ->
            val code = codeLines(SCREEN)
            val sites = code.indices.filter { code[it] == lower }
            assertTrue(
                "⛔ `$lower` must appear on a line of its own, followed IMMEDIATELY by `$proceed`. " +
                    "The two pre-send guards are chained, never simultaneous: lowered AFTER the " +
                    "send is let through, the flag would still be raised while the message goes " +
                    "out, and its dialog would sit over a composer that is already sending. " +
                    "Lines carrying it: $sites, each followed by " +
                    "${sites.map { code.getOrNull(it + 1) }}",
                sites.any { code.getOrNull(it + 1) == proceed },
            )
        }
    }

    @Test fun `every dialog is drawn behind its own flag`() {
        DIALOGS.filter { it.drawnInScreen }.forEach { d ->
            assertPinned(
                SCREEN, "if (${d.local}) {",
                "${d.what}: the dialog must be drawn only while its flag is raised. Remove this " +
                    "guard and the dialog is on screen permanently; invert it and it never comes " +
                    "up at all.",
            )
        }
    }

    @Test fun `only the dialog's own ways out lower its flag`() {
        DIALOGS.forEach { d ->
            val code = codeLines(SCREEN)
            val found = code.filter { "viewModel.${d.clear}()" in it }
            assertEquals(
                "${d.what}: ⛔ these are the ONLY lines of $SCREEN allowed to call " +
                    "`viewModel.${d.clear}()` — the ways OUT of the dialog itself. Counting the " +
                    "asking gesture is not enough, and this is the asymmetry that let the measured " +
                    "defect back in: a stray `${d.clear}()` dropped into the LaunchedEffect that " +
                    "calls `prepare()` replays on EVERY activity recreation, so the dialog would " +
                    "vanish on every rotation exactly as it did on 2026-08-15 — with the flag rule " +
                    "green, because the screen never names the backing flow. Calls found:",
                d.closes,
                found,
            )
        }
    }

    @Test fun `only four lines of the ViewModel so much as name the chosen day`() {
        val writers = codeLines(VIEW_MODEL).filter { "_scheduleDay" in it }
        assertEquals(
            "the day picked in the calendar (#161) is not a boolean, but it is held under the very " +
                "same rule as the four flags above: ⛔ exactly FOUR lines of $VIEW_MODEL may NAME " +
                "`_scheduleDay` — its declaration, its public flow, and the two doors. A fifth " +
                "writer (a reset in `prepare()`, which runs on EVERY activity recreation) would " +
                "drop the chosen day on a rotation taken BETWEEN the calendar and the clock, and " +
                "the clock would come back over nothing, with this suite still green. Writers found:",
            listOf(
                "private val _scheduleDay = MutableStateFlow<Long?>(null)",
                "val scheduleDay: StateFlow<Long?> = _scheduleDay.asStateFlow()",
                "_scheduleDay.value = dayUtcMidnightMillis",
                "_scheduleDay.value = null",
            ),
            writers,
        )
    }

    @Test fun `the calendar's year list starts on the current year, not on Material's 1900`() {
        assertPinned(
            PICKER,
            "yearRange = scheduleFirstYear(System.currentTimeMillis(), ZoneId.systemDefault()).." +
                "DatePickerDefaults.YearRange.last,",
            "⛔ this line is the only thing bounding the CALENDAR's year list (#161). " +
                "`rememberDatePickerState` defaults to `DatePickerDefaults.YearRange`, i.e. " +
                "1900..2100, and the range — not the `SelectableDates` object — decides " +
                "which years are DRAWN. Measured on `emu` 2026-08-20: the year chevron opened on " +
                "1900 and 2026 was a hundred and twenty-six rows away. ⚠ Worse now than when " +
                "it was measured: `isSelectableYear` was REMOVED with this line (one mechanism per " +
                "decision), so losing the range does not just bring the scrolling back — a " +
                "past year becomes CHOOSABLE again, and its months come up entirely greyed by " +
                "`isSelectableDate` with nothing on screen saying why. ⛔ The upper end must " +
                "stay Material's own `YearRange.last`: no horizon exists anywhere in the " +
                "scheduled-send code and a number of ours here would be a limit to carry forever.",
        )
    }

    @Test fun `the clock's OK is greyed out until the chosen instant is ahead`() {
        assertPinned(
            PICKER,
            "enabled = pickedScheduleMillis(day, timeState.hour, timeState.minute, zone, " +
                "System.currentTimeMillis()) != null,",
            "⛔ this is the second lock against scheduling into the PAST (#161), and the only one " +
                "that covers \"today, at an hour already gone\" — the calendar's own bound cannot, " +
                "today being selectable by definition. Frozen to `true`, or widened to `>=`, the " +
                "message is handed to `ScheduledSends.enqueue`, whose delay is coerced to zero: it " +
                "goes out ON THE SPOT, and a send has no way back. There is deliberately no error " +
                "wording anywhere — the greyed button IS the message, so nothing else in the app " +
                "would say a word.",
        )
    }

    @Test fun `the clock opens on an hour its own OK accepts, decided by the pure rule`() {
        assertPinned(
            PICKER,
            "val opensAt = scheduleClockOpensAt(day, System.currentTimeMillis(), zone)",
            "⛔ the dial's opening hour and the OK button's refusal are two readings of the same " +
                "question (#161), and this line is what keeps them together. Back to " +
                "`ZonedDateTime.now(zone).plusHours(1)`, only the hour and minute of that are kept " +
                "and pasted onto the day the calendar returned: between 23:00 and midnight an hour " +
                "ahead is TOMORROW, so the clock reopens on 00:xx TODAY — an instant a day in the " +
                "past, refused on the spot by `pickedScheduleMillis`, with OK greyed from the " +
                "first frame. There is no error wording anywhere in this path by design, so the " +
                "user gets a dead dialog and not one word saying why. Handing it the day read in " +
                "the device's zone instead of `day` breaks it the other way, west of UTC.",
        )
    }

    @Test fun `in landscape the clock is the keyboard entry, never the dial that gets cut off`() {
        assertPinned(
            PICKER,
            "val configuration = LocalConfiguration.current",
            "⛔ the two dimensions below are only worth what this line is (#161). Pinning the " +
                "CALL and not where its arguments come from is the old trap one notch up: a bare " +
                "`Configuration()` reads back 0 by 0 — Material's own SCREEN_WIDTH_DP_UNDEFINED " +
                "— so the rule answers `false` on every screen, the dial comes back " +
                "unconditionally, and in landscape half of it is outside the dialog again with " +
                "`PM` unreachable. It must be the CURRENT configuration, which is also what makes " +
                "the choice follow the phone being turned.",
        )
        assertPinned(
            PICKER,
            "val clockAsInput = schedulePickerAsInput(configuration.screenHeightDp, " +
                "configuration.screenWidthDp)",
            "⛔ this line is what keeps the clock INSIDE its dialog when the phone is turned " +
                "(#161). Material picks its own time-picker layout on exactly this comparison, " +
                "and in landscape it puts the dial BESIDE the time display, far wider than the " +
                "dialog — and the cut being SIDEWAYS, the shell's vertical scroll does not save " +
                "it. Measured on `emu` at 914×411 dp, 2026-08-20: the right half of the dial was " +
                "off " +
                "screen, its digits drew over the AM/PM selector, and `PM` had no node left at " +
                "all, so half the day could not be picked. Read the two arguments in the other " +
                "order, or swap the comparison for a dp constant of ours, and the same clipping " +
                "comes back with nothing on screen saying why.",
        )
        assertPinned(
            PICKER,
            "if (clockAsInput) TimeInput(state = timeState) else TimePicker(state = timeState)",
            "⛔ back to an unconditional `TimePicker(state = timeState)` here and the " +
                "landscape defect is exactly as it was measured (#161): half the dial outside " +
                "the dialog, `PM` unreachable, two hour positions of twelve, and not one word " +
                "about it — only Cancel and OK still answer. ⚠ Both branches must hand " +
                "the SAME `timeState`: it is what the OK button reads through " +
                "`pickedScheduleMillis`, so a second state here would light OK on one face while " +
                "scheduling the hour of the other.",
        )
    }

    @Test fun `the clock's Cancel and OK can never be pushed under the software keyboard`() {
        val code = codeLines(PICKER)
        assertPinned(
            PICKER,
            "properties = DialogProperties(decorFitsSystemWindows = false),",
            "⛔ this is what turns the software keyboard into an INSET the clock dialog can " +
                "subtract (#161). With the default, `true`, the platform PANS the dialog window " +
                "instead and stops as soon as the focused field is clear — and Material puts the " +
                "focus in `TimeInput`'s hour field, so the keyboard comes up on its own, at the " +
                "rotation and at the first touch alike. Measured on `emu` in landscape at " +
                "914×411 dp, 2026-08-21: the `ime` inset covered [132,502][2400,1080], the " +
                "window slid 167 px, and the Cancel/OK row was left at y=531..584 — UNDER the " +
                "top of the keys at y=502. ⛔ The failure is not a silent one: tapping where " +
                "`OK` is announced types a DIGIT into the hour field (02 became 03), so the user " +
                "changes the hour while trying to confirm it. And `DialogWrapper` only sets a " +
                "`softInputMode` below API 31, so on Android 12+ the insets are the only " +
                "mechanism left.",
        )
        assertPinned(
            PICKER,
            "modifier = Modifier.safeDrawingPadding(),",
            "⛔ the other half of the same fix (#161): `decorFitsSystemWindows = false` makes " +
                "the keyboard an inset, and THIS line is what subtracts it. The dialog window " +
                "stays `wrap_content` and CENTRED, so taking the keyboard's height off the bottom " +
                "lifts the whole thing above the keys on its own. Dropped, the insets arrive and " +
                "nothing consumes them: there is no panning left either, so the row sits under " +
                "the keyboard exactly as measured — worse than before, since the platform is " +
                "no longer sliding the window at all.\n⚠ TWO lines now, and they are not twins: " +
                "one is in `SchedulePickerDialog`, the shell BOTH typed faces are drawn in, and " +
                "one is on the calendar's `DatePickerDialog`, which still draws the grid and can " +
                "still be typed into by hand through Material's own pencil — measured on `emu` at " +
                "914×411 dp, 2026-08-21, its Cancel/OK row was 37 px tall instead of 53, cut by " +
                "the screen's bottom edge. Take the line off either and the count lands here.",
            times = 2,
        )
        assertPinned(
            PICKER,
            ".weight(1f, fill = false)",
            "⛔ in a `Column`, the children WITHOUT a weight are measured FIRST (#161). This " +
                "weight is on the picker's box and on nothing else, which is what makes the " +
                "Cancel/OK row unpushable: whatever height is left, the row is measured before " +
                "the picker, and the picker scrolls instead of the row leaving the screen. " +
                "`fill = false` keeps the dialog from growing to the full height when the picker " +
                "is short. Remove it and the picker takes the height it wants — " +
                "`AlertDialogContent` has neither a `verticalScroll` nor a `weight`, which is the " +
                "very reason this shell exists.",
        )
        assertPinned(
            PICKER,
            "modifier = Modifier.align(Alignment.End).padding(top = 24.dp),",
            "⛔ the clock's action row must carry NO weight — this is the whole line, so " +
                "adding one lands here (#161). Given a weight, the row joins the picker in being " +
                "measured LAST, out of whatever height is left over, and the measured defect is " +
                "back: Cancel and OK under the keys at y=531 with the keyboard's top at y=502, " +
                "and a tap on `OK` typing a digit into the hour field instead of confirming. " +
                "⚠ And it must stay a FLOW row: Material's `AlertDialogFlowRow`, which this shell " +
                "replaces, wraps its actions onto a second line, and at 360 dp with the font " +
                "scaled up \"Abbrechen\" or \"Annuleren\" beside \"OK\" does not fit on one. " +
                "A plain `Row` puts OK off the edge instead of under it.",
        )
        // Anchored, not counted: `FlowRow(` is used three times in this screen, so the line
        // that has to be one is the one right above the action row's own modifier.
        val actionRowAt = code.indexOf("modifier = Modifier.align(Alignment.End).padding(top = 24.dp),")
        assertEquals(
            "⛔ the clock's actions must WRAP (#161). Material's own dialog puts them in an " +
                "`AlertDialogFlowRow`, and this shell replaced that: back to a plain `Row` and, " +
                "at 360 dp with the font scaled up, a translated Cancel beside OK overflows the " +
                "line — OK goes off the edge rather than onto a second row, so the only way to " +
                "confirm is not on screen and nothing says why.",
            "FlowRow(", code.getOrNull(actionRowAt - 1),
        )
        // Anchored to the weight, not counted: this screen scrolls in three places. What has to
        // be true is the chain, the picker's box shrinks by the weight, and what no longer fits is
        // reachable by the scroll right under it.
        val weightAt = code.indexOf(".weight(1f, fill = false)")
        assertEquals(
            "⛔ the weight lets the picker SHRINK; these two lines under it are what make the " +
                "part that no longer fits reachable anyway (#161), and they are worth nothing " +
                "apart. With the keyboard up in landscape the box is left about 70 dp — 411 dp " +
                "of screen, less the 220 dp `ime` inset measured on `emu` on 2026-08-21, less " +
                "the padding and the action row — where `TimeInput`'s fields alone are 72 dp. " +
                "Drop the scroll and the box is measured at that height, the content overflows " +
                "it and the `Surface` clips it: the hour fields come back cut off, with nothing " +
                "left to scroll and not a word about it.",
            listOf(".align(Alignment.CenterHorizontally)", ".verticalScroll(rememberScrollState()),"),
            listOf(code.getOrNull(weightAt + 1), code.getOrNull(weightAt + 2)),
        )
        // Anchored, not counted: the pin above counts two inset lines and a count is not a
        // place. This one says the shell has its own (the line right above its properties line)
        // so moving it onto any other dialog of this screen keeps the total at two and lands here.
        val shellPropertiesAt = code.indexOf("properties = DialogProperties(decorFitsSystemWindows = false),")
        assertEquals(
            "⛔ the shell that both typed faces are drawn in must subtract the keyboard ITSELF " +
                "(#161). Its two lines work only together: `decorFitsSystemWindows = false` turns " +
                "the keyboard into an inset, `safeDrawingPadding()` takes that inset off. With the " +
                "second one moved elsewhere the insets arrive and nothing consumes them, and there " +
                "is no panning left either: the Cancel/OK row sits under the keys exactly as it " +
                "was measured on `emu` on 2026-08-21, y=531..584 against keys at y=502, with `OK` " +
                "typing a digit into the hour field instead of confirming.",
            "modifier = Modifier.safeDrawingPadding(),", code.getOrNull(shellPropertiesAt - 1),
        )
    }

    @Test fun `both typed faces are drawn in that shell, and only the grid in Material's dialog`() {
        val code = codeLines(PICKER)
        assertPinned(
            PICKER, "if (dayAsInput) {",
            "⛔ this branch IS the volet (#161): in landscape the calendar must not be drawn in " +
                "`DatePickerDialog` at all. Measured on `emu` at 914×411 dp, 2026-08-21, with the " +
                "keyboard up on the typed face: the `ime` inset started at y=502, the Cancel/OK " +
                "row was announced at y=492..618, and a tap in the middle of `OK` did NOTHING — it " +
                "landed on the numeric keypad drawn there, so the clock never opened and the send " +
                "could not be scheduled at all. Remove this branch and every other line of the " +
                "block survives as it is, this suite included, with that tap dead again.",
        )
        assertPinned(
            PICKER, "SchedulePickerDialog(",
            "⛔ TWO call sites, and no more: the clock, and the calendar's typed face (#161). One " +
                "means a face went back into a Material dialog that cannot hold a keyboard over " +
                "it; three means a dialog nobody measured is wearing the shell.",
            times = 2,
        )
        val dayBranchAt = code.indexOf("PickInstantStep.Day -> {")
        val timeBranchAt = code.indexOf("PickInstantStep.Time -> {")
        assertTrue(
            "the calendar's branch must come before the clock's in $PICKER, or the dismissal " +
                "lines below cannot be told apart. Found Day at $dayBranchAt, Time at $timeBranchAt.",
            dayBranchAt in 0 until timeBranchAt,
        )
        val dismissAts = code.indices.filter { code[it] == "onDismissRequest = onDismiss," }
        val dayDismissAts = dismissAts.filter { it in dayBranchAt until timeBranchAt }
        assertEquals(
            "⛔ the calendar's TWO faces must be drawn in these two dialogs, in this order (#161): " +
                "the typed face in our shell, the grid in Material's `DatePickerDialog`. Swapped, " +
                "both defects come back at once — the grid inside a `verticalScroll` is one tap on " +
                "the year chevron from a `LazyVerticalGrid` measured in infinite height, which " +
                "THROWS, and the typed face is back under the keyboard where `OK` cannot be " +
                "tapped. The lines before each `onDismissRequest`:",
            listOf("SchedulePickerDialog(", "DatePickerDialog("),
            dayDismissAts.map { code.getOrNull(it - 1) },
        )
        assertEquals(
            "⛔ and what each face is handed next (#161): the shell takes a `picker` slot, and the " +
                "`DatePickerDialog` branch keeps the inset line that subtracts the keyboard — the " +
                "count of two above proves a number, never a place, so this is what says the grid " +
                "branch is the one that still has it. Lines after each `onDismissRequest`:",
            listOf("picker = {", "modifier = Modifier.safeDrawingPadding(),"),
            dayDismissAts.map { code.getOrNull(it + 1) },
        )
        val clockDismissAt = dismissAts.singleOrNull { it > timeBranchAt } ?: -1
        assertEquals(
            "⛔ the CLOCK is drawn in the shell too, and this is what says so (#161) — it used to " +
                "be anchored to the inset line right under it, which now lives in the shell " +
                "itself. Put back into a Material `AlertDialog`, the clock loses the scroll, the " +
                "weight and the inset in one line, and its Cancel/OK row goes back under the keys " +
                "at y=531 with the keyboard's top at y=502, `OK` typing a digit into the hour " +
                "field instead of confirming.",
            "SchedulePickerDialog(", code.getOrNull(clockDismissAt - 1),
        )
    }

    @Test fun `the shell never composes the calendar's GRID face, which would STOP the app`() {
        assertBody(
            PICKER,
            "if (dayState.displayMode == DisplayMode.Input) {",
            "DatePicker(state = dayState, showModeToggle = false)",
            "⛔ the shell composes the calendar only on the TYPED face, and this guard is not the " +
                "branch above repeated (#161): `rememberDatePickerState` restores the face it was " +
                "SAVED on, so the first frame after a rotation taken on the grid comes back " +
                "`DisplayMode.Picker` while the window is already landscape — the `LaunchedEffect` " +
                "only lands after that frame. The shell measures what it holds in INFINITE height " +
                "(its `verticalScroll`), and the grid's year list is a `LazyVerticalGrid`, which " +
                "THROWS when it is measured that way; Material keeps `yearPickerVisible` in a " +
                "`rememberSaveable`, so a rotation taken with that list open brings it back open. " +
                "Drop this guard and the app STOPS on that frame — it does not merely look wrong — " +
                "and is one tap on the chevron from it otherwise. One empty frame in a rare " +
                "gesture is the price.",
        )
        assertPinned(
            PICKER, "DatePicker(state = dayState, showModeToggle = false)",
            "⛔ `showModeToggle = false` is the other half of the guard above (#161). Material's " +
                "default is `true`: the typed face would carry a calendar icon that is a SINGLE " +
                "TAP into a grid inside the shell's infinite height — the year chevron there opens " +
                "a `LazyVerticalGrid` and the app STOPS. ⚠ Not a flat removal either: the grid " +
                "branch keeps Material's toggle, because in portrait the grid fits and nothing is " +
                "owed there.",
        )
    }

    @Test fun `the shell actually draws what it is handed, in the order Material draws it`() {
        assertPinned(
            PICKER, "picker()",
            "⛔ the shell's picker slot must be CALLED (#161). Deleting this one line leaves both " +
                "\"pick date and time\" dialogs composing an empty box with a Cancel and an OK in " +
                "it — no dial, no fields, no calendar, and nothing on screen saying what is being " +
                "picked. Every other line of the shell stays exactly where it is, so nothing else " +
                "in this suite moves: it is this pin or nothing.",
        )
        assertPinned(
            PICKER, "buttons()",
            "⛔ and the action slot must be CALLED too (#161). Without it the dialog has no " +
                "Cancel and no OK at all: the clock can no longer schedule anything, and the only " +
                "way out of either dialog is a tap outside — the whole gesture ends in silence, " +
                "having done nothing.",
        )
        assertPinned(
            PICKER, "buttons = { dayCancel(); dayOk() },",
            "⛔ Cancel BEFORE OK on the calendar's typed face (#161), the order Material's own " +
                "`AlertDialogFlowRow` puts them in and the order the GRID face keeps through " +
                "`dismissButton`/`confirmButton`. Swapped, the two faces of the same calendar " +
                "answer with their buttons the other way round — turning the phone moves the " +
                "confirm button under the finger that was on Cancel, and this shell is the only " +
                "place in the app where that order is written by hand.",
        )
    }

    @Test fun `the shell hands each slot on, and to the right box`() {
        val code = codeLines(PICKER)
        assertPinned(
            PICKER, "onDismissRequest = onDismissRequest,",
            "⛔ the shell must PASS ITS PARAMETER ON (#161). The three call sites are pinned, but " +
                "a pinned call site proves only that the argument was written down — emptied to " +
                "`{ }` HERE, every one of them stays exactly as it is and this whole suite is " +
                "green, while the clock and the calendar's typed face stop closing on a tap " +
                "outside AND on the system Back: the dialog window swallows the gesture, the flag " +
                "stays raised, and `Cancel` is the only way out left. That is the very defect this " +
                "file was written for, one level of indirection further in.",
        )
        val weightAt = code.indexOf(".weight(1f, fill = false)")
        val actionRowAt = code.indexOf("modifier = Modifier.align(Alignment.End).padding(top = 24.dp),")
        val pickerAt = code.indexOf("picker()")
        val buttonsAt = code.indexOf("buttons()")
        assertTrue(
            "⛔ the two slots must be called in THEIR OWN box, and this is what says which (#161). " +
                "Both are pinned as existing one line each, so SWAPPING them — `buttons()` inside " +
                "the weighted, scrolling box and `picker()` in the action row — keeps every pin in " +
                "this file green and undoes the whole fix at once, on BOTH dialogs: it is then the " +
                "Cancel/OK row that carries the weight, so it is the row that is measured last, " +
                "out of whatever height is left, and it goes back under the keyboard exactly as it " +
                "was measured at y=531 against keys at y=502. `picker()` must sit between the " +
                "weight and the action row, `buttons()` after it. Found: weight at $weightAt, " +
                "picker() at $pickerAt, action row at $actionRowAt, buttons() at $buttonsAt.",
            weightAt in 0 until pickerAt && pickerAt < actionRowAt && actionRowAt < buttonsAt,
        )
    }

    @Test fun `the calendar answers with the same two buttons on both of its faces`() {
        assertBody(
            PICKER, "confirmButton = dayOk,", "dismissButton = dayCancel,",
            "⛔ the grid face's two slots, and they are not interchangeable (#161). The bodies are " +
                "hoisted above the branch so both faces share them, which means NOTHING else in " +
                "this file distinguishes them any more: swap the two names here and Material draws " +
                "OK where Cancel belongs — the grid face answers \"OK, Cancel\" while the typed " +
                "face, three lines up, still answers \"Cancel, OK\". Turning the phone then moves " +
                "the confirming button under the finger that was on Cancel. ⚠ `dayOk` also " +
                "carries the `enabled` that greys it until a day is picked; handed to " +
                "`dismissButton` that grey lands on the wrong button.",
        )
        assertPinned(
            PICKER, "onClick = { onDayChosen(dayState.selectedDateMillis ?: 0L) },",
            "⛔ the day handed on is the one the CALENDAR returned (#161) — `selectedDateMillis`, " +
                "which is a UTC midnight, and that is how `pickedScheduleMillis` reads it back. " +
                "Any other Long here — `System.currentTimeMillis()` being the one that looks " +
                "harmless — schedules the message for TODAY whatever day was picked or typed, and " +
                "the toast and the Scheduled list both agree with the mistake. `ScheduledSends." +
                "enqueue` coerces a delay in the past to zero, so \"today, earlier\" means the " +
                "message goes out ON THE SPOT, and a send has no way back. ⚠ `?: 0L` is 1970 and " +
                "therefore refused downstream; a fallback of `now` would be sent.",
        )
    }

    @Test fun `in landscape the calendar is the keyboard entry, never the grid that gets cut off`() {
        assertPinned(
            PICKER,
            "val dayConfiguration = LocalConfiguration.current",
            "⛔ the two dimensions below are only worth what this line is (#161). A bare " +
                "`Configuration()` reads back 0 by 0 — Material's own SCREEN_WIDTH_DP_UNDEFINED " +
                "— so the rule would answer `false` on every screen and the grid would come back " +
                "unconditionally. It must be the CURRENT configuration, which is also what makes " +
                "the face follow the phone being turned. ⚠ The name is `dayConfiguration` on " +
                "purpose: the clock's block declares `val configuration = " +
                "LocalConfiguration.current` and the lint above pins THAT as the one line of its " +
                "kind, so a second copy under the same name would fail it instead of this one.",
        )
        assertPinned(
            PICKER,
            "val dayAsInput = schedulePickerAsInput(dayConfiguration.screenHeightDp, " +
                "dayConfiguration.screenWidthDp)",
            "⛔ this line is what keeps the CALENDAR inside the screen when the phone is turned " +
                "(#161). `DatePickerDialog` lays its surface out at `requiredWidth(" +
                "ContainerWidth).heightIn(max = ContainerHeight)` — 360 dp wide, at most 568 dp " +
                "tall, a MAX and never a min. Turned, the ceiling is the SCREEN's 411 dp instead, " +
                "and Material's content box has no scroll of its own, so what the month grid does " +
                "not fit into is simply CLIPPED. " +
                "Measured on `emu` at 914×411 dp, 2026-08-21: August 2026 stopped at Saturday " +
                "the 29th, that cell itself truncated, the 30th and the 31st had NO node at all, " +
                "Saturday the 1st was gone off the top, and Cancel/OK were 37 px tall instead of " +
                "53, drawn over the 23–29 row. ⛔ Nothing scrolls: a swipe up uncovered neither " +
                "the 30th nor the 31st. Read the two arguments in the other order, or swap the " +
                "comparison for a dp constant of ours, and the same clipping is back with " +
                "nothing on screen saying why.",
        )
        assertPinned(
            PICKER,
            "initialDisplayMode = if (dayAsInput) DisplayMode.Input else DisplayMode.Picker,",
            "⛔ the typed entry is Material's own answer to a constrained height, and this is the " +
                "SAME decision the clock takes one dialog down (#161). Frozen to " +
                "`DisplayMode.Picker`, the landscape reading above is exactly what comes back: " +
                "the last two days of the month with no node at all, so they cannot be picked, " +
                "and the buttons cut by the bottom edge. ⛔ A `verticalScroll` around " +
                "`DatePicker` is NOT the alternative: the year chevron opens a `LazyVerticalGrid`, " +
                "which THROWS when it is measured in infinite height — the clipping would be " +
                "traded for the app stopping.",
        )
        assertPinned(
            PICKER,
            "LaunchedEffect(dayAsInput) {",
            "⛔ without this effect the defect's OWN gesture survives the fix (#161): " +
                "`rememberDatePickerState` SAVES its displayMode, so after the rotation the " +
                "state is restored on the face it was opened with and `initialDisplayMode` above " +
                "is never consulted again. Open \"Pick date and time\" in portrait — the grid, " +
                "rightly — then turn the phone, which is the measured gesture, and the cut-off " +
                "grid is redrawn with this suite still green. ⚠ Keyed on the SHAPE of the window " +
                "and on nothing else: a face the user switched BY HAND with Material's own pencil " +
                "must not be undone under her fingers.",
        )
        assertPinned(
            PICKER,
            "dayState.displayMode = if (dayAsInput) DisplayMode.Input else DisplayMode.Picker",
            "⛔ the BODY of that effect is what actually moves the face (#161). An empty " +
                "`LaunchedEffect(dayAsInput) { }` keeps the key, keeps the pin above green, and " +
                "changes nothing at all on screen. ⚠ It must read the same `dayAsInput` as " +
                "`initialDisplayMode`, or the first frame and every frame after a rotation " +
                "disagree about which face is owed.",
        )
        assertPinned(
            PICKER,
            "properties = DialogProperties(usePlatformDefaultWidth = false, " +
                "decorFitsSystemWindows = false),",
            "⛔ the calendar's own properties line, and it is NOT the clock's (#161): " +
                "`DatePickerDialog`'s default is `DialogProperties(usePlatformDefaultWidth = " +
                "false)` — read from the Material 1.3.1 bytecode, `DialogProperties(false, " +
                "false, false, 3, null)`, the mask 3 leaving only the third argument set. " +
                "Passing `DialogProperties(decorFitsSystemWindows = false)` alone would hand " +
                "`usePlatformDefaultWidth` back its default of `true` and cap the window at the " +
                "platform's dialog width, under the 360 dp the surface REQUIRES: the grid would " +
                "then be cut sideways on a 360 dp screen in PORTRAIT, which is the commonest " +
                "gesture there is and was never broken. `decorFitsSystemWindows = false` is the " +
                "other half, for the same reason as the clock: the typed face is a text field, so " +
                "the keyboard comes up and Cancel/OK need an inset to subtract.",
        )
        assertPinned(
            PICKER,
            "DatePicker(state = dayState, showModeToggle = true)",
            "⛔ the calendar must be drawn from `dayState` and from nothing else (#161) — every " +
                "line above is a property OF that state, so handing the picker a fresh " +
                "`rememberDatePickerState()` here would leave all six of them as dead code with " +
                "this suite still green. What comes back then is the whole set at once: the " +
                "1900 year list, the grid cut off in landscape, and — the one that costs data — " +
                "no `selectableDates`, so a day already gone becomes pickable again and the " +
                "message is handed to `ScheduledSends.enqueue`, whose delay is coerced to zero. " +
                "It goes out ON THE SPOT, and a send has no way back. ⚠ The confirm button reads " +
                "`dayState.selectedDateMillis` too, so the two must be the same object.\n" +
                "⛔ `showModeToggle = true` is the other half of this line, and it is Material's " +
                "own default written out: this branch is the GRID, which is only reached on a " +
                "screen that fits it, so the pencil back to the typed face must stay exactly where " +
                "Material puts it. ⚠ The typed face is the other way round and is pinned " +
                "separately — there the toggle is a single tap into a `LazyVerticalGrid` measured " +
                "in infinite height, which stops the app.",
        )
    }

    @Test fun `the tap re-reads the instant in the DEVICE's zone, not the button's word`() {
        assertPinned(
            PICKER,
            "val millis = pickedScheduleMillis(day, timeState.hour, timeState.minute, zone, " +
                "System.currentTimeMillis())",
            "⛔ the schedule is written from THIS read, not from the one that lit the button " +
                "(#161), and the two must ask the same question. Widened to a fixed zone here " +
                "while the button keeps `zone`, the OK is right and the instant written down is " +
                "off by the device's offset — a message going out hours from the time that was " +
                "picked, with the toast and the Scheduled list both agreeing with the error. " +
                "Dropped altogether, a minute that turned between composition and tap schedules " +
                "into the past, which `ScheduledSends.enqueue` turns into sending AT ONCE.",
        )
    }

    @Test fun `the composer hands the day on, then swaps the calendar for the clock, in that order`() {
        val code = codeLines(SCREEN)
        val at = code.indexOf("onDayChosen = { dayUtcMidnightMillis ->")
        assertTrue("$SCREEN must hand `PickInstantDialogs` an `onDayChosen` taking the picked day", at >= 0)
        assertEquals(
            "⛔ the day the calendar returned is stored FIRST, from the lambda's own argument " +
                "(#161), then the calendar's flag is lowered, then the clock's raised. Any other " +
                "Long in the first line, `System.currentTimeMillis()` being the one that looks " +
                "harmless, schedules the message for TODAY whatever day was picked, and " +
                "`ScheduledSends.enqueue` turns \"today, earlier\" into sending ON THE SPOT. " +
                "The clock raised before the day is stored opens over 1970 with OK greyed for " +
                "good. Lines found:",
            listOf(
                "viewModel.chooseScheduleDay(dayUtcMidnightMillis)",
                "viewModel.clearScheduleDay()",
                "viewModel.askScheduleTime()",
                "},",
            ),
            code.subList(at + 1, (at + 5).coerceAtMost(code.size)),
        )
    }

    @Test fun `the two flags decide which dialog is drawn, and one way out closes both`() {
        val code = codeLines(SCREEN)
        assertPinned(
            SCREEN,
            "step = if (pendingScheduleDay) PickInstantStep.Day else if (pendingScheduleTime) " +
                "PickInstantStep.Time else PickInstantStep.None,",
            "⛔ each step is drawn only while its own flag is raised (#161), and this line is the " +
                "whole of that condition now that the dialogs live in `PickInstantDialogs`. Frozen " +
                "to one step, that dialog is on screen permanently; with the two flags swapped, " +
                "the clock opens over no day at all and the calendar comes back after OK.",
        )
        val dismissAt = code.indexOf("onDismiss = {")
        assertTrue("$SCREEN must hand `PickInstantDialogs` an `onDismiss` block", dismissAt >= 0)
        assertEquals(
            "⛔ `onDismiss` is what Cancel, a tap outside and the system Back land on, for BOTH " +
                "steps (#161). It must lower both flags through the ViewModel's closing doors: " +
                "emptied to `{ }`, the dialog stops closing and the window swallows Back; " +
                "dropping one of the two, that step can no longer be left except by OK. Lines found:",
            listOf("viewModel.clearScheduleDay()", "viewModel.clearScheduleTime()", "},"),
            code.subList(dismissAt + 1, (dismissAt + 4).coerceAtMost(code.size)),
        )
        assertPinned(
            SCREEN, "chosenDay = scheduleDay,",
            "⛔ the clock reads the day held by the ViewModel (#161). Handed anything else, the " +
                "collected `scheduleDay` becomes dead code and a rotation taken between the " +
                "calendar and the clock brings the clock back over 1970, OK greyed for good.",
        )
        assertPinned(
            PICKER, "val day = chosenDay ?: 0L",
            "⛔ the clock's `day` is the one the caller handed in (#161), and `?: 0L` is 1970, " +
                "which is never strictly ahead, so a clock with no day behind it can only refuse.",
        )
        assertPinned(
            PICKER, "onDismissRequest = onDismiss,",
            "⛔ the three dialogs of the two steps (the calendar's two faces and the clock) must " +
                "each close through the caller's `onDismiss` (#161). One emptied to `{ }` stops " +
                "that face closing on a tap outside and on Back, while the others still do.",
            times = 3,
        )
        listOf("PickInstantStep.Day -> {", "PickInstantStep.Time -> {").forEach {
            assertPinned(
                PICKER, it,
                "⛔ each step has exactly one branch in `PickInstantDialogs` (#161); a step with " +
                    "no branch draws nothing while its flag stays raised.",
            )
        }
    }

    @Test fun `the clock hands on only an instant it has just found ahead`() {
        val picker = codeLines(PICKER)
        val readAt = picker.indexOf(
            "val millis = pickedScheduleMillis(day, timeState.hour, timeState.minute, zone, " +
                "System.currentTimeMillis())",
        )
        assertTrue("the tap's own read of the instant is missing from $PICKER", readAt >= 0)
        assertEquals(
            "⛔ the instant read at the tap is the one handed on, and only when it is ahead " +
                "(#161). Without the guard a refused instant is scheduled anyway, which " +
                "`ScheduledSends.enqueue` turns into sending AT ONCE; handing on anything but " +
                "`millis` schedules what the button saw at composition, not what the tap read. " +
                "Lines found:",
            listOf(
                "if (millis != null) {",
                "onPicked(millis, timeState.hour, timeState.minute)",
                "}",
            ),
            picker.subList(readAt + 1, (readAt + 4).coerceAtMost(picker.size)),
        )
    }

    @Test fun `only the pickers' own ways out call onDismiss`() {
        val found = codeLines(PICKER).filter { ON_DISMISS.containsMatchIn(it) }
        assertEquals(
            "⛔ these are the ONLY lines of $PICKER allowed to name `onDismiss` (#161), whole and " +
                "in source order: the parameter, the calendar's Cancel, the three dialogs' tap " +
                "outside and Back, the clock's Cancel. A Cancel emptied to `{}` leaves its dialog " +
                "closable only by OK; an extra `onDismiss()` (after the clock's refusal, or in the " +
                "`LaunchedEffect` that replays on every rotation) closes a dialog nobody left. " +
                "Lines found:",
            listOf(
                "onDismiss: () -> Unit,",
                "TextButton(onClick = onDismiss) {",
                "onDismissRequest = onDismiss,",
                "onDismissRequest = onDismiss,",
                "onDismissRequest = onDismiss,",
                "TextButton(onClick = onDismiss) {",
            ),
            found,
        )
        assertBody(
            PICKER, "val dayCancel: @Composable () -> Unit = {", "TextButton(onClick = onDismiss) {",
            "⛔ the calendar's Cancel is the slot both faces hand on as Cancel (#161); emptied, " +
                "neither face of the calendar can be left except by OK.",
        )
    }

    @Test fun `the clock answers Cancel then OK, and Cancel closes it`() {
        val code = codeLines(PICKER)
        val at = code.indexOf("buttons = {")
        assertTrue("the clock's `buttons = {` slot is missing from $PICKER", at >= 0)
        assertEquals(
            "⛔ the clock's two buttons, in Material's order, Cancel then OK, as the calendar " +
                "answers on both its faces (#161). Swapped, the confirming button sits under the " +
                "finger that reached for Cancel, and the clock's OK schedules a send. Lines found:",
            listOf(
                "TextButton(onClick = onDismiss) {",
                "Text(stringResource(R.string.settings_cancel))",
                "}",
                "TextButton(",
            ),
            code.subList(at + 1, (at + 5).coerceAtMost(code.size)),
        )
    }

    @Test fun `the clock follows the device's 12 or 24 hour setting`() {
        assertPinned(
            PICKER, "is24Hour = android.text.format.DateFormat.is24HourFormat(context),",
            "the dial is read by whoever set the device's 12/24-hour preference (#161). Forced to " +
                "`true` or `false`, a reader of the other convention picks 7 meaning 19, and the " +
                "message is scheduled twelve hours off.",
        )
    }

    @Test fun `the screen reads the chosen day from the ViewModel, never from a remember`() {
        assertPinned(
            SCREEN,
            "val scheduleDay by viewModel.scheduleDay.collectAsStateWithLifecycle()",
            "⛔ the day picked in the calendar is not a boolean, so the rule above that forbids a " +
                "bare `remember` for the dialog FLAGS cannot see it — this line is what holds it " +
                "to the same mechanism. Turned back into `remember { mutableStateOf(null) }`, the " +
                "four lines of the ViewModel survive as dead code, this suite stays green, and a " +
                "rotation taken BETWEEN the calendar and the clock drops the day: the clock comes " +
                "back over 1970, its OK greyed for good, and the dialog is dead on screen.",
        )
    }

    @Test fun `the schedule's confirmation names the instant, never a preset label`() {
        assertPinned(
            VIEW_MODEL,
            "val label = scheduledHoldLabel(sendAtMillis, { millis, flags -> " +
                "DateUtils.formatDateTime(app, millis, flags) }) {",
            "a scheduled instant, hand-picked or preset, is announced by FORMATTING the instant " +
                "handed to `scheduleSend`, with the flags the Scheduled list uses " +
                "(ScheduledHoldLabelTest). A preset label slipped back in would name a time the " +
                "message is not going out at (WYSIWYG).",
        )
    }

    @Test fun `the Delete draft entry raises the flag through the ViewModel`() {
        assertPinned(
            SCREEN,
            "onClick = { moreMenu = false; viewModel.askDraftDelete() },",
            "the Delete draft entry must ask the ViewModel to open the door (#127). Assigning a " +
                "local flag here is what the rotation wiped; and it must still only ASK — " +
                "deleting from the entry drops the editing with no question and no way back. It " +
                "closes the overflow it lives in since #164, and nothing more.",
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    /**
     * Asserts exactly [times] code lines of [path], trimmed, are [pinned], whole line, never a
     * fragment.
     */
    private fun assertPinned(path: String, pinned: String, why: String, times: Int = 1) {
        val hits = codeLines(path).count { it == pinned }
        assertEquals(
            "$why\nExpected exactly $times line(s) of $path whose trimmed text is:\n    $pinned" +
                "\nbut found $hits. The line was rewritten, removed, split or duplicated — this " +
                "lint compares the WHOLE line, so a change that only LENGTHENS it lands here too.",
            times, hits,
        )
    }

    /**
     * Asserts [signature] is present once in [path] and that the very next code line is [body].
     */
    private fun assertBody(path: String, signature: String, body: String, why: String) {
        val code = codeLines(path)
        val at = code.indexOf(signature)
        assertEquals(
            "$why\nExpected exactly one line of $path whose trimmed text is:\n    $signature",
            1, code.count { it == signature },
        )
        assertEquals(
            "$why\nIn $path the line after `$signature` must be exactly:\n    $body",
            body, code.getOrNull(at + 1),
        )
    }

    /** The lines of [path] that are code, trimmed, comments taken off (they name the very words banned above). */
    private fun codeLines(path: String): List<String> = source(path).lines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).trim().takeIf { it.isNotBlank() }
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
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun source(path: String): String {
        val file = File(root, path)
        assertTrue("$path is not a file under ${root.absolutePath}", file.isFile)
        return file.readText()
    }

    /**
     * One of the composer's dialogs: the ViewModel flow that holds its question, the name the
     * screen reads it under (kept as it was, other lints pin lines naming it), and its two doors.
     */
    private data class Dialog(
        val what: String,
        val flow: String,
        val local: String,
        val ask: String,
        val clear: String,
        /** Every line of the screen allowed to call `clear`, whole and in order. */
        val closes: List<String>,
        /** False when `PickInstantDialogs` draws it: its `if (flag)` and `onDismissRequest` are not in [SCREEN]. */
        val drawnInScreen: Boolean = true,
    ) {
        val backing = "_$flow"

        /** An ASSIGNMENT to the backing flow, `=` not `==`, so a read is never mistaken for a write. */
        val write = Regex("""${backing}\s*\.\s*value\s*=[^=]""")
    }

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        const val PICKER = "app/src/main/kotlin/app/sterna/ui/components/PickInstantDialogs.kt"

        /** `remember` and `rememberSaveable` both, and `\b` keeps the first from matching the second. */
        val REMEMBER = Regex("""\bremember(Saveable)?\b""")

        val ON_DISMISS = Regex("""\bonDismiss\b""")

        val DIALOGS = listOf(
            Dialog(
                what = "the trash icon's \"Delete this draft?\" (#127)",
                flow = "pendingDraftDelete", local = "pendingDraftDelete",
                ask = "askDraftDelete", clear = "clearDraftDelete",
                closes = listOf(
                    "onDismissRequest = { viewModel.clearDraftDelete() },",
                    "viewModel.clearDraftDelete()",
                    "TextButton(onClick = { viewModel.clearDraftDelete() }) {",
                ),
            ),
            Dialog(
                what = "the leave dialog, \"You haven't saved your changes\" (#35, #127)",
                flow = "pendingDiscard", local = "showDiscard",
                ask = "askDiscard", clear = "clearDiscard",
                closes = listOf(
                    "onDismissRequest = { viewModel.clearDiscard() },",
                    "DiscardChoice.CANCEL -> TextButton(onClick = { viewModel.clearDiscard() }, enabled = enabled) {",
                    "viewModel.clearDiscard()",
                    "viewModel.clearDiscard()",
                ),
            ),
            Dialog(
                what = "the pre-send \"forgot an attachment?\" guard",
                flow = "pendingForgotAttachment", local = "showForgotAttachment",
                ask = "askForgotAttachment", clear = "clearForgotAttachment",
                closes = listOf(
                    "onDismissRequest = { viewModel.clearForgotAttachment() },",
                    "viewModel.clearForgotAttachment()",
                    "TextButton(onClick = { viewModel.clearForgotAttachment() }) {",
                ),
            ),
            Dialog(
                what = "the pre-send \"many recipients\" guard",
                flow = "pendingManyRecipients", local = "showManyRecipients",
                ask = "askManyRecipients", clear = "clearManyRecipients",
                closes = listOf(
                    "onDismissRequest = { viewModel.clearManyRecipients() },",
                    "viewModel.clearManyRecipients()",
                    "TextButton(onClick = { viewModel.clearManyRecipients() }) {",
                ),
            ),
            Dialog(
                what = "the \"pick date and time\" CALENDAR (#161)",
                flow = "pendingScheduleDay", local = "pendingScheduleDay",
                ask = "askScheduleDay", clear = "clearScheduleDay",
                // In source order: `onDayChosen`, then `onDismiss`.
                closes = listOf(
                    "viewModel.clearScheduleDay()",
                    "viewModel.clearScheduleDay()",
                ),
                drawnInScreen = false,
            ),
            Dialog(
                what = "the \"pick date and time\" CLOCK (#161)",
                flow = "pendingScheduleTime", local = "pendingScheduleTime",
                ask = "askScheduleTime", clear = "clearScheduleTime",
                // In source order: `onDismiss`, then `onPicked`.
                closes = listOf(
                    "viewModel.clearScheduleTime()",
                    "viewModel.clearScheduleTime()",
                ),
                drawnInScreen = false,
            ),
        )

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SCREEN).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }
    }
}
