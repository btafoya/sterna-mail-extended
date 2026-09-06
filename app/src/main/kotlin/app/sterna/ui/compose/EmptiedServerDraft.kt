package app.sterna.ui.compose

/*
 * Emptying a draft this composer opened STRAIGHT OFF THE SERVER (#69 × #63), kept out of
 * `ComposeViewModel` so a JVM test can run it, for `EmptiedLocalDraft.kt`'s reason.
 *
 * An empty save took two routes and only the local one read the fidelity verdict. What "empty" means
 * is decided by `draftHasContent`, which counts neither an inline image nor a calendar part: a draft
 * made of one inline image is DRAWN empty here.
 */

/**
 * Is the draft on the server KEPT by this emptying? The twin of [emptiedLocalDraftKeepsServerCopy],
 * deliberately not the same function: the local route asks it of a row that STANDS IN FOR a server
 * draft, while here the draft being emptied IS the server draft.
 * [bodyIsLossy] beats the rule of #69, since expunging on the strength of an emptied screen destroys
 * for ever what she was never shown (arbitration rule 1), and the screen owes her
 * `compose_emptied_draft_server_copy_kept`. [addressingIsProvenEmpty] is the term the ordinary save
 * has had all along, `false` covering both "there were addresses" and "the read did not answer".
 * And no wider: a draft read whole and emptied by hand is still destroyed.
 */
fun emptiedServerDraftIsKept(bodyIsLossy: Boolean, addressingIsProvenEmpty: Boolean): Boolean =
    bodyIsLossy || !addressingIsProvenEmpty

/**
 * Whose credentials expunge the SERVER draft this composer opened, once emptied and saved: the account
 * it was READ FROM ([openedUnderAccountId]), or null when there is nobody to act for.
 * Never [composingAsAccountId]: the id and its numbering were read off the account the composer was
 * opened on, and the screen closes as a success either way — JMAP spares an id that server never
 * issued, and on IMAP two accounts sharing a UIDVALIDITY let the expunge through in the WRONG mailbox.
 *
 * Deliberately not [credentialsDestroyingReplacedServerDraft], which is asked of a LEASE. Null is
 * the safe end, with no fallback.
 */
fun <T : Any> credentialsDestroyingEmptiedServerDraft(
    openedUnderAccountId: String?,
    @Suppress("UNUSED_PARAMETER") composingAsAccountId: String?,
    lookup: (String) -> T?,
): T? = openedUnderAccountId?.let(lookup)
