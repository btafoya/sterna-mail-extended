package app.sterna.ui.compose

import app.sterna.contacts.ContactSuggestion
import app.sterna.contacts.PoolContact
import app.sterna.contacts.buildRecipientPool
import app.sterna.contacts.rankRecipients
import app.sterna.contacts.usualRecipients
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.ReceivedContactRow
import app.sterna.core.data.db.SentContactRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** The account the composer sends from: the picked "From", else the one it was opened for, else [current]. */
internal fun composingAccountId(selected: FromOption?, openedFor: String?, current: () -> String?): String? =
    selected?.accountId ?: openedFor ?: current()

/** Every address that is the user, on every account: each login and each identity, lowercased. */
internal fun ownAddresses(
    accounts: List<StoredAccount>,
    identities: (accountId: String) -> List<StoredIdentity>,
): Set<String> =
    accounts.flatMap { account -> listOf(account.username) + identities(account.id).map { it.email } }
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

private val ANGLE_ADDRESS = Regex("<([^>]+)>")

/** The lowercased addresses committed as chips in [fields], the text still being typed left out. */
internal fun recipientsOnScreen(vararg fields: String): Set<String> =
    fields.flatMap { splitRecipients(it).first }
        .map { chip -> (ANGLE_ADDRESS.find(chip)?.groupValues?.get(1) ?: chip).trim().lowercase() }
        .toSet()

/**
 * The pool the menu ranks, for [senderAccountId]. Only [serverBook] is per account, read for the
 * sender alone. Every source is a local copy, so neither a focus nor a keystroke reaches a server.
 * A null [device] means the phone's contacts are off, which leaves the other three untouched.
 */
internal suspend fun loadRecipientPool(
    senderAccountId: String?,
    sent: suspend () -> List<SentContactRow>,
    received: suspend () -> List<ReceivedContactRow>,
    serverBook: suspend (accountId: String) -> List<ContactRow>,
    device: (suspend () -> List<ContactSuggestion>)?,
    selves: Set<String>,
): List<PoolContact> = buildRecipientPool(
    sent = sent(),
    received = received(),
    book = senderAccountId?.let { serverBook(it) }.orEmpty(),
    phone = device?.invoke().orEmpty(),
    selves = selves,
)

/**
 * The recipient menu of one composer. The pool is read once per focus of a recipient field and per
 * sender switch, then each keystroke only ranks it in memory; a keystroke that comes before the
 * pool waits for it.
 */
internal class RecipientSuggester(
    private val scope: CoroutineScope,
    private val sender: () -> String?,
    private val loadPool: suspend (senderAccountId: String?) -> List<PoolContact>,
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the pool is read and ranked: off the main thread in the app. */
    private val work: CoroutineContext = EmptyCoroutineContext,
    private val limit: Int = 6,
) {
    private val _suggestions = MutableStateFlow<List<ContactSuggestion>>(emptyList())
    val suggestions: StateFlow<List<ContactSuggestion>> = _suggestions.asStateFlow()
    private var job: Job? = null

    private class Loading(val sender: String?, val pool: Deferred<List<PoolContact>>)
    private var loading: Loading? = null

    private fun load(senderAccountId: String?): Loading {
        loading?.pool?.cancel()
        return Loading(senderAccountId, scope.async(work) { loadPool(senderAccountId) }).also { loading = it }
    }

    private fun tokenOf(fieldValue: String) = fieldValue.substringAfterLast(',').substringAfterLast(';').trim()

    /** A recipient field took the focus: the pool is read afresh, and an empty field offers the usual recipients. */
    fun focused(fieldValue: String, onScreen: Set<String>) {
        job?.cancel()
        val current = load(sender())
        if (tokenOf(fieldValue).isNotEmpty()) {
            _suggestions.value = emptyList()
            return
        }
        publish(current) { pool -> usualRecipients(pool, onScreen, now(), limit) }
    }

    fun suggest(fieldValue: String, onScreen: Set<String>) {
        job?.cancel()
        val token = tokenOf(fieldValue)
        if (token.isEmpty()) {
            _suggestions.value = emptyList()
            return
        }
        val senderAccountId = sender()
        val current = loading?.takeIf { it.sender == senderAccountId } ?: load(senderAccountId)
        publish(current) { pool -> rankRecipients(pool, token, onScreen, now(), limit) }
    }

    private fun publish(current: Loading, pick: (List<PoolContact>) -> List<ContactSuggestion>) {
        job = scope.launch {
            val found = withContext(work) { pick(current.pool.await()) }
            ensureActive()
            // The sender can change without [senderChanged], when a draft or a reply settles it.
            if (sender() == current.sender) _suggestions.value = found
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        _suggestions.value = emptyList()
    }

    fun senderChanged() {
        clear()
        load(sender())
    }

    /**
     * A source was switched on or off, the phone's contacts in the app: the pool is read again and the
     * next keystroke ranks the new one. The menu on screen stays, and so does a search waiting on the
     * previous pool, which is left to finish rather than cancelled. Before any focus there is nothing to reread.
     */
    fun sourcesChanged() {
        if (loading == null) return
        val senderAccountId = sender()
        loading = Loading(senderAccountId, scope.async(work) { loadPool(senderAccountId) })
    }
}
