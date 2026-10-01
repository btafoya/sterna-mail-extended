package app.sterna.ui.settings

import app.sterna.widget.DeclarationSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: what a drop writes and which drops are refused is executed in
 * [FiltersMoveTest] and `SieveScriptMoveTest`; nothing in this module composes. What only this can
 * see is that the column and the arrows ask that same judge, and that the move goes through the one
 * door of every write, behind the auto-reply's question.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`drop`, `onMove` and `idle` are names, pinned with the
 * expression they are bound to, not with what the caller passes for them), 2 (nothing here sees an
 * expression wrapped around a pinned call), 3 (the `when` branch is pinned, not its rank among the
 * branches above it), and 4 (what the column hands back as `from` and `to` is never observed).
 */
class FiltersReorderWiringLintTest {

    @Test fun `the reorder mode asks the view model's judge, and writes through the door every write takes`() {
        assertEquals(
            "the reorder mode no longer takes its judge from the view model, or no longer writes a " +
                "move as a toggle is written: through save, behind the question a write that stops " +
                "the auto-reply asks. `idle` must hold the dropped order while that question is open.",
            listOf(
                "reordering && mayReorder -> FiltersReorder(",
                "state = state,",
                "idle = !state.saving && confirmToggle == null,",
                "drop = viewModel::drop,",
                "onMove = { change -> if (filtersWarnsBeforeWrite(state)) confirmToggle = change else viewModel.save(change) },",
                ")",
            ),
            block(SCREEN, "reordering && mayReorder -> FiltersReorder(", 6),
        )
        assertEquals(
            "the column no longer draws the rules read, keyed by their id, with drop as the judge of " +
                "a drag and the writer of a release; without `idle` a refused write leaves the dropped " +
                "order drawn over a script that does not hold it.",
            listOf(
                "ReorderableColumn(",
                "items = rows,",
                "key = { it.key },",
                "canMove = { from, to -> drop(from, to) != null },",
                "onMove = { from, to -> drop(from, to)?.let(onMove) },",
                "idle = idle,",
            ),
            block(SCREEN, "ReorderableColumn(", 6),
        )
        assertEquals(
            "the rows are no longer keyed by what their block holds: a rule's id is its position at " +
                "the read, so after a move two rows would trade keys, and a screen reader's focus with them.",
            listOf("val rows = filtersReorderRows(state.scriptRules)"),
            block(SCREEN, "val rows = ", 1),
        )
        assertEquals(
            "the Up and Down of a row are no longer the drops one place up and one place down, so a " +
                "screen reader would be offered a move the script refuses, or none it allows.",
            listOf(
                "val at = rows.indexOf(row)",
                "RuleReorderRow(row.rule, handle, up = drop(at, at - 1), down = drop(at, at + 1), onMove = onMove)",
            ),
            block(SCREEN, "val at = rows.indexOf(row)", 2),
        )
        assertEquals(
            "the drag handle no longer carries the modifier the column hands it: a long press then starts nothing.",
            listOf(
                "Icons.Filled.DragHandle,",
                "contentDescription = stringResource(R.string.folders_reorder_drag),",
                "modifier = handle.size(48.dp).padding(12.dp),",
            ),
            block(SCREEN, "Icons.Filled.DragHandle,", 3),
        )
    }

    @Test fun `the top arrow leaves the reorder mode as the back gesture does, and holds during a write`() {
        assertEquals(
            "the top arrow no longer takes the back gesture's road: in the reorder mode it left the " +
                "whole screen where the gesture leaves only the mode.",
            listOf(
                "fun back() {",
                "if (!filtersMayLeave(state)) return",
                "if (reordering && mayReorder) reordering = false else onBack()",
                "}",
                "BackHandler(enabled = reordering && mayReorder) { reordering = false }",
                "BackHandler(enabled = !filtersMayLeave(state)) {}",
            ),
            block(SCREEN, "fun back() {", 6),
        )
        assertEquals(listOf("onBack = { back() },"), block(SCREEN, "onBack = { back() },", 1))
    }

    @Test fun `the judge refuses every drop during a write, and judges over the script the list was read from`() {
        assertEquals(
            "drop no longer refuses during a write, or no longer asks filtersDrop over the script " +
                "opened: a drop then lands on a list the write is about to replace.",
            listOf(
                "fun drop(from: Int, to: Int): FiltersChange.MoveRule? {",
                "val base = opened ?: return null",
                "if (filtersBusy(_state.value)) return null",
                "val script = parsed?.takeIf { it.first === base }?.second ?: base.scriptToEdit.also { parsed = base to it }",
                "return filtersDrop(script.rules, script, from, to)",
            ),
            block(VIEW_MODEL, "fun drop(from: Int, to: Int)", 5),
        )
    }

    /** [count] consecutive code lines from the one line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = DeclarationSource.kotlinLines(file)
        val hits = lines.indices.filter { lines[it].startsWith(prefix) }
        val at = hits.singleOrNull()
            ?: error("${hits.size} code lines start with `$prefix` in ${file.name}: teach this lint the new shape")
        return lines.subList(at, minOf(at + count, lines.size))
    }

    private companion object {
        val SCREEN: File by lazy { DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt") }
        val VIEW_MODEL: File by lazy { DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/FiltersViewModel.kt") }
    }
}
