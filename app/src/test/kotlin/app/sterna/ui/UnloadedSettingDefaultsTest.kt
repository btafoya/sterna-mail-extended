package app.sterna.ui

import app.sterna.core.data.settings.SwipeAction
import app.sterna.ui.inbox.SWIPE_CONFIG_UNLOADED
import app.sterna.ui.message.CONFIRM_LINKS_UNLOADED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two settings that DRIVE AN ACTION rather than merely show a value, and the value each one carries
 * during the frames before DataStore has answered.
 *
 * A swipe that lands in that window commits whatever `swipeConfig` says, and a link tapped in it
 * opens with or without the confirmation `confirmLinks` says. Seeding either flow with the
 * repository's default means the reader who chose "None", or who asked to be shown a link's
 * destination first, gets the opposite for as long as the read takes. The fix is not a new state:
 * it is that the waiting value must be the INERT one — `NONE`/`NONE` deletes nothing, `true` asks
 * before opening — so that losing the race costs a gesture, never a message or a page load.
 *
 * The guard already held by accident of data dependency; this file is what makes it WRITTEN.
 *
 * Two instruments, because neither is enough on its own:
 *
 *  1. the VALUES, executed. Expectations are hard-coded, never recomputed from the constants under
 *     test: a test that derives what it expects is a copy of the decision and stays green when the
 *     decision is inverted;
 *  2. the WIRING, as a source lint, from the constant all the way to the screen. `InboxViewModel`
 *     and `MessageViewModel` are `AndroidViewModel`s reaching into the application container, and
 *     the screens are Compose code — there is no Robolectric and no `androidTest` in this module,
 *     so nothing here can construct either and read a value. Reading the source as text is what is
 *     left.
 *
 * The second half of that thread is not decoration. A `StateFlow` is also a `Flow`, so
 * `collectAsStateWithLifecycle(initialValue = false)` resolves at the collection site and puts the
 * original defect back one file further along, out of reach of every rule that stops at the view
 * model's door. It matters most in the reader, where `MessagePage` builds a `MessageViewModel` PER
 * PAGE of the pager (see `MessageScreen.kt`): the loading window for `confirmLinks` reopens on
 * every message swiped through, not once at startup.
 *
 * What this does NOT prove: that `SwipeableEmailRow` honours a `NONE` action (that is
 * [app.sterna.ui.inbox.SwipeCommitTest]'s and the screen's business), or that `MessageScreen`'s
 * `if (confirmLinks)` branch still guards the right call. It reads names and argument text, so it
 * can only say a value is WRITTEN where it is written.
 *
 * Every rule below compares WHOLE lines (trimmed, nothing else), never a `contains` for something
 * it expects to find: a substring check is blind to any mutation that LENGTHENS the line, and
 * `initialValue = SWIPE_CONFIG_UNLOADED.copy(left = SwipeAction.DELETE),` is exactly such a
 * mutation. `contains` appears once, where this file screens for something that must be ABSENT.
 */
class UnloadedSettingDefaultsTest {

    // -- 1. the values themselves, executed -------------------------------------------------------

    @Test fun `neither swipe direction acts before the stored actions are known`() {
        assertEquals(
            "the swipe-right action carried while the setting is still being read must be NONE: " +
                "a swipe that lands in that window would otherwise run an action the reader may " +
                "have turned off",
            SwipeAction.NONE,
            SWIPE_CONFIG_UNLOADED.right,
        )
        assertEquals(
            "same for swipe-left, and this is the destructive one: DELETE here is a message in " +
                "the Trash because DataStore was a frame late",
            SwipeAction.NONE,
            SWIPE_CONFIG_UNLOADED.left,
        )
    }

    @Test fun `a link tapped before the stored setting is known is confirmed`() {
        assertTrue(
            "the waiting value for confirmLinks must be true. false opens the tapped URL straight " +
                "away, which is the side that cannot be taken back: the page is fetched, the " +
                "tracking parameter is honoured, and no dialog ever appeared",
            CONFIRM_LINKS_UNLOADED,
        )
    }

    // -- 2. the wiring, as a whole-line source lint -----------------------------------------------

    @Test fun `swipeConfig starts on the inert pair and nothing else`() {
        assertBlock(
            INBOX_VIEW_MODEL,
            listOf(
                "val swipeConfig: StateFlow<SwipeConfig> =",
                "combine(settings.swipeRightAction, settings.swipeLeftAction) { right, left ->",
                "SwipeConfig(right, left)",
                "}.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = SWIPE_CONFIG_UNLOADED,",
                ")",
            ),
            "the swipe configuration's stateIn",
        )
    }

    @Test fun `confirmLinks starts on the asking side and nothing else`() {
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "val confirmLinks = settings.confirmLinks.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = CONFIRM_LINKS_UNLOADED,",
                ")",
            ),
            "the link-confirmation stateIn",
        )
    }

    /**
     * The negative screen — a `contains`, deliberately: it looks for what must be absent.
     *
     * The literal it forbids was a SECOND COPY of the repository's own defaults
     * (`SettingsRepository.swipeRightAction` / `swipeLeftAction`), free to drift from them. The
     * point of the inert pair is that the app no longer restates a default it does not own: what
     * it says while waiting is "not read yet", not "probably this".
     */
    @Test fun `the repository's swipe defaults are not restated in the inbox view model`() {
        val offenders = codeLines(INBOX_VIEW_MODEL).filter { "SwipeAction.TOGGLE_READ" in it || "SwipeAction.DELETE" in it }
        assertEquals(
            "InboxViewModel must not name the stored swipe defaults at all. They belong to " +
                "SettingsRepository, which is where the stored value is read against them; a copy " +
                "here is one that can drift, and seeded into a stateIn it is the defect itself. " +
                "Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    // -- 3. the screens take the waiting value as it comes, without reseeding it -------------------

    @Test fun `the inbox takes the swipe actions as the view model publishes them`() {
        assertSoleLine(
            INBOX_SCREEN,
            "val swipe by viewModel.swipeConfig.collectAsStateWithLifecycle()",
            "the inbox's collection of swipeConfig",
            "viewModel.swipeConfig",
        )
    }

    @Test fun `the reader takes the link setting as the view model publishes it`() {
        assertSoleLine(
            MESSAGE_SCREEN,
            "val confirmLinks by viewModel.confirmLinks.collectAsStateWithLifecycle()",
            "the reader's collection of confirmLinks",
            "viewModel.confirmLinks",
        )
    }

    // -- reading the sources ----------------------------------------------------------------------

    /**
     * Pins one WHOLE line that must appear in [file] EXACTLY ONCE.
     *
     * Counting, rather than locating: for a one-line rule "present" and "present once" are
     * different facts, and only the second forbids a duplicate collection of the same flow wired
     * differently. Zero and two both fail, with the neighbourhood ([near]) printed so the failure
     * says which of the two happened.
     */
    private fun assertSoleLine(file: File, expected: String, what: String, near: String) {
        val lines = codeLines(file)
        val nearby = lines.filter { near in it }
        assertEquals(
            "$what must appear in ${file.name} exactly once and be written EXACTLY as:\n" +
                "  $expected\n" +
                "An argument added here — collectAsStateWithLifecycle(initialValue = …) — compiles, " +
                "since a StateFlow is also a Flow, and reinstates the very default the constant " +
                "exists to remove, one file past every other rule in this test. Lines mentioning " +
                "'$near':\n" + nearby.joinToString("\n").ifEmpty { "(none at all)" },
            1,
            lines.count { it == expected },
        )
    }

    /** Locates [expected]'s first line in [file] and compares the block that follows it, whole. */
    private fun assertBlock(file: File, expected: List<String>, what: String) {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { it == expected.first() }
        check(at >= 0) {
            "no '${expected.first()}' in ${file.name} — $what is gone or was reshaped, and this " +
                "lint must be taught the new shape rather than left green over a block it never read"
        }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned WHOLE, line by line and in order. Nothing in this module can run it. " +
                "Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this lint reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        val MESSAGE_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")
        }
        val INBOX_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        }
        val MESSAGE_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }
    }
}
