package app.sterna.core.data.text

/**
 * [name] made safe for a file name, keeping its letters — accents, Cyrillic, anything unicode —
 * and replacing only what a path cannot carry: `/ \ : * ? " < > |` and every control character,
 * each by `_`. Trimmed at both ends, cut at [maxLength] UTF-16 code units — never through a
 * surrogate pair: a lone high surrogate is not a character a provider can name a file with — and
 * [fallback] when nothing is left (null, empty, whitespace). No extension: the caller adds its own.
 *
 * Not the `[^A-Za-z0-9._-]` regex of `StorageRepository.cacheAttachment` (and its five
 * siblings): that one names a cache file nobody reads, and it turns "Réunion décalée" into
 * `R_union_d_cal_e` — a name the user is meant to find in her Downloads. Those six regexes stay
 * where they are; this is a different job.
 */
fun safeFileName(name: String?, fallback: String, maxLength: Int = 80): String {
    val cleaned = name.orEmpty().trim()
        .map { c -> if (c in HOSTILE || c.isISOControl()) '_' else c }
        .joinToString("")
        .take(maxLength)
        .dropLastWhile { it.isHighSurrogate() }
        .trim()
    return cleaned.ifEmpty { fallback }
}

/** What no file system accepts in a name, across Android, Windows and the picker's providers. */
private const val HOSTILE = "/\\:*?\"<>|"
