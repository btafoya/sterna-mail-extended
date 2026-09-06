package app.sterna.core.data.mail

/**
 * The folder a destroy of a server draft is opposed to. Without one `destroyAll` spares everything,
 * in silence, while the local row is consumed anyway, so the server draft comes back carrying the
 * pre-edit text (#122, #95 × #69). [cachedMailboxId] comes first because a draft can sit outside the
 * account's Drafts folder, where checking the role's folder would spare it.
 */
fun draftDestroyFolder(cachedMailboxId: String?, draftsRoleMailboxId: String?): String? =
    cachedMailboxId ?: draftsRoleMailboxId
