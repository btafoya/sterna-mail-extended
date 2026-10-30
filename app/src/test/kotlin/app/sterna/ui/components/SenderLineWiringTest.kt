package app.sterna.ui.components

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes or measures. [SenderLineFitTest]
 * runs the two decisions; this pins that the row hands them the right values, that the address is
 * drawn grey and whole, and that the decision is taken in the measure pass.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`nameLine`, `unread`, `nameWeight` and `showRecipients` are only names),
 * 3 (each block is pinned whole, but nothing proves which branch runs for a given row), and 4 (no
 * text is ever measured here: that `maxIntrinsicWidth` is the width the text then takes is
 * Compose's promise, not something this reads).
 */
class SenderLineWiringTest {

    private val row by lazy { DeclarationSource.functionBody(DeclarationSource.file(LIST), "fun EmailListItem(") }
    private val pair by lazy { DeclarationSource.functionBody(DeclarationSource.file(PAIR), "internal fun SenderWithAddress(") }

    @Test fun `the address is only looked up for a row that shows its sender`() {
        assertEquals(
            "in Sent and Drafts the row shows \"To: …\" (#59, #69) or, a draft with no recipient yet, " +
                "the sender who is yourself: the lookup must be handed `showRecipients`, not `recipient`",
            listOf("val senderAddress = listAddressBesideName(email.from.firstOrNull(), rowShowsRecipients = showRecipients)"),
            row.filter { "senderAddress =" in it },
        )
    }

    @Test fun `the pair takes the name's slot, and without an address the name is drawn as before`() {
        val at = row.indexOf("Column(Modifier.weight(1f)) {")
        assertEquals(
            "the first line of the text column must branch on `senderAddress`: the pair in the " +
                "name's `weight(1f)` slot, so the chips and the date stay its siblings, its name bold " +
                "by `unread` alone like the row's other texts, and otherwise " +
                "the very name `Text` the row had, ellipsed so a long name never pushes the date out",
            ROW_HEAD,
            if (at < 0) emptyList() else row.subList(at, minOf(row.size, at + ROW_HEAD.size)),
        )
    }

    @Test fun `the address is grey, light, on one line, and never cut`() {
        val at = pair.indexOf("text = address,")
        assertEquals(
            "the address `Text` pinned whole: `onSurfaceVariant` like the date but `labelSmall`, a size under it so it fits whole more often, " +
                "normal weight even unread, and no `overflow` nor `softWrap` at all. An ellipsed " +
                "address reads as a different address.",
            ADDRESS_TEXT,
            if (at < 1) emptyList() else pair.subList(at - 1, minOf(pair.size, at - 1 + ADDRESS_TEXT.size)),
        )
        assertEquals(
            "one address `Text` in the pair, and no `Ellipsis` anywhere but on the name",
            1,
            pair.count { it == "overflow = TextOverflow.Ellipsis," },
        )
        assertEquals(emptyList<String>(), pair.filter { "softWrap" in it })
    }

    @Test fun `the name keeps its style and its ellipsis inside the pair`() {
        val at = pair.indexOf("text = name,")
        assertEquals(
            NAME_TEXT,
            if (at < 1) emptyList() else pair.subList(at - 1, minOf(pair.size, at - 1 + NAME_TEXT.size)),
        )
    }

    @Test fun `the fit is decided in the measure pass, by senderLineShowsAddress`() {
        val at = pair.indexOf("val (nameText, addressText) = measurables")
        assertEquals(
            "both widths are the texts' own `maxIntrinsicWidth`, the slot is the width offered, the " +
                "gap is the 8 dp the address is drawn after; the address is measured and placed only " +
                "in the branch that fits, and the other branch gives the name the whole slot",
            MEASURE,
            if (at < 0) emptyList() else pair.subList(at, minOf(pair.size, at + MEASURE.size)),
        )
        assertEquals(
            "nothing that decides a frame late or in a second composition",
            emptyList<String>(),
            pair.filter { LATE.containsMatchIn(it) },
        )
    }

    private companion object {
        const val LIST = "app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt"
        const val PAIR = "app/src/main/kotlin/app/sterna/ui/components/SenderWithAddress.kt"

        val LATE = Regex("""\b(BoxWithConstraints|SubcomposeLayout|onTextLayout|onSizeChanged|onGloballyPositioned)\b""")

        val ROW_HEAD = listOf(
            "Column(Modifier.weight(1f)) {",
            "Row(verticalAlignment = Alignment.CenterVertically) {",
            "if (senderAddress != null) {",
            "SenderWithAddress(",
            "name = nameLine,",
            "address = senderAddress,",
            "nameWeight = if (unread) FontWeight.Bold else FontWeight.Normal,",
            "modifier = Modifier.weight(1f),",
            ")",
            "} else {",
            "Text(",
            "text = nameLine,",
            "style = MaterialTheme.typography.titleMedium,",
            "fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,",
            "maxLines = 1,",
            "overflow = TextOverflow.Ellipsis,",
            "modifier = Modifier.weight(1f),",
            ")",
            "}",
            "if (showDraftBadge) {",
        )

        val NAME_TEXT = listOf(
            "Text(",
            "text = name,",
            "style = MaterialTheme.typography.titleMedium,",
            "fontWeight = nameWeight,",
            "maxLines = 1,",
            "overflow = TextOverflow.Ellipsis,",
            ")",
        )

        val ADDRESS_TEXT = listOf(
            "Text(",
            "text = address,",
            "style = MaterialTheme.typography.labelSmall,",
            "fontWeight = FontWeight.Normal,",
            "color = MaterialTheme.colorScheme.onSurfaceVariant,",
            "maxLines = 1,",
            ")",
        )

        val MEASURE = listOf(
            "val (nameText, addressText) = measurables",
            "val gap = 8.dp.roundToPx()",
            "val nameWidth = nameText.maxIntrinsicWidth(constraints.maxHeight)",
            "val addressWidth = addressText.maxIntrinsicWidth(constraints.maxHeight)",
            "val loose = constraints.copy(minWidth = 0, minHeight = 0)",
            "if (senderLineShowsAddress(constraints.maxWidth, nameWidth, addressWidth, gap)) {",
            "val namePlaced = nameText.measure(loose.copy(maxWidth = nameWidth))",
            "val addressPlaced = addressText.measure(loose.copy(maxWidth = addressWidth))",
            "val height = maxOf(namePlaced.height, addressPlaced.height)",
            "layout(constraints.constrainWidth(nameWidth + gap + addressWidth), constraints.constrainHeight(height)) {",
            "namePlaced.placeRelative(0, (height - namePlaced.height) / 2)",
            "addressPlaced.placeRelative(nameWidth + gap, (height - addressPlaced.height) / 2)",
            "}",
            "} else {",
            "val namePlaced = nameText.measure(loose)",
            "layout(constraints.constrainWidth(namePlaced.width), constraints.constrainHeight(namePlaced.height)) {",
            "namePlaced.placeRelative(0, 0)",
            "}",
            "}",
        )
    }
}
