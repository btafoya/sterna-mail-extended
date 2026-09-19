package app.sterna.ui.compose

import app.sterna.contacts.ContactSuggestion
import app.sterna.contacts.mergeSuggestions
import app.sterna.core.data.db.ContactRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The account the composer sends from: the picked "From", else the one it was opened for, else [current]. */
internal fun composingAccountId(selected: FromOption?, openedFor: String?, current: () -> String?): String? =
    selected?.accountId ?: openedFor ?: current()

/**
 * The recipient menu for [token]. Only [serverBook] is per account, read for [senderAccountId] alone;
 * it is the local copy, so typing never reaches the server. A null [device] means device contacts are
 * off, which leaves the server book untouched.
 */
internal suspend fun suggestRecipients(
    token: String,
    senderAccountId: String?,
    recent: suspend (String, Int) -> List<ContactRow>,
    serverBook: suspend (accountId: String, query: String, limit: Int) -> List<ContactRow>,
    device: (suspend (String, Int) -> List<ContactSuggestion>)?,
    limit: Int = 6,
): List<ContactSuggestion> {
    val seen = recent(token, limit)
    val book = senderAccountId?.let { serverBook(it, token, limit) }.orEmpty()
    val phone = device?.invoke(token, limit).orEmpty()
    return mergeSuggestions(seen, book, phone, limit)
}

internal class RecipientSuggester(
    private val scope: CoroutineScope,
    private val recent: suspend (String, Int) -> List<ContactRow>,
    private val serverBook: suspend (accountId: String, query: String, limit: Int) -> List<ContactRow>,
    private val limit: Int = 6,
) {
    private val _suggestions = MutableStateFlow<List<ContactSuggestion>>(emptyList())
    val suggestions: StateFlow<List<ContactSuggestion>> = _suggestions.asStateFlow()
    private var job: Job? = null

    fun suggest(
        fieldValue: String,
        sender: () -> String?,
        device: (suspend (String, Int) -> List<ContactSuggestion>)?,
    ) {
        job?.cancel()
        val token = fieldValue.substringAfterLast(',').substringAfterLast(';').trim()
        if (token.length < 2) {
            _suggestions.value = emptyList()
            return
        }
        val senderAccountId = sender()
        job = scope.launch {
            val found = suggestRecipients(token, senderAccountId, recent, serverBook, device, limit)
            ensureActive()
            // The sender can change without [senderChanged], when a draft or a reply settles it.
            if (sender() == senderAccountId) _suggestions.value = found
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        _suggestions.value = emptyList()
    }

    fun senderChanged() = clear()
}
