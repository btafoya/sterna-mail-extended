package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * When the home-screen widget is pushed new numbers, kept apart from Android so it can be executed
 */
internal object UnreadWidgetPush {

    /**
     * The numbers to draw over time: nothing while no widget is placed, the shared unified-unread
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun redraws(
        placed: Flow<Boolean>,
        accounts: Flow<List<StoredAccount>>,
        unread: () -> Flow<Map<String, Int>>,
    ): Flow<Map<String, Int>> = placed
        .distinctUntilChanged()
        .flatMapLatest { anyPlaced ->
            if (anyPlaced) unread().combine(accounts.map(::redrawKey), ::Pair) else emptyFlow()
        }
        .distinctUntilChanged()
        .map { (unreadByAccount, _) -> unreadByAccount }

    /** The account list projected down to which of [UnreadWidgetContent.of]'s arms draws the cell and
     *  the row order [UnreadWidgetContent.byAccount] follows. A renamed account does not redraw. */
    private data class RedrawKey(val bound: CellBound, val idOrder: List<String>)

    private fun redrawKey(accounts: List<StoredAccount>) = RedrawKey(cellBound(accounts), accounts.map { it.id })

    private enum class CellBound { ALL_INBOXES, ONE_ACCOUNT, NO_ACCOUNT }

    private fun cellBound(accounts: List<StoredAccount>): CellBound = when {
        AllInboxesView.existsFor(accounts) -> CellBound.ALL_INBOXES
        accounts.isEmpty() -> CellBound.NO_ACCOUNT
        else -> CellBound.ONE_ACCOUNT
    }
}
