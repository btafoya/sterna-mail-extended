package app.sterna.core.data.account

/** What a settings backup takes out of [AccountStore] and puts back in, kept out of it for a JVM
 *  test: the store needs a `Context` and the Keystore, and cannot be built here. */

/** [accounts] as a settings backup holds them: password accounts that stand on their own login,
 *  with every session value blanked. Everything the user chose, folder order included, is kept, except
 *  the S/MIME certificate alias: it names a key in this phone's keystore and nothing on another, and
 *  the photo: its file stays on this phone, so on another the name points at nothing, or at another
 *  account's. */
internal fun backupSnapshotOf(accounts: List<StoredAccount>): List<StoredAccount> = accounts
    .filter { it.authType == AuthType.BASIC && !it.isLinked }
    .map { it.withBackupBlanks() }

/** [backedUp] as an import adds it, under the fresh [id]: inert until signed in, session values
 *  blanked, every setting the user chose kept as the backup holds it. The S/MIME alias and the photo
 *  are blanked here too, for a file written before [backupSnapshotOf] left them out. */
internal fun importedCopy(backedUp: StoredAccount, id: String): StoredAccount =
    backedUp.withBackupBlanks().copy(id = id, importPending = true)

/** The one list of what neither leaves in a backup nor comes back from one, shared by both ways. */
private fun StoredAccount.withBackupBlanks(): StoredAccount = copy(
    oauthAccessToken = "",
    oauthAccessExpiresAt = 0,
    inboxId = null,
    inboxName = "Inbox",
    unread = 0,
    smimeCertAlias = "",
    photo = null,
)
