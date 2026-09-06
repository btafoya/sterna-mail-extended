package app.sterna.ui

/**
 * Which top-level screen the app opens on. Two facts in, one [RootState] out — and nothing else,
 * so the decision can be EXECUTED by a unit test instead of being read off `RootViewModel`, which
 * needs an `Application`, the account store and the Keystore to exist at all.
 *
 * It exists because of what the account-blob write refusal leaves behind. When the stored account
 * list cannot be decoded it reads back empty, so the old routing landed on [RootState.NeedAccount]
 * — the welcome, then "add an account" — while the store was refusing every write to protect the
 * real accounts underneath. The user's account would be added, appear to work, and be gone at the
 * next launch. An unreadable list is therefore its own screen ([RootState.AccountsUnreadable]),
 * which explains and asks for nothing.
 */
object RootRoute {

    /**
     * THE ORDER OF THE TWO TESTS IS THE DECISION, not a formality.
     *
     * A signed-in account wins first. Reversed, a device whose blob is unreadable for a moment
     * would throw a user who IS signed in onto the alert screen; and, more to the point, the rule
     * would then be "unreadable beats everything", which is exactly the shape that turns a
     * legitimate empty into an alert. Only after no account is found does the empty get qualified:
     * unreadable means the alert, and a genuine empty (fresh install, or "clear data") means the
     * normal first-run path — which is the only way anyone ever creates a first account.
     *
     * @param currentAccountId the signed-in account to open, or null if there is none
     * @param accountsUnreadable whether the stored account list failed to decode on a FRESH read
     */
    fun resolve(currentAccountId: String?, accountsUnreadable: Boolean): RootState = when {
        currentAccountId != null -> RootState.Authenticated(currentAccountId)
        accountsUnreadable -> RootState.AccountsUnreadable
        else -> RootState.NeedAccount
    }
}
