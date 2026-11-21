package app.sterna.ui.dav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.db.DavObjectEntity
import app.sterna.core.data.db.DavPendingEntity
import app.sterna.core.jmap.JmapType
import app.sterna.dav.Dav
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Which of the screen's three states is showing. [id] null in [Edit] is a new contact. */
sealed interface ContactView {
    data object List : ContactView
    data class Detail(val id: String) : ContactView
    data class Edit(val id: String?) : ContactView
}

/** A book the editor can file a card in. */
data class BookChoice(val id: String, val name: String, val isDefault: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.davRepository
    private val account = MutableStateFlow("")

    val query = MutableStateFlow("")
    val view = MutableStateFlow<ContactView>(ContactView.List)

    fun bind(accountId: String) { account.value = accountId }

    private fun <T> perAccount(f: (String) -> Flow<T>): Flow<T> = account.flatMapLatest { if (it.isEmpty()) emptyFlow() else f(it) }

    private val all: Flow<List<DavObjectEntity>> = perAccount { repo.watch(it, JmapType.CONTACT_CARD) }

    /** Cards matching the search, in name order (the DAO sorts). */
    val contacts: StateFlow<List<DavObjectEntity>> = combine(all, query) { rows, q ->
        // ponytail: re-parses every row's JSON per keystroke; index a lowercase search column if books grow past a few thousand.
        rows.filter { matches(parse(it), q) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val books: StateFlow<List<BookChoice>> = perAccount { repo.watch(it, JmapType.ADDRESS_BOOK) }
        .map { rows -> rows.map { BookChoice(it.id, it.name, (parse(it)["isDefault"] as? JsonPrimitive)?.contentOrNull == "true") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pending: StateFlow<Int> = perAccount { repo.watchPendingCount(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val failed: StateFlow<List<DavPendingEntity>> = perAccount { repo.watchFailed(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The book a new contact goes in: the default one, else the first. */
    fun defaultBookId(): String = books.value.let { b -> (b.firstOrNull { it.isDefault } ?: b.firstOrNull())?.id.orEmpty() }

    suspend fun card(id: String): JsonObject? = repo.get(account.value, JmapType.CONTACT_CARD, id)?.let(::parse)

    fun save(id: String?, form: ContactForm) {
        val acct = account.value
        viewModelScope.launch {
            if (id == null) {
                repo.create(acct, JmapType.CONTACT_CARD, newCard(form))
            } else {
                val stored = card(id) ?: return@launch
                val patch = patchOf(stored, form)
                if (patch.isEmpty()) return@launch
                repo.update(acct, JmapType.CONTACT_CARD, id, patch)
            }
            Dav.enqueue(getApplication())
        }
    }

    fun delete(id: String) {
        val acct = account.value
        viewModelScope.launch {
            repo.destroy(acct, JmapType.CONTACT_CARD, id)
            Dav.enqueue(getApplication())
        }
    }

    fun dismissFailed(id: Long) { viewModelScope.launch { repo.dismissFailed(id) } }

    private fun parse(row: DavObjectEntity): JsonObject = Json.parseToJsonElement(row.json).jsonObject
}
