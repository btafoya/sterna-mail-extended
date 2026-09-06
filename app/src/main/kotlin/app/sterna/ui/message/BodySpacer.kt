package app.sterna.ui.message

import java.util.Locale

/**
 * How much room the document reserves for a Compose layer drawn OVER it, as a CSS length (#171).
 * The unit is what this function exists for. A CSS pixel is not a screen pixel — the WebView paints
 * at `getScale()`, and a message with its own `<meta viewport>` opens below scale 1 — so a blank
 * reserved in `px` paints short and the opaque layer covers message; `vh` is measured against the
 * LAYOUT viewport, so that scale cancels out. A scale applied WITHOUT laying the page out again is
 * invisible to every CSS length, and moving the overlays instead was refused.
 *
 * @return a CSS length, unit included, or `"0"` while either measurement is still missing.
 */
internal fun bodySpacerCss(reservedPx: Int, viewportHeightPx: Int): String {
    if (reservedPx <= 0 || viewportHeightPx <= 0) return "0"
    // Locale.ROOT, NOT the device's locale: a comma-decimal locale formats "26,4114vh", which the
    // WebView cannot parse, so the blank falls to zero and the header covers the top of every message.
    return String.format(Locale.ROOT, "%.4f", 100.0 * reservedPx / viewportHeightPx) + "vh"
}
