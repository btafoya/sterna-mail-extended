package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: `InboxViewModel` cannot be built in a JVM test, so this reads
 * it as text. [UnifiedViewByChoiceTest] executes the rule; this says it is wired into the folder
 * half of a tapped notification, and that the setting's claim on "All inboxes" ends where the
 * reader picks that view herself. Of the four holes `CONTRIBUTING.md` lists, three apply: nothing
 * here says what `selection` holds at runtime (1), nor when the notification's verdict runs against
 * the first folder list (3), nor what the list on screen ends up showing (4).
 */
class UnifiedViewByChoiceWiringLintTest {

    @Test
    fun `the folder half asks whether the reader stands in All inboxes, not whether it is on screen`() {
        assertEquals(
            "applyNotificationFolder() must hand NotificationFolderSwitch the reader's own unified " +
                "view, standsInUnifiedView(). Handed `selection.value is Sel.Unified`, the 'All " +
                "inboxes' the open-on setting seeds keeps a tapped notification's folder from the " +
                "list behind the message (#185).",
            listOf("unifiedView = standsInUnifiedView(),"),
            codeLines().filter { it.startsWith("unifiedView = ") },
        )
        assertEquals(
            "standsInUnifiedView() must ask unifiedViewByChoice() about the selection on screen and " +
                "the seed's own origin, the same answer SternaApp's account half reads off this list.",
            listOf("fun standsInUnifiedView(): Boolean = unifiedViewByChoice(selection.value, unifiedOpenedBySetting)"),
            codeLines().filter { it.startsWith("fun standsInUnifiedView(") },
        )
        assertEquals(
            "the seed's origin is read from the setting, once, when the selection is seeded.",
            listOf("private var unifiedOpenedBySetting = openOnOverridesMemory(store.openOnView())"),
            codeLines().filter { it.startsWith("private var unifiedOpenedBySetting") },
        )
    }

    /** Pinned as a block: moved below the early return, the clearing line would skip the widget or
     *  drawer tap on an "All inboxes" already shown, and leave the reader's choice to the setting. */
    @Test
    fun `going back to All inboxes by hand makes it the reader's own`() {
        assertEquals(
            "selectUnified() must clear unifiedOpenedBySetting first, before its early return. " +
                "Both its callers are the reader's taps (drawer entry, widget); after the early return " +
                "a tap on the 'All inboxes' already shown would leave the flag set, and a notification " +
                "would move her list out of the view she just chose.",
            listOf(
                "fun selectUnified() {",
                "unifiedOpenedBySetting = false",
                "if (selection.value is Sel.Unified) return",
            ),
            codeLines().let { lines ->
                val start = lines.indexOf("fun selectUnified() {")
                if (start < 0) emptyList() else lines.subList(start, minOf(start + 3, lines.size))
            },
        )
        assertEquals(
            "selectUnified() must be the only way into Sel.Unified after the seed: a second door " +
                "into the unified view would inherit a claim the setting no longer has.",
            listOf("selection.value = Sel.Unified"),
            codeLines().filter { SELECTION_TO_UNIFIED.containsMatchIn(it) },
        )
        assertEquals(
            "every assignment to unifiedOpenedBySetting, `this.`-prefixed or not, compared whole: " +
                "its declaration and the clearing in selectUnified(), selectUnread() and " +
                "selectUnreadFromShortcut(). Any other write hands a view back to the setting, or " +
                "takes one from it, without a gesture of the reader.",
            listOf(
                "private var unifiedOpenedBySetting = openOnOverridesMemory(store.openOnView())",
                "unifiedOpenedBySetting = false",
                "unifiedOpenedBySetting = false",
                "unifiedOpenedBySetting = false",
            ),
            codeLines().filter { FLAG_ASSIGNMENT.containsMatchIn(it) },
        )
    }

    /** #282: under "open on" All inboxes the flag is still set when the reader taps "Unread" there,
     *  and every account's unread view would then count as the setting's. Pinned before the early
     *  return for the reason `selectUnified()` is. */
    @Test
    fun `opening the unread view by hand makes it the reader's own`() {
        for (entry in listOf("fun selectUnread() {", "fun selectUnreadFromShortcut() {")) {
            assertEquals(
                "$entry must clear unifiedOpenedBySetting first, before its early return: the " +
                    "drawer's and the shortcut's 'Unread' are the reader's taps, and the unread view " +
                    "of every account they open must not be moved by a notification.",
                listOf(entry, "unifiedOpenedBySetting = false"),
                codeLines().let { lines ->
                    val start = lines.indexOf(entry)
                    if (start < 0) emptyList() else lines.subList(start, minOf(start + 2, lines.size))
                },
            )
        }
    }

    private fun codeLines(): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, INBOX_VIEW_MODEL).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
        return File(root, INBOX_VIEW_MODEL).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private companion object {
        const val INBOX_VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"
        val FLAG_ASSIGNMENT = Regex("""\bunifiedOpenedBySetting\s*=(?!=)""")
        val SELECTION_TO_UNIFIED = Regex("""\bselection\.value\s*=\s*Sel\.Unified\b""")
    }
}
