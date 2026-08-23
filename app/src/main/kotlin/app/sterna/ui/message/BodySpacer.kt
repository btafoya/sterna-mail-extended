package app.sterna.ui.message

import java.util.Locale

/**
 * How much room the document reserves for a Compose layer drawn OVER it, as a CSS length — pulled
 * out of `ConversationBody` so a test can run the decision instead of re-deriving it (#171).
 *
 * The reader paints two opaque layers on top of the body's WebView: the collapsing header at the
 * top, the Reply/Forward bar at the bottom. Their room is reserved inside the HTML document, as two
 * `<div>`s, so the content begins below the header and the last line can scroll clear of the bar.
 *
 * ⛔ **The unit is what this function exists for.** The room was reserved in CSS PIXELS, and a CSS
 * pixel is not a screen pixel: the WebView paints at `getScale()`. A newsletter carrying its own
 * `<meta name="viewport">` opens below scale 1 — measured at the bench: density 2.625, scale
 * 1.5752913 against a device scale of 2.625, i.e. a page scale of 0.600 — so a blank of 205 CSS px
 * painted 323 screen px where the header covered 538. The missing 218 px were message, hidden under
 * an opaque layer, with nothing left to scroll to get them back.
 *
 * `vh` is measured against the page's LAYOUT viewport, so a scale the browser chose while laying
 * the page out cancels out of it. A page laid out at scale `s` has a layout viewport of
 * `viewportHeightPx / s` CSS pixels, so `1vh` is `viewportHeightPx / 100` CSS pixels, painted back
 * at `s`: `viewportHeightPx / 100` SCREEN pixels, whatever `s` is. A blank of
 * `100 · reservedPx / viewportHeightPx` vh therefore measures exactly [reservedPx] screen pixels —
 * no scale observed, no layer moved, no document rebuilt on a pinch.
 *
 * ⛔ **What it does NOT cover, and cannot.** A scale the browser applies WITHOUT laying the page out
 * again is invisible to every CSS length there is, `vh` included, because it scales the whole
 * document uniformly — blank and body together. Two paths reach it: a pinch, and the shrink-to-fit
 * of `loadWithOverviewMode` when a message asks for `width=device-width` and then overflows it. In
 * both, the blank shrinks on screen exactly as the body does and the overlay covers the difference
 * again. The only cure would be to move the overlays, which was measured at the bench and refused:
 * it costs the subject and the sender, which live nowhere else. See the branch journal, `C4`.
 *
 * @param reservedPx height of the overlaying layer, in device pixels.
 * @param viewportHeightPx height of the body's own view, in device pixels.
 * @return a CSS length, unit included, or `"0"` while either measurement is still missing — the
 *   reader then reserves nothing and behaves exactly as it did before the first layout pass.
 */
internal fun bodySpacerCss(reservedPx: Int, viewportHeightPx: Int): String {
    if (reservedPx <= 0 || viewportHeightPx <= 0) return "0"
    // ⛔ Locale.ROOT, NOT the device's locale. A French or German device formats this as
    // "26,4114vh"; the WebView cannot parse that declaration, drops it, the blank falls to zero and
    // the opaque header covers the top of EVERY message — in every comma-decimal language.
    // Four decimals is finer than a pixel on any viewport this reader will ever have.
    return String.format(Locale.ROOT, "%.4f", 100.0 * reservedPx / viewportHeightPx) + "vh"
}
