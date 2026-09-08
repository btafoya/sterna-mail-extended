package app.sterna.widget

import app.sterna.core.data.account.StoredAccount

/**
 * Does the app have a cross-account "All inboxes" list at all?
 */
internal object AllInboxesView {

    fun existsFor(accounts: List<StoredAccount>): Boolean = accounts.size > 1
}
