package app.sterna.ui.compose

import app.sterna.mail.MessageDestroyWorker
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/*
 * Emptying a draft the phone kept for a SERVER draft it has not managed to replace yet (#95 × #69),
 * kept out of `ComposeViewModel` so a JVM test can run these decisions. A local row R names its server
 * draft S in `replacesEmailId`, and S is masked out of Drafts while R exists. The mask is not total —
 * a server draft that represents a conversation is drawn beside its local row — and nothing here
 * depends on it being total.
 */

/**
 * What the emptying of a local row does about the server draft behind it. Two fields rather than one
 * nullable order: "nothing to destroy" and "kept on purpose, and said so" share the same null order.
 */
data class EmptiedLocalDraftPlan(
    /** The destroy to make durable, or null when nothing may be destroyed. */
    val order: MessageDestroyWorker.FolderDestroy?,
    /**
     * True when the server copy is deliberately KEPT because this phone never read all of it — and
     * the screen has to say so, or the app quietly does the opposite of what was asked.
     */
    val tellServerCopyKept: Boolean,
)

/**
 * The plan the emptying of a local row produces.
 *
 * What the order CARRIES is the point: a destroy reaching the worker without its folder or its
 * frozen numbering destroys nothing, in silence, on both protocols (#122, #99). Hence a value a test
 * can read rather than a call a lint can see.
 *
 * [bodyIsLossy] keeps the server copy, beating the rule of #69, the row's body being only what this
 * phone MANAGED to read (arbitration rule 1); [addressingIsProvenEmpty] keeps it for the same damage.
 */
fun planForEmptiedLocalDraft(
    replacedServerDraftId: String?,
    replacedServerDraftUidValidity: Long?,
    mailboxId: String?,
    bodyIsLossy: Boolean,
    addressingIsProvenEmpty: Boolean,
): EmptiedLocalDraftPlan {
    if (emptiedLocalDraftKeepsServerCopy(replacedServerDraftId, bodyIsLossy, addressingIsProvenEmpty)) {
        return EmptiedLocalDraftPlan(null, tellServerCopyKept = true)
    }
    val serverDraft = replacedServerDraftId ?: return EmptiedLocalDraftPlan(null, tellServerCopyKept = false)
    val folder = mailboxId?.takeIf { it.isNotEmpty() } ?: return EmptiedLocalDraftPlan(null, tellServerCopyKept = false)
    return EmptiedLocalDraftPlan(
        MessageDestroyWorker.FolderDestroy(folder, listOf(serverDraft), replacedServerDraftUidValidity),
        tellServerCopyKept = false,
    )
}

/**
 * Is the copy on the server KEPT by this gesture? Asked twice: the empty-save asks it afterwards to
 * post the notice, the delete button asks it BEFORE the finger, because its confirmation would
 * otherwise promise "this can't be undone" over a copy that survives.
 *
 * [addressingIsProvenEmpty] is the term the ordinary save has always had and this gesture had not:
 * the composer cannot tell a draft with no Cc from a Cc it never managed to read. The unknown
 * KEEPS — `false` covers both "there were addresses" and "the read did not answer".
 */
fun emptiedLocalDraftKeepsServerCopy(
    replacedServerDraftId: String?,
    bodyIsLossy: Boolean,
    addressingIsProvenEmpty: Boolean,
): Boolean = replacedServerDraftId != null && (bodyIsLossy || !addressingIsProvenEmpty)

/**
 * Who gets the phone's own draft row back once a DELETION asked for is over (#95 × #127): the row to
 * put from `EDITING` back to `PENDING`, or null when nobody may have it.
 *
 * The lease is a decision, not a field to blank: blanked up front, a `submit` that throws leaves the
 * row `EDITING` with nobody to release it, and no sweep takes `EDITING` back.
 *
 * The failure is atomic — everything that can throw sits in front of any destruction — and [lease] is
 * read as it stands when the gesture ends, the close button possibly having given the row back (#127).
 */
fun localDraftLeaseAfterDelete(lease: String?, destroyed: Boolean): String? =
    if (destroyed) null else lease

/**
 * Make the destroy of the server draft DURABLE, take it off the phone, say what was kept, and only
 * then consume the local row it was standing behind.
 *
 * The order is guaranteed here rather than promised in a comment: the local row is the only thing
 * that still names the server draft, so consumed first, a process death in the interval leaves that
 * draft alive and unmasked for ever. The three acts are sheltered from cancellation TOGETHER,
 * `viewModelScope` dying with the back gesture — and no wider, a cancellation arriving before
 * anything is destroyed being honoured.
 */
suspend fun destroyThenConsumeEmptiedLocalDraft(
    localDraftId: String,
    plan: EmptiedLocalDraftPlan,
    destroy: suspend (MessageDestroyWorker.FolderDestroy) -> Unit,
    evict: suspend (List<String>) -> Unit,
    consume: suspend (String) -> Unit,
    tell: suspend () -> Unit,
) {
    // The boundary, read BEFORE the shelter is entered: nothing destroyed yet, so a screen already
    // gone takes nothing with it and the draft survives on the phone.
    coroutineContext.ensureActive()
    withContext(NonCancellable) {
        plan.order?.let {
            destroy(it)
            evict(it.emailIds)
        }
        if (plan.tellServerCopyKept) tell()
        consume(localDraftId)
    }
}

/**
 * Whose credentials destroy the SERVER draft a reopened local row stands in front of: the account the
 * ROW was leased under, or null when there is nobody to act for.
 *
 * Never [composingAsAccountId]: the "From" picker offers every account's identities, while the id
 * and its numbering were read off the account the row was opened from — and ids collide between two
 * accounts of one server, so the wrong one does not even reliably fail (#31). It is a parameter, never
 * consulted, so the refusal is executed rather than read out of the source.
 * Null is the safe end and there is NO fallback: the caller draws the failure banner.
 */
fun <T : Any> credentialsDestroyingReplacedServerDraft(
    leasedUnderAccountId: String?,
    @Suppress("UNUSED_PARAMETER") composingAsAccountId: String?,
    lookup: (String) -> T?,
): T? = leasedUnderAccountId?.let(lookup)
