package app.sterna.ui.message

import app.sterna.core.data.text.htmlEscape
import app.sterna.ui.compose.BLOCKQUOTE_TAG

/**
 * Fold the TRAILING quoted history of a rich HTML body behind a native `<details>`, so a long reply
 * chain opens on the new message instead of on the history the reader already read.
 *
 * ⭐ `<details>`/`<summary>` is markup, not behaviour: the WebView opens it on a tap ALL BY ITSELF.
 * That is the whole reason this is not a Compose affordance or a JS toggle — it leaves the reader's
 * `javaScriptEnabled = false` and its `default-src 'none'` CSP exactly as they are, and adds no
 * channel from the message to the app.
 * ⚠ The flip side, accepted deliberately: the open/closed state lives in the DOM only. Kotlin
 * cannot read it, cannot set it, and cannot persist it — so the fold is closed on every open, is
 * remembered nowhere, and has no setting. There is nothing here to remember it WITH.
 *
 * Depth is read with [BLOCKQUOTE_TAG], the counter the reply quoter already uses, so "level" means
 * the same thing on both sides of the app. ⛔ NOT `htmlToQuotedText`, which flattens to text: the
 * reader needs the HTML back.
 *
 * **ONE quote is folded — the last top-level one, and only when nothing the reader can see comes
 * after it.** That is what K-9 and Gmail both do, and the two shapes it deliberately leaves alone
 * are the reason:
 *
 *  - an INTERLEAVED reply (`question / answer / question / answer`, the house style of technical
 *    mailing lists) would otherwise fold every question away and leave a column of answers to
 *    nothing;
 *  - a BOTTOM-POSTED reply, where the history comes first, folds nothing: hiding the top of the
 *    page and leaving the bottom is not what any reference client does.
 *
 * Three guards, and they all end the same way — return [html] untouched:
 *
 *  1. a blank [label] would render an empty `<summary>`: a button with no word in it, which no
 *     screen reader can announce;
 *  2. nothing the reader can SEE before that quote (a forwarded bulletin quoted whole) would fold
 *     the ENTIRE message away and show a bare button on an empty page;
 *  3. markup this one-pass scan cannot balance — depth going negative, or not back to zero at the
 *     end. ⛔ Losing body is the only unacceptable outcome here; not folding is not one. The result
 *     is always the input with `<details …><summary>…</summary>` and `</details>` INSERTED around
 *     one whole span, never a character of it removed or rewritten.
 */
internal fun foldTopLevelQuotes(html: String, label: String): String {
    // Guard 1 — a mute button.
    if (label.isBlank()) return html

    // One pass: remember where each top-level blockquote opens and where it closes again.
    val spans = ArrayList<IntRange>()
    var depth = 0
    var openedAt = -1
    for (tag in BLOCKQUOTE_TAG.findAll(html)) {
        if (tag.value.startsWith("</")) {
            depth -= 1
            // Guard 3a — a closing tag with nothing open. The markup is not what this scan
            // assumes, so it keeps its hands off the whole body.
            if (depth < 0) return html
            if (depth == 0 && openedAt >= 0) {
                spans += openedAt..tag.range.last
                openedAt = -1
            }
        } else {
            // ⛔ `depth == 0` is what makes the span a TOP-LEVEL one, and it is the whole rule.
            // Without it `openedAt` walks inwards to the innermost quote while the span still ends
            // on the outermost close: the <details> would then open INSIDE the history and the
            // first levels of it would stay unfolded on screen — the reported defect, intact.
            if (depth == 0) openedAt = tag.range.first
            depth += 1
        }
    }
    // Guard 3b — a quote that never closes. Where it ends is a guess, and guessing here truncates.
    if (depth != 0 || openedAt >= 0) return html

    val quote = spans.lastOrNull() ?: return html
    // Only a TRAILING quote folds: anything the reader can see after it means this is an
    // interleaved or bottom-posted reply, and those read worse folded than open.
    if (hasVisibleContent(html.substring(quote.last + 1))) return html
    // Guard 2 — is there any message ABOVE it? If not, folding empties the screen.
    if (!hasVisibleContent(html.substring(0, quote.first))) return html

    return html.substring(0, quote.first) +
        "<details class=\"s-quote\"><summary>${htmlEscape(label)}</summary>" +
        html.substring(quote.first, quote.last + 1) +
        "</details>" +
        html.substring(quote.last + 1)
}

/**
 * Whether [fragment] holds anything the reader would SEE — the test behind guard 2.
 *
 * ⛔ The markup that CARRIES no text to the screen is dropped before the tags are stripped, and
 * that is the point of this function rather than a bare `replace(Regex("<[^>]*>"), "")`: a mail
 * whose whole body is a forwarded quote almost always arrives as a complete document, and the
 * `body{margin:0}` sitting in its `<head><style>` is text by any regex — enough to satisfy guard 2
 * and fold the only thing on the page away. What the reader would then see is a button on a blank
 * screen.
 *
 * ⛔ An `<img>` deliberately does NOT count. A remote image is BLOCKED by default in this reader,
 * so `<img src="https://…/pixel.gif">` above a quote is nothing at all on screen — and the cost of
 * getting this wrong is asymmetric: counting it wrongly empties the page, ignoring it wrongly only
 * costs a fold, which this branch has already declared an acceptable outcome.
 */
private fun hasVisibleContent(fragment: String): Boolean =
    fragment
        .replace(INVISIBLE_MARKUP, " ")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .isNotBlank()

/** Elements whose TEXT never reaches the screen, plus comments — dropped content and all. */
private val INVISIBLE_MARKUP = Regex(
    "<!--.*?-->|<(head|style|script|title)\\b[^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
