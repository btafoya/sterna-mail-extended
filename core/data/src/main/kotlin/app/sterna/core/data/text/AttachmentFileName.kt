package app.sterna.core.data.text

/**
 * [name] proposed to the system picker for an attachment: sanitised like any other file name, but
 * with its EXTENSION kept whole.
 *
 * WHY the split, rather than a plain `safeFileName(name, "attachment")`: that one caps the whole
 * string at 80 code units, and the cap falls wherever the name happens to reach. A part called by
 * a long sentence ending in `.pdf` therefore reaches the picker as `aaa…aaa` — or, one character
 * luckier, as `aaa….pd`, a file type that does not exist. Either way the document lands in her
 * folder untyped: on Android nothing reads the bytes to find out what a file is, the extension IS
 * the type, so it opens in no app and she has no way to tell what it was.
 *
 * The rule: cut at the LAST dot, and what follows is an extension only if that dot is neither the
 * first nor the last character (`.bashrc` is a name, `nom.` has no extension) and what follows is
 * at most [MAX_EXTENSION] characters — past that it is a word. Trunk and extension each go through
 * [safeFileName], so neither can carry `/ \ : * ? " < > |` nor a control character, and the trunk
 * alone pays the length cap. Nothing at all (null, empty, blank) gives `attachment`.
 */
fun attachmentFileName(name: String?): String {
    val raw = name.orEmpty().trim()
    val dot = raw.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < raw.length - 1 && raw.length - dot - 1 <= MAX_EXTENSION
    val trunk = safeFileName(if (hasExtension) raw.substring(0, dot) else raw, "attachment")
    val extension = if (hasExtension) safeFileName(raw.substring(dot + 1), "") else ""
    return if (extension.isEmpty()) trunk else "$trunk.$extension"
}

/** Past this many characters, what follows the last dot is a word and not a file type. */
private const val MAX_EXTENSION = 10
