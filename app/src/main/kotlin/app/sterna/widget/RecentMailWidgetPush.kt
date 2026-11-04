package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.RecentInbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * When the latest-messages widget is asked to draw itself again, [UnreadWidgetPush]'s twin, kept
 */
internal object RecentMailWidgetPush {

    /** The moments the cell must be redrawn, as the read that changed under it. A read that throws
     *  ends this placement only, through [onReadFailure]: `appScope` has no handler to catch it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun redraws(
        placed: Flow<Boolean>,
        accounts: Flow<List<StoredAccount>>,
        onReadFailure: (Throwable) -> Unit,
        inbox: () -> Flow<RecentInbox>,
    ): Flow<RecentInbox> = placed
        .distinctUntilChanged()
        .flatMapLatest { anyPlaced ->
            if (anyPlaced) {
                inbox().combine(accounts.map(AllInboxesView::existsFor), ::Pair).catch { onReadFailure(it) }
            } else {
                emptyFlow()
            }
        }
        .distinctUntilChanged()
        .map { (read, _) -> read }
}
