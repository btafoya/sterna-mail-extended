package app.sterna.core.data.text

/*
 * Pure helpers for a draft's stored HTML (#131): what a save writes, what a reopen reads back,
 * and whether a body would lose fidelity if flattened.
 *
 * Lives in `core:data`: `localDraftPrefill` (LocalDraftReopen.kt) shares these with the other
 * four reopen routes so all read stored HTML identically — a copy in `:app` could drift silently.
 */

/**
 * The HTML part a draft save must store for [body], or null when there is nothing to store.
 *
 * Null whenever [body] has no styling, list or link (#131): keeps the exact pre-#131 wire shape
 * so other clients still read a plain draft as plain text. Round-trips exactly through [fromHtml].
 */
fun draftHtmlToSave(body: RichBody): String? = if (body.isPlain) null else toHtml(body)

/**
 * The HTML this editor can use, or null — the single decision behind [richBodyFrom] and
 * [draftHtmlIsLossy], which must never disagree about the same part.
 *
 * Usable means parses AND carries text: `fromHtml("")`/whitespace/`"<br>"` parse to an EMPTY
 * body, not null, and treating that as usable would open the composer blank and destroy an
 * original that exists nowhere else — present-but-unreadable is an unknown that keeps it.
 */
private fun usableDraftHtml(html: String?): RichBody? =
    html?.let(::fromHtml)?.takeIf { it.text.isNotBlank() }

/**
 * The body a composer reopens on: [html] parsed when usable, else [plainText].
 *
 * HTML first — the text part is the same letters stripped, so reading it first would silently
 * lose every span. [plainText] is a lambda: only the fallback when [usableDraftHtml] is null.
 */
fun richBodyFrom(html: String?, plainText: () -> String): RichBody =
    usableDraftHtml(html) ?: RichBody.plain(plainText())

/**
 * Whether [html] is an HTML body this editor cannot give back faithfully: present, and
 * [usableDraftHtml] cannot use it.
 *
 * The exact negation of what [richBodyFrom] reads, asked twice so the two never disagree —
 * disagreeing would mean showing the text part while marking the draft faithful, destroying the
 * original in favour of a copy that dropped its styling.
 */
fun draftHtmlIsLossy(html: String?): Boolean = html != null && usableDraftHtml(html) == null
