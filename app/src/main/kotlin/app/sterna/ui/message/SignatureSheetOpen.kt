package app.sterna.ui.message

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which message's "About this signature" sheet is open, as a [MessagePaging.entryKey]. Held by the
 * inbox's ViewModel, not by the header: a width change swaps the `message/…` route for the reading
 * pane (or back), and state remembered in the header dies with the host that composed it.
 */
class SignatureSheetOpen(initial: String?, private val persist: (String?) -> Unit) {
    private val _openFor = MutableStateFlow(initial)
    val openFor: StateFlow<String?> = _openFor.asStateFlow()

    fun open(emailId: String, accountId: String?) = set(MessagePaging.entryKey(emailId, accountId))

    fun close() = set(null)

    private fun set(key: String?) {
        _openFor.value = key
        persist(key)
    }

    companion object {
        fun shows(openFor: String?, emailId: String, accountId: String?): Boolean =
            openFor != null && openFor == MessagePaging.entryKey(emailId, accountId)
    }
}
