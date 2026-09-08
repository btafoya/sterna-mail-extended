package app.sterna.ui.compose

/*
 * Emptying a draft this composer opened straight off the server (#69 × #63), kept out of
 * `ComposeViewModel` so a JVM test can run it, for `EmptiedLocalDraft.kt`'s reason.
 */

/**
 * Is the draft on the server kept by this emptying? The twin of [emptiedLocalDraftKeepsServerCopy],
 * one route away, and deliberately not the same function.
 */
fun emptiedServerDraftIsKept(bodyIsLossy: Boolean, addressingIsProvenEmpty: Boolean): Boolean =
    bodyIsLossy || !addressingIsProvenEmpty

/**
 * Whose credentials expunge the server draft this composer opened, once emptied and saved: the account
 */
fun <T : Any> credentialsDestroyingEmptiedServerDraft(
    openedUnderAccountId: String?,
    @Suppress("UNUSED_PARAMETER") composingAsAccountId: String?,
    lookup: (String) -> T?,
): T? = openedUnderAccountId?.let(lookup)
