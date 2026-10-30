package app.sterna.ui.components

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes. [LabelsTest] runs `labelsOf` and
 * `rowLabels`; this pins that the list row and the reader's header hand them their own message's
 * keywords, that the row draws what `rowLabels` keeps and not the whole list, in a group weighted
 * `1f` against the subject's `2f` so it cannot squeeze the subject nor the conversation pill, that a
 * chip paints the fill it is handed (#141), and that the header's chips sit inside the box whose
 * measured height the body reserves (#171).
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`email`, `msg` and `labels` are only names: a lambda
 * parameter of the same name around the call would rebind them and stay green), 3 (each call is
 * pinned whole and against its neighbours, but a branch drawn around it is not seen), and 4 (no chip
 * is ever drawn, nor any description spoken: the plural is read by key, its wording held by
 * [LabelsTest]).
 */
class LabelsWiringTest {

    @Test fun `the list row hands its own message's labels to the row chips, after the subject`() {
        val lines = DeclarationSource.functionBody(DeclarationSource.file(LIST), "fun EmailListItem(")
        assertEquals(1, lines.count { "RowLabelChips(" in it })
        assertEquals(1, lines.count { "labelsOf(" in it })
        val at = lines.indexOf("RowLabelChips(labels = labelsOf(email.keywords), fill = chipBackground)")
        assertEquals(
            listOf(
                "modifier = Modifier.weight(2f, fill = false),",
                ")",
                "RowLabelChips(labels = labelsOf(email.keywords), fill = chipBackground)",
                "if (threadExpandable && threadCount > 1) {",
            ),
            if (at < 2) emptyList() else lines.subList(at - 2, minOf(at + 2, lines.size)),
        )
    }

    @Test fun `the row chips draw what rowLabels keeps as a group weighted against the subject`() {
        val body = DeclarationSource.functionBody(DeclarationSource.file(LABELS), "internal fun RowScope.RowLabelChips(")
        assertEquals(listOf("val row = rowLabels(labels)"), body.filter { "rowLabels(" in it })
        assertEquals(emptyList<String>(), body.filter { "labels.forEach" in it || "labels.take" in it })
        val group = body.indexOf("if (row.shown.isEmpty()) return")
        assertEquals(
            listOf(
                "if (row.shown.isEmpty()) return",
                "Row(",
                "modifier = Modifier.weight(1f, fill = false).padding(start = 6.dp),",
                "horizontalArrangement = Arrangement.spacedBy(6.dp),",
                "verticalAlignment = Alignment.CenterVertically,",
                ") {",
                "row.shown.forEach { label ->",
                "LabelChip(text = label, fill = fill, modifier = Modifier.weight(1f, fill = false))",
                "}",
                "if (row.more > 0) {",
                "val description = pluralStringResource(R.plurals.a11y_more_labels, row.more, row.more)",
                "LabelChip(",
                "text = \"+\${row.more}\",",
                "fill = fill,",
                "modifier = Modifier.clearAndSetSemantics { contentDescription = description },",
                ")",
            ),
            if (group < 0) emptyList() else body.subList(group, minOf(group + 16, body.size)),
        )
    }

    @Test fun `a label chip paints the fill it is handed`() {
        val body = DeclarationSource.functionBody(DeclarationSource.file(LABELS), "private fun LabelChip(")
        val at = body.indexOf("modifier = modifier")
        assertEquals(
            listOf(
                "modifier = modifier",
                ".clip(MaterialTheme.shapes.small)",
                ".background(fill)",
                ".padding(horizontal = 6.dp, vertical = 1.dp),",
            ),
            if (at < 0) emptyList() else body.subList(at, minOf(at + 4, body.size)),
        )
        assertEquals(1, body.count { ".background(" in it })
    }

    @Test fun `the reader's header hands the open message's labels to a flow row, under the subject`() {
        val lines = DeclarationSource.functionBody(DeclarationSource.file(READER), "private fun MessageHeader(")
        assertEquals(1, lines.count { "HeaderLabelChips(" in it })
        val subject = lines.indexOf("?: stringResource(R.string.message_no_subject),")
        val at = lines.indexOf("HeaderLabelChips(")
        assertEquals(
            listOf(
                "HeaderLabelChips(",
                "labels = labelsOf(msg.header.keywords),",
                "modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),",
                ")",
                "Row(",
            ),
            if (at < 0) emptyList() else lines.subList(at, minOf(at + 5, lines.size)),
        )
        assertEquals("the chips come after the subject", true, subject in 0 until at)

        val flow = DeclarationSource.functionBody(DeclarationSource.file(LABELS), "internal fun HeaderLabelChips(")
        assertEquals(
            listOf("if (labels.isEmpty()) return", "FlowRow("),
            flow.subList(1, minOf(3, flow.size)),
        )
        assertEquals(1, flow.count { it == "labels.forEach { label ->" })
    }

    @Test fun `the header is drawn inside the box whose height the body reserves`() {
        val lines = DeclarationSource.kotlinLines(DeclarationSource.file(READER))
        assertEquals(1, lines.count { it.startsWith("MessageHeader(") })
        val at = lines.indexOf(".onSizeChanged { headerHeightPx = it.height }")
        assertEquals(
            listOf(
                ".onSizeChanged { headerHeightPx = it.height }",
                ".background(MaterialTheme.colorScheme.surface),",
                ") {",
                "MessageHeader(",
            ),
            if (at < 0) emptyList() else lines.subList(at, minOf(at + 4, lines.size)),
        )
    }

    private companion object {
        const val LIST = "app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt"
        const val READER = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        const val LABELS = "app/src/main/kotlin/app/sterna/ui/components/Labels.kt"
    }
}
