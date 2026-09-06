package app.sterna.ui.message

import app.sterna.core.data.text.htmlEscape
import app.sterna.core.jmap.model.EmailAddress

/**
 * The header lines of the message to print, ALREADY formatted by the caller: the subject resolved
 * (or the localised "(no subject)"), each participant as "Name <address>" and joined by ", ", the
 * date through `MailDates.formatFull`. Nothing here is escaped yet — [buildPrintDocument] does it.
 */
internal data class PrintHeader(
    val subject: String,
    val from: String,
    val to: String,
    /** "" when nobody is in Cc — the line is then left out of the document entirely. */
    val cc: String,
    val date: String,
)

/** The already-localised labels of the header lines, passed in like every string here. */
internal data class PrintLabels(val from: String, val to: String, val cc: String, val date: String)

/**
 * The complete HTML document to hand to Android's print service — a document DISTINCT from the
 * reader's ([buildHtmlDocument]), on purpose: that one carries two `vh` bands for the collapsing
 * header and the reply bar, and in dark theme inverts the whole page. Paper has neither a header
 * to clear nor a dark theme, so this document is always light and reserves nothing.
 *
 * Pure: no `Context`, no resource. [body] is the fragment [readerBody] already decided for the
 * screen, so what gets printed is what was being read — plain text stays a `<pre class="plain">`,
 * a rich body gets its inline images embedded exactly as the reader does it.
 *
 * ONE deliberate exception to "what was being read": the QUOTED history. The reader folds the
 * trailing quoted block behind a `<details>` ([foldTopLevelQuotes]); paper and PDF get it in full,
 * always, because a sheet does not unfold and a print that silently dropped the quote would be a
 * print of half the message. That holds by construction and not by a flag: the fold is called only
 * from [buildHtmlDocument], never from here, and the CSS below carries `pre.plain` and `p.s-note`
 * only — a `details.s-quote` rule does not exist in this document. "It is not called from here"
 * is invisible to whoever edits this function, so it is PINNED: see PrintDocumentTest's
 * `the print document never folds the quoted history away`.
 */
internal fun buildPrintDocument(
    header: PrintHeader,
    labels: PrintLabels,
    body: ReaderBody,
    inlineImages: Map<String, String> = emptyMap(),
): String {
    // The same two rewrites the reader's document makes on a rich body, and only on one: the
    // message's own dark-mode styles are defanged here too — paper is white, and a marketing email
    // must not print its dark variant because the device happens to be in dark theme.
    val inner = if (body.richHtml) {
        neutraliseDarkModeStyles(embedInlineImages(body.fragment, inlineImages))
    } else {
        body.fragment
    }
    val lines = StringBuilder()
    fun line(label: String, value: String) {
        lines.append("<p class=\"h\"><b>").append(htmlEscape(label)).append("</b> ")
            .append(htmlEscape(value)).append("</p>\n")
    }
    line(labels.from, header.from)
    line(labels.to, header.to)
    if (header.cc.isNotBlank()) line(labels.cc, header.cc)
    line(labels.date, header.date)
    return """
        <!DOCTYPE html><html><head>
        <meta charset="utf-8">
        <meta name="color-scheme" content="only light">
        $CSP_META
        <style>
          html { color-scheme: only light; }
          html, body { background: #ffffff; color: #111111; }
          body { font-family: sans-serif; line-height: 1.45; margin: 0;
                 word-wrap: break-word; overflow-wrap: break-word; }
          @page { margin: 18mm; }
          img { max-width: 100%; height: auto; }
          /* The two classes readerBody emits, styled as the reader styles them: what was read
             in sans-serif prints in sans-serif, and the "derived text" line stays a note. */
          pre.plain { white-space: pre-wrap; word-wrap: break-word; font-family: sans-serif; }
          p.s-note { margin: 0 0 12px; font-size: 0.85em; opacity: 0.7; }
          h1 { font-size: 1.3em; margin: 0 0 0.5em 0; }
          p.h { margin: 0.15em 0; }
          hr { border: 0; border-top: 1px solid #cccccc; margin: 1em 0; }
        </style></head><body>
        <h1>${htmlEscape(header.subject)}</h1>
        $lines<hr>
        $inner
        </body></html>
    """.trimIndent()
}

/**
 * Replace every `cid:` reference in [html] by its data URI — the message's own inline images,
 * which no WebView could fetch by itself. Shared by the reader's document and the print one so the
 * two cannot drift: both spellings, `cid:x` then `cid:<x>`, in that order.
 */
internal fun embedInlineImages(html: String, inlineImages: Map<String, String>): String {
    var out = html
    inlineImages.forEach { (cid, dataUri) ->
        out = out.replace("cid:$cid", dataUri).replace("cid:<$cid>", dataUri)
    }
    return out
}

/**
 * The name the print job carries in the system's print queue and in the PDF it may save: the
 * message's subject, trimmed, and the app's name when there is none — a blank job name is what
 * Android shows as an empty row.
 */
internal fun printJobName(subject: String, appName: String): String = subject.trim().ifBlank { appName }

/**
 * Defang the message's own `prefers-color-scheme: dark` media queries by appending an always-false
 * condition. Android WebView ignores a declared `color-scheme` and matches the device's theme, so
 * without this a marketing email renders its dark variant — on the reader's page (where the invert
 * then turns it light, the "white band" bug) and on paper alike. Shared by both documents.
 */
internal fun neutraliseDarkModeStyles(html: String): String = html.replace(
    Regex("""prefers-color-scheme\s*:\s*dark""", RegexOption.IGNORE_CASE),
    "prefers-color-scheme:dark) and (max-width:0px",
)

/**
 * A participant as paper wants it: `Name <address>`, or the bare address — unlike the screen's
 * [EmailAddress.display], which shows the name alone because the address is one tap away. On paper
 * nothing is one tap away.
 */
internal fun printAddress(address: EmailAddress): String {
    val name = address.name?.trim().orEmpty()
    return if (name.isEmpty()) address.email else "$name <${address.email}>"
}

/** The participants of one header line, joined the way the header on screen joins them. */
internal fun printAddresses(addresses: List<EmailAddress>): String = addresses.joinToString { printAddress(it) }
