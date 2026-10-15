package app.sterna.ui.components

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so no row and no header is ever
 * drawn here. [ReplyMarksTest] runs the decision; this pins that the two places that show a message
 * hand it that message's own two flags, each to its own parameter, and that the icons follow the
 * marks the decision returns.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`email` and `msg` are only names: a lambda parameter of
 * the same name around the call would rebind them and stay green), 3 (each call is pinned whole and
 * against its neighbour, the subject's `Text(` in the row and the star's `if` in the header, but a
 * branch drawn around it is not seen), and 4 (no icon is ever drawn nor any description spoken: the
 * vectors are read by name, and the strings by key, their wording held by [ReplyMarksTest]).
 */
class ReplyMarksWiringTest {

    @Test fun `the list row hands the marks its own message's two flags, right before the subject`() {
        val lines = DeclarationSource.functionBody(DeclarationSource.file(LIST), "fun EmailListItem(")
        val at = lines.indexOf("ReplyMarkIcons(")
        assertEquals(
            "one call in the row, whatever kind of row it is: a conversation row shows the message " +
                "it carries, and the marks do not wait for a star handler",
            1,
            lines.count { "ReplyMarkIcons(" in it },
        )
        assertEquals(
            listOf(
                "ReplyMarkIcons(",
                "answered = email.isAnswered,",
                "forwarded = email.isForwarded,",
                "modifier = Modifier.padding(end = 4.dp),",
                ")",
                "Text(",
                "text = email.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.message_no_subject),",
            ),
            if (at < 0) emptyList() else lines.subList(at, minOf(at + 7, lines.size)),
        )
    }

    @Test fun `the reader's header hands the marks the open message's two flags, before the star`() {
        val lines = DeclarationSource.functionBody(DeclarationSource.file(READER), "private fun MessageHeader(")
        val at = lines.indexOf("ReplyMarkIcons(")
        assertEquals(1, lines.count { "ReplyMarkIcons(" in it })
        assertEquals(
            listOf(
                "ReplyMarkIcons(",
                "answered = msg.header.isAnswered,",
                "forwarded = msg.header.isForwarded,",
                "modifier = Modifier.padding(start = 8.dp),",
                ")",
                "if (msg.header.isFlagged) {",
            ),
            if (at < 0) emptyList() else lines.subList(at, minOf(at + 6, lines.size)),
        )
    }

    @Test fun `the icons are the decision's marks, each drawn and named as itself`() {
        val file = DeclarationSource.file(MARKS)
        val body = DeclarationSource.functionBody(file, "internal fun ReplyMarkIcons(")
        assertEquals(
            listOf("val marks = replyMarks(answered = answered, forwarded = forwarded)"),
            body.filter { "replyMarks(" in it },
        )
        val icon = body.indexOf("marks.forEach { mark ->")
        assertEquals(
            listOf(
                "marks.forEach { mark ->",
                "Icon(",
                "mark.vector(),",
                "contentDescription = stringResource(mark.label),",
                "tint = MaterialTheme.colorScheme.onSurfaceVariant,",
                "modifier = Modifier.size(18.dp),",
                ")",
            ),
            if (icon < 0) emptyList() else body.subList(icon, minOf(icon + 7, body.size)),
        )
        assertEquals(
            listOf(
                "internal fun ReplyMark.vector(): ImageVector = when (this) {",
                "ReplyMark.ANSWERED -> Icons.AutoMirrored.Filled.Reply",
                "ReplyMark.FORWARDED -> Icons.AutoMirrored.Filled.Forward",
            ),
            DeclarationSource.functionBody(file, "internal fun ReplyMark.vector(): ImageVector"),
        )
    }

    private companion object {
        const val LIST = "app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt"
        const val READER = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        const val MARKS = "app/src/main/kotlin/app/sterna/ui/components/ReplyMarks.kt"
    }
}
