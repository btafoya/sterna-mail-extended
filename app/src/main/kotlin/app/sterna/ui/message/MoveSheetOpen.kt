package app.sterna.ui.message

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which message the reader's move-to-folder sheet is open for, as a [MessagePaging.entryKey], and
 * the text typed in its filter. Held by the inbox's ViewModel for the same reason as
 * [SignatureSheetOpen]: a rotation across the two-pane threshold swaps the `message/…` route for
 * the reading pane, and state remembered in [MessageScreen] dies with the host that composed it.
 *
 * [read] and [write] are the ViewModel's `SavedStateHandle`, so the sheet also survives a process
 * death. The filter is cleared with the sheet: a sheet reopened later starts on an empty field.
 */
class MoveSheetOpen(read: (key: String) -> String?, private val write: (key: String, value: String?) -> Unit) {
    private val _openFor = MutableStateFlow(read(OPEN_FOR))
    val openFor: StateFlow<String?> = _openFor.asStateFlow()

    private val _query = MutableStateFlow(if (_openFor.value == null) "" else read(QUERY).orEmpty())
    val query: StateFlow<String> = _query.asStateFlow()

    fun open(emailId: String, accountId: String?) = set(MessagePaging.entryKey(emailId, accountId), "")

    fun filter(text: String) {
        val openFor = _openFor.value ?: return
        set(openFor, text)
    }

    fun close() = set(null, "")

    private fun set(openFor: String?, text: String) {
        _openFor.value = openFor
        _query.value = text
        write(OPEN_FOR, openFor)
        write(QUERY, text.takeIf { openFor != null })
    }

    companion object {
        private const val OPEN_FOR = "moveSheet.openFor"
        private const val QUERY = "moveSheet.query"

        fun shows(openFor: String?, emailId: String, accountId: String?): Boolean =
            openFor != null && openFor == MessagePaging.entryKey(emailId, accountId)
    }
}
