package app.sterna.ui

/**
 * Which top-level screen the app opens on. Two facts in, one [RootState] out, and nothing else,
 * so the decision can be executed by a unit test instead of being read off `RootViewModel`, which
 * needs an `Application`, the account store and the Keystore to exist at all.
 */
object RootRoute {

    /**
     * The order of the two tests is the decision, not a formality.
     */
    fun resolve(currentAccountId: String?, accountsUnreadable: Boolean): RootState = when {
        currentAccountId != null -> RootState.Authenticated(currentAccountId)
        accountsUnreadable -> RootState.AccountsUnreadable
        else -> RootState.NeedAccount
    }
}
