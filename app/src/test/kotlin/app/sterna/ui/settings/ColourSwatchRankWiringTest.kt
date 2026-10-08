package app.sterna.ui.settings

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: the rank itself is executed in `AccountPaletteRankTest`; nothing
 * in this module composes, so the accessibility tree of `ColourSwatch` is never built here and no
 * screen reader was listened to. What is pinned is that the swatch's own node is described by the
 * rank label, and only when there is one.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`rankLabel` is only a name: its one `val` is pinned
 * whole, but a lambda parameter of that name around the `semantics` block would rebind it and stay
 * green), 3 (the first `.semantics {` is the one read, on whichever modifier chain it stands), and
 * 4 (the description the node ends up exposing, merged or not with the "Auto" text or the check
 * mark drawn inside it, is never observed).
 */
class ColourSwatchRankWiringTest {

    @Test fun `the label is the positional string, fed the rank and the palette's size`() {
        val lines = body()
        val at = lines.indexOfFirst { it.startsWith("val rankLabel") }
        assertEquals(
            "ColourSwatch no longer builds its label from the rank of the colour and the size of " +
                "the palette: a null colour and a colour outside the palette must both end without one",
            EXPECTED_LABEL,
            slice(lines, at, EXPECTED_LABEL.size),
        )
        assertEquals("one rankLabel, or the semantics may read another", 1, lines.count { it.startsWith("val rankLabel") })
    }

    @Test fun `the swatch is described by that label, only when it has one`() {
        val lines = body()
        assertEquals(
            "the semantics of ColourSwatch are pinned whole, line by line: without the content " +
                "description a coloured swatch has a role and a state but no name of its own",
            EXPECTED_SEMANTICS,
            slice(lines, lines.indexOfFirst { it.startsWith(".semantics {") }, EXPECTED_SEMANTICS.size),
        )
    }

    private fun slice(lines: List<String>, at: Int, size: Int): List<String> =
        if (at < 0) emptyList() else lines.subList(at, minOf(at + size, lines.size))

    private fun body(): List<String> = DeclarationSource.functionBody(
        DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/SettingsComponents.kt"),
        "internal fun ColourSwatch(",
    )

    private companion object {
        val EXPECTED_LABEL = listOf(
            "val rankLabel = color?.let(AccountPalette::rankOf)?.let { rank ->",
            "stringResource(R.string.settings_account_colour_n, rank, AccountPalette.colors.size)",
            "}",
        )
        val EXPECTED_SEMANTICS = listOf(
            ".semantics {",
            "role = Role.Button",
            "this.selected = selected",
            "if (rankLabel != null) contentDescription = rankLabel",
            "},",
        )
    }
}
