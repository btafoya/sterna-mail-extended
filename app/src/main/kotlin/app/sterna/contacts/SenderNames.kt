package app.sterna.contacts

import android.content.Context
import android.database.ContentObserver
import android.provider.ContactsContract
import android.util.Log
import app.sterna.core.data.rethrowIfCancelled
import app.sterna.ui.compose.recipientNameKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The names the user saved in the phone's contacts, keyed by `recipientNameKey`. A contact whose
 * name is empty or only repeats the address writes no entry, so a sender found in the book always
 * has a name to show; the first contact for an address wins.
 */
internal fun senderNameBook(contacts: List<ContactSuggestion>): Map<String, String> {
    val book = LinkedHashMap<String, String>()
    for (contact in contacts) {
        val key = recipientNameKey(contact.email)
        val name = contact.name?.trim().orEmpty()
        if (key.isEmpty() || name.isEmpty() || name.equals(contact.email.trim(), ignoreCase = true)) continue
        book.putIfAbsent(key, name)
    }
    return book
}

/**
 * The address book read for senders (#280), for as long as "Suggest from contacts" is on. Off, the
 * book is empty and neither [read] nor [watch] is called.
 * [watch] registers a change observer and returns its removal, called when the setting goes off.
 */
class SenderNames(
    scope: CoroutineScope,
    enabled: Flow<Boolean>,
    read: suspend () -> List<ContactSuggestion>,
    watch: (onChange: () -> Unit) -> (() -> Unit),
    context: CoroutineContext = EmptyCoroutineContext,
) {
    private val book = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = book.asStateFlow()

    /** Whether [book] answers the setting's current value; false until the first read after it turns on. */
    private val settled = MutableStateFlow(false)

    init {
        scope.launch(context) {
            enabled.distinctUntilChanged().collectLatest { on ->
                if (!on) {
                    book.value = emptyMap()
                    settled.value = true
                    return@collectLatest
                }
                settled.value = false
                // Conflated: changes landing during a read fold into one more read.
                val changes = Channel<Unit>(Channel.CONFLATED)
                val unwatch = watch { changes.trySend(Unit) }
                try {
                    while (true) {
                        book.value = senderNameBook(
                            runCatching { read() }.rethrowIfCancelled().getOrDefault(emptyList()),
                        )
                        settled.value = true
                        changes.receive()
                    }
                } finally {
                    unwatch()
                }
            }
        }
    }

    /** The book, once it answers the setting: waits for the first read when the setting is on. */
    suspend fun current(): Map<String, String> {
        settled.first { it }
        return book.value
    }
}

/**
 * Registers [onChange] for any change in the phone's contacts and returns its removal. The
 * permission is checked here and not only by the setting: a setting restored from a backup can be
 * on while the permission was never granted, and then nothing is registered.
 */
fun watchContacts(context: Context, onChange: () -> Unit): () -> Unit {
    if (!AndroidContacts.hasPermission(context)) return {}
    val resolver = context.contentResolver
    val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) = onChange()
    }
    val registered = runCatching {
        resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
    }.onFailure {
        Log.w("Sterna", "contacts not watched; saved names reload when the setting is turned on again", it)
    }.isSuccess
    return { if (registered) resolver.unregisterContentObserver(observer) }
}
