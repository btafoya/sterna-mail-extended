package app.sterna.core.data.text

/*
 * The three decisions a STORED rich body needs (#131), pure and executable: what a draft save
 * writes as its HTML part, what a reopen reads back out of one, and whether an HTML body the
 * composer is about to flatten costs anything.
 *
 * ⛔ In `core:data` and not beside the composer, although the composer is the only screen that
 * shows them: `localDraftPrefill` (LocalDraftReopen.kt) reopens the phone's own drafts and lives
 * HERE, and it must read the stored HTML with the very function the other four routes use — the
 * server draft, the outbox, a cancelled scheduled send and the undo of a send. A second copy over
 * in `:app` would be free to drift from this one, and the drift is silent: the styling simply
 * stops coming back on one route.
 */

/**
 * The HTML part a draft save must store for [body], or `null` when there is nothing to store.
 *
 * ⛔ `null` for a body with no styling, no list AND no link (#131) — `isPlain` counts all three —
 * and that is the whole point: a plain draft is written EXACTLY as it was before #131 — same row,
 * same `Email/set`, same APPEND, no `text/html` part anywhere. Storing `toHtml` of a plain body
 * would give every draft ever saved a second MIME part, and turn a draft another client reads as
 * text into one it reads as HTML.
 *
 * What comes out is the closed subset [toHtml] writes, so [fromHtml] reads it back exactly — the
 * round trip is what makes a reopened draft keep its bold instead of showing markup.
 */
fun draftHtmlToSave(body: RichBody): String? = if (body.isPlain) null else toHtml(body)

/**
 * The HTML this editor can actually work from, or null — ⛔ **the ONE decision behind both
 * [richBodyFrom] and [draftHtmlIsLossy]**, so "what the composer opens on" and "may the original be
 * destroyed" can never answer differently about the same part.
 *
 * Usable means: it parses as the closed subset [fromHtml] reads, AND what comes out carries text.
 *
 * ⛔ **The second half is not a nicety, it is a data-loss guard.** `fromHtml("")` answers an EMPTY
 * body, not null, and so do `" "`, `"\t"` and `"<br>"`. Read as "it parses, so the draft is
 * faithful", a server draft whose `text/html` part is empty or whitespace — a part another client
 * is free to write — would open the composer BLANK (the text part is never even read, the fallback
 * lambda is not called), be judged reproducible, and have its original DESTROYED by the next save.
 * The text of that draft exists nowhere else. Before #131 any HTML part at all made the reopen
 * lossy, so the original was kept; this keeps that answer for every part we cannot read.
 *
 * ⭐ An HTML that is PRESENT but says nothing is an UNKNOWN, and an unknown keeps the original —
 * the same rule the read-receipt leg states at `DraftSave.kt:170`: present-but-unreadable is not
 * "nothing to lose".
 */
private fun usableDraftHtml(html: String?): RichBody? =
    html?.let(::fromHtml)?.takeIf { it.text.isNotBlank() }

/**
 * The body a composer reopens on: [html] parsed when it is ours and carries text, and [plainText]
 * otherwise.
 *
 * ⭐ The HTML FIRST, on every route that has one. It is the only place the styling exists — the
 * text part of the same draft is the same letters with the styling stripped — so reading the text
 * first (which is what `bodySource` does for a two-part JMAP draft) silently loses every span.
 *
 * ⛔ And [plainText] is a lambda because it is the FALLBACK: an HTML that is not ours (a draft
 * written in Thunderbird, a forward's carried original, an imported HTML signature), or one that
 * carries no text at all (see [usableDraftHtml]), is not read and the stored text is what the
 * editor gets, exactly as it did before #131. It is not a failure — this editor cannot show
 * `<table>` — it only means the reopen is lossy, which [draftHtmlIsLossy] is what says.
 */
fun richBodyFrom(html: String?, plainText: () -> String): RichBody =
    usableDraftHtml(html) ?: RichBody.plain(plainText())

/**
 * Whether an HTML body [html] is one this editor cannot give back — the fidelity term of a reopen
 * (`reopenedDraftIsLossy(hasHtmlBody = …)`).
 *
 * ⭐ "There is an HTML part AND [usableDraftHtml] cannot use it". Until #131 the term was
 * `htmlContent() != null`: ANY html body made the reopen lossy, because the editor was plain text
 * and flattened it. Now a draft this app itself styled comes back with its spans, so it IS
 * reproducible and the original may be replaced — while everything else still keeps its original,
 * and the user gets a duplicate in Drafts rather than a message stripped of a formatting nobody
 * was shown losing.
 *
 * ⛔ `null` is NOT lossy: a draft with no HTML part at all loses nothing. ⛔ An HTML that fails to
 * parse is lossy whatever its text part says. ⛔ And so is one that parses to NOTHING — empty,
 * whitespace, a lone `<br>`: it is present, it says nothing, and reading that as fidelity opens
 * the composer blank and destroys the original behind it. Present-but-unreadable is an unknown,
 * and an unknown is the safe direction: the original is kept, the duplicate is the price.
 *
 * ⛔ It is exactly the negation of what [richBodyFrom] READ — one decision, asked twice — because
 * the two answering differently is the destroy: "the composer shows the text part" plus "the
 * draft is faithful" is the original expunged in favour of a copy of something else.
 */
fun draftHtmlIsLossy(html: String?): Boolean = html != null && usableDraftHtml(html) == null
