package app.sterna.ui.compose

import app.sterna.R
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.data.pgp.isSmime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this composer's body ever took a quote of decrypted text. A fact of the composer, not of
 * the text: nothing can tell how much of the clear an edit left behind, so it is never lowered.
 */
internal class DecryptedQuoteGuard {
    private var offered = false
    private val _held = MutableStateFlow(false)
    val held: StateFlow<Boolean> = _held.asStateFlow()

    /** The quote about to be handed to the screen is built on [source]. */
    fun offer(source: QuoteSource) {
        offered = source.fromDecrypted
    }

    /**
     * The screen is putting the quote last offered into the body, and says so before it writes the
     * text. A quote it refused, the user having typed first, never gets here, and that reply stays
     * an ordinary one. `ComposeViewModel.onQuoteInBody` then settles the lock at once, on the
     * availability already known: a refresh would show the refusal while the provider answers.
     * True the one time the fact is raised.
     */
    fun landed(): Boolean {
        val raised = offered && !_held.value
        if (raised) _held.value = true
        return raised
    }

    /**
     * A message handed back to a composer with its body, an undone send, keeps what it was. The
     * scheduled send's record carries the fact as well, though no mode schedules such a body: an
     * Undo must never hand the quote back to a composer unguarded.
     */
    fun restore(held: Boolean) {
        if (held) _held.value = true
    }
}

/**
 * Whether the lock is settled as the quote lands: once, as the fact is [raised]. Never over an
 * S/MIME mode: the settling is OpenPGP's and is offered no S/MIME mode, so it would lower one to off.
 */
internal fun settlesLockOnQuote(raised: Boolean, pgpMode: PgpMode): Boolean = raised && !pgpMode.isSmime

/**
 * What the lock treats as "encrypt by default" for this message: the account's setting, or a
 * decrypted quote in the body. The setting itself is not written, the next message opens as before.
 */
internal fun encryptByDefaultFor(accountDefault: Boolean, quotesDecrypted: Boolean): Boolean =
    accountDefault || quotesDecrypted

/**
 * Why a body quoting decrypted text may not leave the composer, or null when it may. The one rule
 * for the send, the draft and the schedule: all three carry the body as it stands, and only an
 * encrypting mode keeps the quoted clear from whoever handles the message next. Asked through
 * [PgpMode.encrypts], so a signed message is refused like a plain one.
 */
internal fun decryptedQuoteRefusal(quotesDecrypted: Boolean, pgpMode: PgpMode): Int? =
    if (quotesDecrypted && !pgpMode.encrypts) R.string.compose_decrypted_quote_needs_encryption else null

/**
 * Whether the composer's body may be parked in its resume file. Not while it quotes decrypted
 * text: that file is plain UTF-8 under `filesDir`, and a decrypted body is never written to disk.
 */
internal fun bodyMayBeParkedOnDisk(quotesDecrypted: Boolean): Boolean = !quotesDecrypted

/** The parcel's form for a body kept in a [ComposerBodyMemory]: it names no text and no file. */
internal const val MEMORY_FORM = "m:"

/**
 * Where a body that may not go to disk waits out a rotation: beside the fact that forbids the disk,
 * in the view model. A process death takes both, so the quote never comes back without its guard.
 */
class ComposerBodyMemory(private val quotesDecrypted: () -> Boolean) {
    private var parked: ComposerResumeSlot.Resume? = null

    internal val keepsBodyOffDisk: Boolean get() = !bodyMayBeParkedOnDisk(quotesDecrypted())

    /** Holds [state] and erases [slot]: a record parked before the quote landed is no longer named by the parcel. */
    internal fun park(state: ComposerResumeState, slot: ComposerResumeSlot) {
        slot.clear()
        val body = state.bodyState.value
        parked = ComposerResumeSlot.Resume(
            body.text, body.selection.start, body.selection.end, state.baselineState.value,
            state.rangesState.value, state.baselineRangesState.value,
            state.blocksState.value, state.baselineBlocksState.value,
            state.linksState.value, state.baselineLinksState.value,
        )
    }

    /** Handed back once, as the file is read once. */
    internal fun take(): ComposerResumeSlot.Resume? = parked.also { parked = null }
}
