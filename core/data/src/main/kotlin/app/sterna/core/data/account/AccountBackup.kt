package app.sterna.core.data.account

/** What a settings backup takes out of [AccountStore] and puts back in, kept out of it for a JVM
 *  test: the store needs a `Context` and the Keystore, and cannot be built here. */

/** [accounts] as a settings backup holds them: password accounts that stand on their own login,
 *  with every session value blanked. Everything the user chose, folder order included, is kept, except
 *  the S/MIME certificate alias: it names a key in this phone's keystore and nothing on another. */
internal fun backupSnapshotOf(accounts: List<StoredAccount>): List<StoredAccount> = accounts
    .filter { it.authType == AuthType.BASIC && !it.isLinked }
    .map {
        it.copy(
            oauthAccessToken = "",
            oauthAccessExpiresAt = 0,
            inboxId = null,
            inboxName = "Inbox",
            unread = 0,
            smimeCertAlias = "",
        )
    }

/** [backedUp] as an import adds it, under the fresh [id]: inert until signed in, session values
 *  blanked, every setting the user chose kept as the backup holds it. The S/MIME alias is blanked here
 *  too, for a file written before [backupSnapshotOf] left it out. */
internal fun importedCopy(backedUp: StoredAccount, id: String): StoredAccount = backedUp.copy(
    id = id,
    oauthAccessToken = "",
    oauthAccessExpiresAt = 0,
    inboxId = null,
    inboxName = "Inbox",
    unread = 0,
    smimeCertAlias = "",
    importPending = true,
)
