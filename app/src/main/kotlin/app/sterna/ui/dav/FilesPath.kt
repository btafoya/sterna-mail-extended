package app.sterna.ui.dav

/** One folder on the way down from the root; the root itself is the empty stack. */
data class Crumb(val id: String, val name: String)

/** The group id (`parentId`) the folder at the top of [stack] lists; "" is the root. */
fun List<Crumb>.groupId(): String = lastOrNull()?.id.orEmpty()

enum class FileNameProblem { EMPTY, FORBIDDEN_CHAR, TOO_LONG, TAKEN }

/** The server refuses these (`forbiddenNameChars` "/\\", `maxSizeFileNodeName` 255 on the reference server); caught here so the error is plain. */
private const val MAX_NAME = 255

/**
 * Why [name] cannot be used next to [siblings], or null. Case-insensitive, as the server's names are;
 * [renaming] is the current name of the node being renamed, which does not collide with itself.
 * ponytail: the limits are the reference server's constants, not read from its capability.
 */
fun fileNameProblem(name: String, siblings: Collection<String>, renaming: String? = null): FileNameProblem? {
    val n = name.trim()
    return when {
        n.isEmpty() -> FileNameProblem.EMPTY
        n.any { it == '/' || it == '\\' } -> FileNameProblem.FORBIDDEN_CHAR
        n.length > MAX_NAME -> FileNameProblem.TOO_LONG
        siblings.any { it.equals(n, ignoreCase = true) && !it.equals(renaming, ignoreCase = true) } -> FileNameProblem.TAKEN
        else -> null
    }
}
