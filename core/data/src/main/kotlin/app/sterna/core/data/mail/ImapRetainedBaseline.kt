package app.sterna.core.data.mail

/**
 * What an IMAP push pass remembers of a folder: its page, plus every row the cache holds inside the
 * notification candidate window. `NewMailNotifier` replaces the stored baseline with this, and the
 * page is only the newest fifty: a message that slid to the fifty-first place would leave the
 * baseline and be announced as new the moment a deletion pulled it back onto the page (#278).
 *
 * The cache is read after the pass's upsert, so mail this pass brought in is remembered too; it is
 * still announced, the notifier comparing against the baseline of the pass before. A null page
 * stays null: widened by the cache, a page that cannot vouch for the folder would overwrite the
 * stored baseline with the cache alone.
 */
internal fun imapRetainedBaseline(pageIds: List<String>?, cachedIds: List<String>): List<String>? =
    pageIds?.let { (it + cachedIds).distinct() }
