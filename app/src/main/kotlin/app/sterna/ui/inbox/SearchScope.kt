package app.sterna.ui.inbox

import androidx.annotation.StringRes
import app.sterna.R

/** The accounts a search covers, the first chip of its row. Never a folder: no leg of the search is limited to one. */
enum class SearchScope { THIS_ACCOUNT, ALL_ACCOUNTS }

/** The scope a search opens with, read off the view it is opened from. */
internal fun initialSearchScope(sel: Sel?): SearchScope =
    if (showsEveryAccount(sel)) SearchScope.ALL_ACCOUNTS else SearchScope.THIS_ACCOUNT

internal fun SearchScope.flipped(): SearchScope =
    if (this == SearchScope.ALL_ACCOUNTS) SearchScope.THIS_ACCOUNT else SearchScope.ALL_ACCOUNTS

/** The accounts both legs of a search under [scope] ask: [every] one, or the [current] one alone. */
internal fun <T> accountsInScope(scope: SearchScope, every: () -> List<T>, current: () -> T?): List<T> =
    when (scope) {
        SearchScope.ALL_ACCOUNTS -> every()
        SearchScope.THIS_ACCOUNT -> listOfNotNull(current())
    }

/** With one account both values cover the same mail: the chip would offer a choice that changes nothing. */
internal fun searchScopeChipShown(accountCount: Int): Boolean = accountCount > 1

@StringRes
internal fun searchScopeLabel(scope: SearchScope): Int = when (scope) {
    SearchScope.THIS_ACCOUNT -> R.string.search_scope_this_account
    SearchScope.ALL_ACCOUNTS -> R.string.search_scope_all_accounts
}
