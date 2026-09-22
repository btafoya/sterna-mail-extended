package app.sterna.core.data.account

/** What a settings backup takes out of [AccountStore] and puts back in, kept out of it for a JVM
 *  test: the store needs a `Context` and the Keystore, and cannot be built here. */

/** [accounts] as a settings backup holds them: password accounts that stand on their own login,
 *  with every session value blanked. Everything the user chose, folder order included, is kept. */
internal fun backupSnapshotOf(accounts: List<StoredAccount>): List<StoredAccount> = accounts
    .filter { it.authType == AuthType.BASIC && !it.isLinked }
    .map {
        it.copy(
            oauthAccessToken = "",
            oauthAccessExpiresAt = 0,
            inboxId = null,
            inboxName = "Inbox",
            unread = 0,
        )
    }

/** [backedUp] as an import adds it, under the fresh [id]: inert until signed in, session values
 *  blanked, every setting the user chose kept as the backup holds it. */
internal fun importedCopy(backedUp: StoredAccount, id: String): StoredAccount = backedUp.copy(
    id = id,
    oauthAccessToken = "",
    oauthAccessExpiresAt = 0,
    inboxId = null,
    inboxName = "Inbox",
    unread = 0,
    importPending = true,
)
