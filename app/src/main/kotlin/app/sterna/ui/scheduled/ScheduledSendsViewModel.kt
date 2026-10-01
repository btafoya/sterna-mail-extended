package app.sterna.ui.scheduled

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.mail.accountDepartureIsProven
import app.sterna.send.ScheduledSends
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ScheduledSendsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository

    private val table = repo.sending.scheduledSendsFlow()

    val items = table.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList<ScheduledSendEntity>(),
    )

    /** Null without a fresh table answer: an initial or stale list would read rows as sent. */
    val rows: StateFlow<List<ScheduledSendEntity>?> = table.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0),
        initialValue = null,
    )

    val accountLabels: StateFlow<Map<String, String>> = application.container.accountStore.accountsFlow
        .map { accounts -> accounts.associate { it.id to it.label() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The rows whose cancellation is still running, so a second tap cannot start a second one (#170).
     */
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val _cancelling = MutableStateFlow<Set<Long>>(emptySet())
    val cancelling = _cancelling.asStateFlow()

    /** Set before the row leaves [cancelling]: in between, its disappearance would read as sent. */
    private val _outcome = MutableStateFlow<ScheduledCancelOutcome?>(null)
    val outcome = _outcome.asStateFlow()

    fun takeOutcome(id: Long): ScheduledCancelExit? {
        val current = _outcome.value?.takeIf { it.id == id } ?: return null
        return if (_outcome.compareAndSet(current, null)) current.exit else null
    }

    fun takeAnyOutcome(): ScheduledCancelExit? {
        val current = _outcome.value ?: return null
        return if (_outcome.compareAndSet(current, null)) current.exit else null
    }

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun say(text: String) { _message.value = text }

    fun clearMessage() { _message.value = null }

    /**
     * Cancel a scheduled send: file the message back in Drafts, then drop its row and then its job,
     * in that order, so that no step can lose the only copy.
     */
    fun cancel(id: Long, accountId: String) {
        val app = getApplication<Application>()
        if (!inFlight.add(id)) return
        _cancelling.update { it + id }
        app.container.appScope.launch {
            try {
                val report = cancelScheduledSend(
                    id = id,
                    row = { repo.sending.scheduledSend(it) },
                    credentials = { app.container.accountStore.credentials(it) },
                    // A `null` from `credentials` is not proof: the store answers null for an
                    // unreadable secret too, and an unreadable blob makes `accounts()` empty. Both
                    // verdicts are read here; what they mean is decided in one executable place.
                    accountIsGone = {
                        accountDepartureIsProven(
                            accountsUnreadable = app.container.accountStore.accountsUnreadable(),
                            accountStillListed = app.container.accountStore.accounts().any { a -> a.id == it },
                        )
                    },
                    attachmentParts = { c, stored -> repo.sending.scheduledPartsFor(c, stored) },
                    saveDraft = { creds, d, parts ->
                        repo.drafts.saveDraft(
                            credentials = creds,
                            to = d.to,
                            subject = d.subject,
                            body = d.body,
                            // The styling the message carried, as the draft's html part (#131).
                            // Dropped here, a cancelled scheduled send is filed with its bold gone.
                            html = d.html,
                            cc = d.cc,
                            bcc = d.bcc,
                            inReplyTo = d.inReplyTo,
                            references = d.references,
                            // Nothing to replace, said out loud: naming the server draft this
                            // message was edited from would let the save destroy it, and the
                            // deposit flattens the HTML body. A duplicate in Drafts is
                            // recoverable, a destroyed original is not (#63).
                            replacesEmailId = null,
                            // …and the numbering that goes with it, explicit because the parameter
                            // has no default (#99), and because we aim at no original.
                            replacesUidValidity = null,
                            // No composer in this story: the row is read from the database and
                            // deposited whole. Explicit because the parameter has no default, and
                            // `true` here would silently refuse a genuinely empty body.
                            composerBodyWasLost = false,
                            // Only ever read when `composerBodyWasLost` is true, which this route
                            // never is: it is answered from the text being deposited, the only text
                            // this route has ever had.
                            typedBodyIsBlank = d.body.isBlank(),
                            fromName = d.fromName,
                            fromEmail = d.fromEmail,
                            requestReceipt = d.requestReceipt,
                            attachments = parts,
                        )
                    },
                    dropWorker = { ScheduledSends.cancel(app, it) },
                    deleteRow = { repo.sending.deleteScheduledSend(it) },
                )
                _outcome.value = ScheduledCancelOutcome(id, scheduledCancelExit(report, accountId))
            } finally {
                inFlight.remove(id)
                _cancelling.update { it - id }
            }
        }
    }
}

data class ScheduledCancelOutcome(val id: Long, val exit: ScheduledCancelExit)
