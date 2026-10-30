package app.sterna.ui.message

import app.sterna.core.data.db.EmailLabels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which message the reader's label sheet is open for, as a [MessagePaging.entryKey], and the name
 * typed in its field. Held by the inbox's ViewModel on its `SavedStateHandle` for the reason given
 * on [MoveSheetOpen]. The name, and the rows [listed] since it opened, are cleared with the sheet.
 */
class LabelSheetOpen(read: (key: String) -> String?, private val write: (key: String, value: String?) -> Unit) {
    private val _openFor = MutableStateFlow(read(OPEN_FOR))
    val openFor: StateFlow<String?> = _openFor.asStateFlow()

    private val _name = MutableStateFlow(if (_openFor.value == null) "" else read(NAME).orEmpty())
    val name: StateFlow<String> = _name.asStateFlow()

    private val _listed = MutableStateFlow(if (_openFor.value == null) emptyList() else EmailLabels.decode(read(LISTED)))
    val listed: StateFlow<List<String>> = _listed.asStateFlow()

    fun keep(rows: List<String>) {
        if (_openFor.value == null || rows == _listed.value) return
        _listed.value = rows
        write(LISTED, EmailLabels.encode(rows))
    }

    fun type(text: String) {
        val openFor = _openFor.value ?: return
        set(openFor, text)
    }

    fun open(emailId: String, accountId: String?) = start(MessagePaging.entryKey(emailId, accountId))

    fun close() = start(null)

    private fun start(openFor: String?) {
        _listed.value = emptyList()
        write(LISTED, null)
        set(openFor, "")
    }

    private fun set(openFor: String?, text: String) {
        _openFor.value = openFor
        _name.value = text
        write(OPEN_FOR, openFor)
        write(NAME, text.takeIf { openFor != null })
    }

    companion object {
        private const val OPEN_FOR = "labelSheet.openFor"
        private const val NAME = "labelSheet.name"
        private const val LISTED = "labelSheet.listed"

        fun shows(openFor: String?, emailId: String, accountId: String?): Boolean =
            openFor != null && openFor == MessagePaging.entryKey(emailId, accountId)
    }
}
