package app.sterna.ui.message

import app.sterna.core.data.text.htmlEscape
import app.sterna.ui.compose.BLOCKQUOTE_TAG

/**
 * Fold the TRAILING quoted history of a rich HTML body behind a native `<details>`.
 * `<details>`/`<summary>` is markup, not behaviour: the WebView opens it on a tap by itself, so the
 * reader keeps `javaScriptEnabled = false` and its CSP. The flip side: the open/closed state lives
 * in the DOM only, so the fold is closed on every open and has no setting.
 * ONE quote is folded — the last top-level one, at [BLOCKQUOTE_TAG]'s depth, and only when nothing
 * visible follows it, as K-9 and Gmail do. Three guards return [html] untouched: a blank [label],
 * nothing visible before the quote, and markup this one-pass scan cannot balance. Losing body is
 * the only unacceptable outcome; not folding is not one.
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
            // `depth == 0` is what makes the span TOP-LEVEL. Without it `openedAt` walks inwards to
            // the innermost quote while the span still ends on the outermost close, so the <details>
            // opens INSIDE the history and its first levels stay unfolded.
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
 * Markup that carries no text to the screen is dropped before the tags are stripped, which a bare
 * `replace(Regex("<[^>]*>"), "")` would not do: a body that is one forwarded quote usually arrives
 * as a complete document, and `body{margin:0}` in its `<head><style>` is text to any regex.
 *
 * An `<img>` deliberately does not count: a remote image is blocked by default, and the cost is
 * asymmetric — counting it wrongly empties the page, ignoring it wrongly only costs a fold.
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
