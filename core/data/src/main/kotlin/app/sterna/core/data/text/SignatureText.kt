package app.sterna.core.data.text

/**
 * The plain-text half of an imported HTML signature. The composer finds the signature block by this
 * text, so an empty one means no block and the HTML is never sent (#260): an image-only signature
 * falls back to its non-blank `alt` values, else `[image]`. HTML with neither text nor image stays "".
 *
 * The fallback lives here, not in `htmlToText`, which also flattens what the reader, quotes and
 * previews show, where a placeholder for every image is noise.
 */
fun signatureText(html: String): String {
    val text = htmlToText(html)
    if (text.isNotEmpty() || !IMG_TAG.containsMatchIn(html)) return text
    val alts = IMG_TAG.findAll(html)
        .mapNotNull { tag -> ALT_ATTR.find(tag.value)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } }
        .map { unescapeEntities(it).trim() }
        .filter { it.isNotEmpty() }
        .toList()
    return if (alts.isEmpty()) "[image]" else alts.joinToString(" ")
}

private val IMG_TAG = Regex("(?i)<img\\b[^>]*>")

private val ALT_ATTR = Regex("(?i)\\salt\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
