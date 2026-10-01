package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox

/** What the list does with a request to show one account's Drafts. */
sealed interface DraftsLanding {
    /** Not yet: no request, or the list still shows the account it showed when it was asked. */
    data object Wait : DraftsLanding

    /** Spent with nothing selected: the requested account's first list has no Drafts folder or no
     *  folder at all, or the list went to a third account first. Kept, the request would select
     *  Drafts the day those folders arrive or that account comes back, long after she moved on. */
    data object Plain : DraftsLanding

    data class Folder(val mailbox: Mailbox) : DraftsLanding
}

/** A parked landing: the account whose Drafts to show, and [listAccountId], the account the list
 *  showed when it was asked, the only other one it may still be waiting under. */
data class DraftsLandingRequest(val accountId: String, val listAccountId: String?)

/** The request to park, read once the switch was asked: [currentAfterSwitch] is the store's current
 *  account then, and a refused switch leaves another one current, so nothing is parked. */
fun draftsLandingRequest(accountId: String, currentAfterSwitch: String?, listAccountId: String?): DraftsLandingRequest? =
    DraftsLandingRequest(accountId, listAccountId).takeIf { accountId == currentAfterSwitch }

/** Judged on every folder list. [folders] must be [currentAccountId]'s: two accounts on one server
 *  share folder ids, so a list judged under the wrong account would select another account's Drafts. */
fun draftsLanding(request: DraftsLandingRequest?, currentAccountId: String?, folders: List<Mailbox>): DraftsLanding = when {
    request == null -> DraftsLanding.Wait
    currentAccountId == request.accountId ->
        folders.firstOrNull { it.role == "drafts" }?.let { DraftsLanding.Folder(it) } ?: DraftsLanding.Plain
    currentAccountId == request.listAccountId -> DraftsLanding.Wait
    else -> DraftsLanding.Plain
}
