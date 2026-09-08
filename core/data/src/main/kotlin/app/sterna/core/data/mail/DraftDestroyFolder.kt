package app.sterna.core.data.mail

/**
 * The folder a destroy of a server draft is opposed to.
 */
fun draftDestroyFolder(cachedMailboxId: String?, draftsRoleMailboxId: String?): String? =
    cachedMailboxId ?: draftsRoleMailboxId
