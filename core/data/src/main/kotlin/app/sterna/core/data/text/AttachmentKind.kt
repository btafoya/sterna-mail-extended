package app.sterna.core.data.text

/** What an attachment is to the person reading the list. Closed: the app draws one icon per entry. */
enum class AttachmentKind {
    IMAGE, PDF, AUDIO, VIDEO, ARCHIVE, TEXT, DOCUMENT, SPREADSHEET, PRESENTATION, MESSAGE, CALENDAR, CONTACT, OTHER,
}

/**
 * The kind of an attachment called [name] that the sender declared as [declared], [lookup] being
 * the device's extension table as for [attachmentMimeType]. The type the viewer would receive is
 * asked first, so a declared type we recognise is believed over the name, as the viewer believes it;
 * only when that type tells us nothing does the name speak, through the device's table and then
 * through [BY_EXTENSION].
 */
fun attachmentKind(declared: String?, name: String?, lookup: (String) -> String?): AttachmentKind {
    kindOfType(attachmentMimeType(declared, name, lookup))?.let { return it }
    val extension = extensionOf(name) ?: return AttachmentKind.OTHER
    return kindOfType(essenceOf(lookup(extension)))
        ?: BY_EXTENSION[extension]
        ?: AttachmentKind.OTHER
}

/** The extension of [name] in capitals, to stand for a kind we cannot name; null when it has none. */
fun attachmentExtensionLabel(name: String?): String? = extensionOf(name)?.uppercase()

/**
 * Exact types are tried before the families: `text/calendar`, `text/vcard`, `text/csv` and
 * `text/rtf` all belong to `text/` and must not be read as plain text.
 */
private fun kindOfType(type: String): AttachmentKind? {
    EXACT[type]?.let { return it }
    PREFIXES.firstOrNull { (prefix, _) -> type.startsWith(prefix) }?.let { return it.second }
    return FAMILIES[type.substringBefore('/')]
}

private val EXACT: Map<String, AttachmentKind> = buildMap {
    put("application/pdf", AttachmentKind.PDF)
    put("application/ogg", AttachmentKind.AUDIO)
    listOf("message/rfc822", "message/global").forEach { put(it, AttachmentKind.MESSAGE) }
    listOf("text/calendar", "text/x-vcalendar", "application/ics").forEach { put(it, AttachmentKind.CALENDAR) }
    listOf("text/vcard", "text/x-vcard", "text/directory").forEach { put(it, AttachmentKind.CONTACT) }
    listOf("text/csv", "text/tab-separated-values").forEach { put(it, AttachmentKind.SPREADSHEET) }
    listOf("application/msword", "application/rtf", "text/rtf").forEach { put(it, AttachmentKind.DOCUMENT) }
    listOf(
        "application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
        "application/x-rar-compressed", "application/vnd.rar", "application/gzip", "application/x-gzip",
        "application/x-tar", "application/x-gtar", "application/x-compressed-tar", "application/x-bzip2",
        "application/x-xz", "application/zstd",
    ).forEach { put(it, AttachmentKind.ARCHIVE) }
}

/** The office formats carry a sub-type per variant (template, macro-enabled), so they match by prefix. */
private val PREFIXES: List<Pair<String, AttachmentKind>> = listOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml." to AttachmentKind.DOCUMENT,
    "application/vnd.oasis.opendocument.text" to AttachmentKind.DOCUMENT,
    "application/vnd.ms-word." to AttachmentKind.DOCUMENT,
    "application/vnd.openxmlformats-officedocument.spreadsheetml." to AttachmentKind.SPREADSHEET,
    "application/vnd.oasis.opendocument.spreadsheet" to AttachmentKind.SPREADSHEET,
    "application/vnd.ms-excel" to AttachmentKind.SPREADSHEET,
    "application/vnd.openxmlformats-officedocument.presentationml." to AttachmentKind.PRESENTATION,
    "application/vnd.oasis.opendocument.presentation" to AttachmentKind.PRESENTATION,
    "application/vnd.ms-powerpoint" to AttachmentKind.PRESENTATION,
)

private val FAMILIES: Map<String, AttachmentKind> = mapOf(
    "image" to AttachmentKind.IMAGE,
    "audio" to AttachmentKind.AUDIO,
    "video" to AttachmentKind.VIDEO,
    "text" to AttachmentKind.TEXT,
)

/** Names this app gives a kind to without relying on the device's table knowing them. */
private val BY_EXTENSION: Map<String, AttachmentKind> = mapOf(
    "eml" to AttachmentKind.MESSAGE,
    "ics" to AttachmentKind.CALENDAR,
    "vcs" to AttachmentKind.CALENDAR,
    "vcf" to AttachmentKind.CONTACT,
)
