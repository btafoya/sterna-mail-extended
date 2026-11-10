package app.sterna.ui.message

import app.sterna.core.data.mail.MailtoUnsubscribe
import app.sterna.core.data.mail.UnsubscribeAction
import app.sterna.core.data.mail.UnsubscribeOptions
import app.sterna.core.imap.CryptoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The card under the message header (#291): which lines it holds and in which order, executed through
 * [headerCardRows]; then, as source lint, that the header draws what the function decided, where it
 * decided it, and that a line is one touch target.
 *
 * Holes left open by the lint half, of the four in `CONTRIBUTING.md`: an argument name says nothing
 * about what it is bound to (1), which is why the `headerCardRows` call is pinned whole; a pinned line
 * says nothing about execution order beyond the line order this file checks (3); nothing here holds
 * the value a composable collects (4), nor measures a line's height on a screen.
 *
 * The signature verdict is held the same way: its icon and its text are tinted by the verdict's tone,
 * its text wraps, and it prints the identity through `cardSignerIdentity`. Those lines are pinned
 * whole, which leaves hole 1 on `verdict` itself, hole 3 on where the lines sit inside their `Text`,
 * and hole 4 on the colour `color()` resolves to and the height the wrapped text takes.
 */
class HeaderCardTest {

    private val locked = CryptoUiState.Locked(CryptoKind.PGP_ENCRYPTED)
    private val list = UnsubscribeOptions(mailto = MailtoUnsubscribe("leave@l.example.com"))
    private val receipt = ReadReceiptStrip(ReadReceiptStripBody.ASK, "sender@example.org")

    // -- the decision, executed --------------------------------------------------------------------

    @Test fun `every line present is drawn crypto first and the unverified sender last`() {
        assertEquals(
            listOf(
                HeaderCardRow.Crypto(locked),
                HeaderCardRow.Images,
                HeaderCardRow.Unsubscribe(UnsubscribeAction.MAIL),
                HeaderCardRow.ReadReceipt(receipt),
                HeaderCardRow.SenderUnverified("DKIM"),
            ),
            headerCardRows(locked, imagesStrip = true, unsubscribe = list, readReceipt = receipt, senderUnverified = "DKIM"),
        )
    }

    @Test fun `nothing to say is no card at all`() {
        assertEquals(
            emptyList<HeaderCardRow>(),
            headerCardRows(CryptoUiState.None, imagesStrip = false, unsubscribe = null, readReceipt = null, senderUnverified = null),
        )
    }

    @Test fun `a list header offering no gesture draws no line`() {
        assertEquals(
            "UnsubscribeOptions with nothing in it has no preferredAction: a line for it would be an " +
                "empty row on a card that may hold nothing else",
            emptyList<HeaderCardRow>(),
            headerCardRows(CryptoUiState.None, imagesStrip = false, unsubscribe = UnsubscribeOptions(), readReceipt = null, senderUnverified = null),
        )
    }

    @Test fun `each line alone is a card of one line`() {
        assertEquals(listOf(HeaderCardRow.Crypto(locked)), headerCardRows(locked, false, null, null, null))
        assertEquals(listOf(HeaderCardRow.Images), headerCardRows(CryptoUiState.None, true, null, null, null))
        assertEquals(
            listOf(HeaderCardRow.Unsubscribe(UnsubscribeAction.ONE_CLICK)),
            headerCardRows(CryptoUiState.None, false, UnsubscribeOptions(oneClickUrl = "https://l.example.com/u"), null, null),
        )
        assertEquals(listOf(HeaderCardRow.ReadReceipt(receipt)), headerCardRows(CryptoUiState.None, false, null, receipt, null))
        assertEquals(listOf(HeaderCardRow.SenderUnverified("SPF")), headerCardRows(CryptoUiState.None, false, null, null, "SPF"))
    }

    @Test fun `the list line carries the gesture the overflow entry would name`() {
        assertEquals(
            listOf(HeaderCardRow.Unsubscribe(UnsubscribeAction.OPEN_PAGE)),
            headerCardRows(CryptoUiState.None, false, UnsubscribeOptions(pageUrl = "https://l.example.com/p"), null, null),
        )
    }

    // -- the header, as source ---------------------------------------------------------------------

    @Test fun `the header asks headerCardRows once, with every argument named`() {
        val header = codeLines(block("private fun MessageHeader("))
        val at = header.indexOf("val cardRows = headerCardRows(")
        assertTrue("MessageHeader no longer asks headerCardRows. Header was:\n${header.joinToString("\n")}", at >= 0)
        assertEquals(
            "imagesStrip is the frozen presence, never the live imagesBlocked: a card that loses its " +
                "images line at the tap changes the header's height and reloads the body",
            listOf(
                "val cardRows = headerCardRows(",
                "crypto = crypto,",
                "imagesStrip = imagesStrip,",
                "unsubscribe = unsubscribe,",
                "readReceipt = readReceiptStrip(readReceiptOffer, readReceiptState),",
                "senderUnverified = senderUnverified,",
                ")",
                "if (cardRows.isNotEmpty()) {",
                "HeaderCard(",
            ),
            header.subList(at, minOf(at + 9, header.size)),
        )
    }

    @Test fun `the card sits under the sender row and before the attachments and the invitation`() {
        val header = codeLines(block("private fun MessageHeader("))
        val card = header.indexOf("HeaderCard(")
        val attachments = header.indexOf("AttachmentSection(")
        val calendar = header.indexOf("CalendarEventCard(")
        val sender = header.indexOf("ReplyMarkIcons(")
        assertTrue("card=$card sender=$sender attachments=$attachments calendar=$calendar", sender in 0 until card)
        assertTrue("card=$card attachments=$attachments", card in 0 until attachments)
        assertTrue("card=$card calendar=$calendar", card in 0 until calendar)
        assertEquals(
            "every line of the card is drawn by HeaderCard alone; MessageHeader draws none of them",
            emptyList<String>(),
            header.filter { line -> ROW_COMPOSABLES.any { line.startsWith("$it(") } },
        )
        assertEquals(
            "the header keeps its closing divider, the attachments and the invitation keep theirs, " +
                "and nothing else: no divider frames the card",
            3,
            header.count { it == "HorizontalDivider()" },
        )
    }

    @Test fun `the card draws the lines it was handed, in that order, with the page between them`() {
        val card = codeLines(block("private fun HeaderCard("))
        val at = card.indexOf("rows.forEachIndexed { index, row ->")
        assertTrue("HeaderCard no longer walks its rows in order. Card was:\n${card.joinToString("\n")}", at >= 0)
        assertEquals(
            listOf(
                "rows.forEachIndexed { index, row ->",
                "if (index > 0) Spacer(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.surface))",
                "when (row) {",
                "is HeaderCardRow.Crypto -> PgpStatusCard(row.state, onCryptoAction, onExplainSignature)",
                "HeaderCardRow.Images -> ImagesStrip(imagesBlocked, onShowImages)",
                "is HeaderCardRow.Unsubscribe -> UnsubscribeStrip(row.action, unsubscribeState, onUnsubscribe)",
                "is HeaderCardRow.ReadReceipt -> ReadReceiptStrip(",
                "row.strip,",
                "onSendReadReceipt = onSendReadReceipt,",
                "onDeclineReadReceipt = onDeclineReadReceipt,",
                ")",
                "is HeaderCardRow.SenderUnverified -> SenderUnverifiedStrip(row.methods, onViewHeaders)",
                "}",
                "}",
            ),
            card.subList(at, minOf(at + 14, card.size)),
        )
        assertEquals("no HorizontalDivider inside the card", emptyList<String>(), card.filter { "HorizontalDivider" in it })
    }

    @Test fun `the card is a rounded surfaceContainerHigh block inset from the page edge`() {
        val card = codeLines(block("private fun HeaderCard("))
        val at = card.indexOf("Column(")
        assertTrue("HeaderCard is no longer a Column", at >= 0)
        assertEquals(
            listOf(
                "Column(",
                "modifier = Modifier",
                ".fillMaxWidth()",
                ".padding(start = 12.dp, end = 12.dp, bottom = 12.dp)",
                ".clip(RoundedCornerShape(16.dp))",
                ".background(MaterialTheme.colorScheme.surfaceContainerHigh),",
                ") {",
            ),
            card.subList(at, minOf(at + 7, card.size)),
        )
    }

    @Test fun `a tappable line is one target, standing on a 48 dp floor`() {
        val line = codeLines(block("private fun HeaderCardLine("))
        val at = line.indexOf("Row(")
        assertTrue("HeaderCardLine is no longer a Row", at >= 0)
        assertEquals(
            listOf(
                "Row(",
                "modifier = Modifier",
                ".fillMaxWidth()",
                ".then(",
                "if (onClick != null) {",
                "Modifier",
                ".clickable(enabled = enabled, role = Role.Button, onClick = onClick)",
                ".semantics(mergeDescendants = true) {}",
                "} else {",
                "Modifier",
                "},",
                ")",
                ".heightIn(min = 48.dp)",
                ".padding(horizontal = 14.dp),",
                "verticalAlignment = Alignment.CenterVertically,",
                "content = content,",
                ")",
            ),
            line.subList(at, minOf(at + 17, line.size)),
        )
    }

    @Test fun `a tappable line holds no button of its own`() {
        listOf(
            "private fun ImagesStrip(",
            "private fun UnsubscribeStrip(",
            "private fun PgpStatusCard(",
            "private fun SenderUnverifiedStrip(",
        ).forEach { decl ->
            val body = codeLines(block(decl))
            assertEquals(
                "$decl: a button inside a clickable Row is a second TalkBack target and a nested touch " +
                    "area; the action is drawn as text by HeaderCardAction",
                emptyList<String>(),
                body.filter { line -> NESTED_TARGETS.any { line.startsWith(it) } || ".clickable" in line },
            )
        }
    }

    /** A decrypted message with no signature has nothing to open, ever: a disabled clickable would be
     *  read by TalkBack as a disabled button. `onClick = null` is the line that takes no touch. */
    @Test fun `a line that never has a gesture is not a disabled button`() {
        val crypto = codeLines(block("private fun PgpStatusCard("))
        assertEquals(
            listOf("is CryptoUiState.Decrypted -> HeaderCardLine(onClick = if (verdict != null) onExplainSignature else null) {"),
            crypto.filter { it.startsWith("is CryptoUiState.Decrypted ->") },
        )
        assertEquals("no `enabled = verdict …` left in the crypto line", emptyList<String>(), crypto.filter { "enabled = verdict" in it })
    }

    /** While a decryption is out the unlock line keeps its gesture but refuses it: a second tap would
     *  start a second decryption. */
    @Test fun `the unlock line takes no touch while it decrypts`() {
        val crypto = codeLines(block("private fun PgpStatusCard("))
        assertEquals(
            listOf("is CryptoUiState.Locked -> HeaderCardLine(onClick = onAction, enabled = !crypto.decrypting) {"),
            crypto.filter { it.startsWith("is CryptoUiState.Locked ->") },
        )
    }

    /** A failure with a message offers a retry; without one (no provider) there is nothing to retry,
     *  and the line must not answer a touch with a decryption bound to fail again. */
    @Test fun `a failure retries only when it has something to retry`() {
        val crypto = codeLines(block("private fun PgpStatusCard("))
        assertEquals(
            listOf("is CryptoUiState.Failed -> HeaderCardLine(onClick = onAction, enabled = crypto.message != null) {"),
            crypto.filter { it.startsWith("is CryptoUiState.Failed ->") },
        )
        assertEquals(
            "the retry label is drawn only where the line is live",
            listOf("if (crypto.message != null) {", "HeaderCardAction(stringResource(R.string.message_retry))"),
            crypto.dropWhile { !it.startsWith("is CryptoUiState.Failed ->") }
                .filter { "crypto.message != null" in it || "message_retry" in it }.drop(1),
        )
    }

    @Test fun `the seal of a verdict is tinted by its tone`() {
        val crypto = codeLines(block("private fun PgpStatusCard("))
        assertEquals(
            "an icon without the verdict's tone draws a bad signature in the neutral grey of a good one",
            1,
            crypto.count { it == "verdict != null -> HeaderCardIcon(verdict.icon.vector(), tint = verdict.tone.color())" },
        )
    }

    @Test fun `the verdict's text wraps in its tone`() {
        val text = verdictText()
        assertEquals(
            "the verdict's text without its tone draws a bad signature in the neutral grey of a good one",
            1,
            text.count { it == "color = verdict.tone.color()," },
        )
        assertEquals(
            "the verdict's warning comes after the signer: cut to one line, it disappears",
            emptyList<String>(),
            text.filter { it.startsWith("maxLines") || it.startsWith("overflow") },
        )
    }

    @Test fun `the verdict prints the signer bounded by cardSignerIdentity`() {
        assertEquals(
            "an unbounded signer identity pushes the verdict's warning out of view",
            1,
            verdictText().count {
                it == "text = stringResource(verdict.line, crypto.result.signatureUserId?.let(::cardSignerIdentity) ?: \"?\"),"
            },
        )
    }

    /** The verdict's `Text(` call, from its `(` line to its `)` line: inside the `Decrypted` branch,
     *  the first `if (verdict != null) {`, which must open a `Text(` on its next code line. The branch
     *  ends at the next `CryptoUiState` case; the chevron's `if (verdict != null) {` comes later. */
    private fun verdictText(): List<String> {
        val crypto = codeLines(block("private fun PgpStatusCard("))
        val start = crypto.indexOfFirst { it.startsWith("is CryptoUiState.Decrypted ->") }
        assertTrue("PgpStatusCard has no Decrypted branch", start >= 0)
        val after = crypto.drop(start + 1)
        val branch = after.take(after.indexOfFirst { it.startsWith("is CryptoUiState.") || it.startsWith("CryptoUiState.") }
            .let { if (it < 0) after.size else it })
        val open = branch.indexOf("if (verdict != null) {")
        assertTrue(
            "the Decrypted branch no longer opens its verdict with `if (verdict != null) {` then `Text(`. Was:\n" +
                branch.joinToString("\n"),
            open >= 0 && branch.getOrNull(open + 1) == "Text(",
        )
        val close = branch.withIndex().indexOfFirst { (i, line) -> i > open + 1 && line == ")" }
        assertTrue("the verdict's Text( is never closed by a `)` line", close >= 0)
        return branch.subList(open + 1, close + 1)
    }

    @Test fun `the action is primary text on one line, of the sentence's line height`() {
        val action = codeLines(block("private fun HeaderCardAction("))
        listOf(
            "style = MaterialTheme.typography.labelLarge,",
            "maxLines = 1,",
            "overflow = TextOverflow.Ellipsis,",
        ).forEach { rule ->
            assertEquals("HeaderCardAction must carry `$rule`. Was:\n${action.joinToString("\n")}", 1, action.count { it == rule })
        }
        assertEquals(
            "the action's colour is primary unless the caller greys an action in flight",
            listOf("color: Color = MaterialTheme.colorScheme.primary,", "color = color,"),
            action.filter { it.startsWith("color") },
        )
    }

    @Test fun `the icon is 20 dp, muted, and followed by 14 dp`() {
        val icon = codeLines(block("private fun HeaderCardIcon("))
        listOf(
            "tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,",
            "modifier = Modifier.size(20.dp),",
            "Spacer(Modifier.width(14.dp))",
        ).forEach { rule ->
            assertEquals("HeaderCardIcon must carry `$rule`. Was:\n${icon.joinToString("\n")}", 1, icon.count { it == rule })
        }
    }

    // -- reading the source ------------------------------------------------------------------------

    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun block(signature: String): String {
        val text = SOURCE.readText()
        val at = text.indexOf(signature)
        check(at >= 0) { "MessageScreen.kt no longer declares `$signature` — was it renamed or moved out?" }
        val end = Regex("""\n(@Composable|@OptIn|private fun |internal fun |fun |/\*\*)""").find(text, at + 1)
            ?.range?.first ?: text.length
        return text.substring(at, end)
    }

    private companion object {
        val ROW_COMPOSABLES = listOf(
            "PgpStatusCard", "ImagesStrip", "UnsubscribeStrip", "ReadReceiptStrip", "SenderUnverifiedStrip",
        )

        val NESTED_TARGETS = listOf("TextButton(", "OutlinedButton(", "Button(", "IconButton(")

        val SOURCE: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }
    }
}
