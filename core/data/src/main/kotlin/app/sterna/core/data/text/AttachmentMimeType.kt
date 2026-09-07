package app.sterna.core.data.text

/**
 * The type to hand a viewer for an attachment called [name] that the sender declared as [declared].
 * An app's intent filter matches on a type, and nothing reads the bytes to find one, so a part the
 * sender declared generic reaches whatever app claims the generic type. The framework's table is
 * injected as [lookup] because answering needs a device and this decision has to run without one.
 */
fun attachmentMimeType(declared: String?, name: String?, lookup: (String) -> String?): String {
    val essence = essenceOf(declared)
    if (essence.isEmpty() || essence in GENERIC) {
        val found = extensionOf(name)?.let { essenceOf(lookup(it)) }
        if (!found.isNullOrEmpty()) return found
    }
    return essence.ifEmpty { WILDCARD }
}

/** No parameter and no case, both because an Intent type carrying either matches no activity. */
private fun essenceOf(type: String?): String =
    type.orEmpty().substringBefore(';').trim().lowercase()

/**
 * What follows the last dot, or null when it is not a file type. Trimmed and folded first: a JMAP
 * server hands the name over raw, and the framework's table answers no key that is padded or shouted.
 */
private fun extensionOf(name: String?): String? =
    name.orEmpty().trim().substringAfterLast('.', "").lowercase().takeIf { EXTENSION.matches(it) }

/** Deliberately closed, and not a setting: a type this list does not name is a type we believe. */
private val GENERIC = setOf("application/octet-stream", "binary/octet-stream", "*/*")

/** Digits belong here: `.mp3`, `.mp4` and `.7z` are file types, and two characters is already one. */
private val EXTENSION = Regex("[a-z0-9]{1,12}")

private const val WILDCARD = "*/*"
