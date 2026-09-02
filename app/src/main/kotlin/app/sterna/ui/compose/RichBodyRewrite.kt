package app.sterna.ui.compose

import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.remapAfterEdit

/**
 * Applies a signature [rewrite] (a swap or an insertion, see `SignatureChange`) to a rich body
 * and carries the user's styling across it: the rewrite works on the text alone, and the
 * edit it made is found again by diffing, so the spans before the signature stay, those after it
 * shift, and one buried in the old signature goes with it. `null` when [rewrite] declines.
 */
fun rewriteRichBody(rich: RichBody, caret: Int, rewrite: (String) -> String?): RichBody? =
    rewrite(rich.text)?.let { remapAfterEdit(rich, it, Span(caret, caret), Span(caret, caret), null) }
