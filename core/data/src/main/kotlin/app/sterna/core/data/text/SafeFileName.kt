package app.sterna.core.data.text

/**
 * [name] made safe for a file name: keeps unicode letters, replaces only `/ \ : * ? " < > |` and
 * control characters with `_`. Trimmed, cut at [maxLength] UTF-16 units without splitting a
 * surrogate pair, [fallback] when nothing is left. No extension: the caller adds its own.
 *
 * Not the same job as `StorageRepository.cacheAttachment`'s `[^A-Za-z0-9._-]` regex (and its
 * five siblings), which names a cache file nobody reads and would mangle unicode names users see.
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
