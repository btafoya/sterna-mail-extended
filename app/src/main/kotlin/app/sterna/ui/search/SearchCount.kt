package app.sterna.ui.search

/**
 * What the header above a result list is allowed to claim.
 *
 * [EXACT] is a total the user can check ("3 results"); [AT_LEAST] is the honest form for every
 * answer that isn't the whole one yet ("At least 3 results").
 */
enum class SearchCount { EXACT, AT_LEAST }

/**
 * The one place both search surfaces decide how to phrase the count, so they cannot drift apart. A
 * firm total may only be stated when the answer is BOTH complete and finished.
 *
 * [complete] is false when the search stopped short — a server cap, the IMAP attachment scan cap, or
 * an account whose leg failed. [loading] is true while a leg is still in flight: the inbox's bar
 * answers in two passes, and on a cold index the first can be a small fraction of the answer, so
 * reporting it as a total made a partial answer look final (#102).
 *
 * The two are independent: an in-flight search starts out `complete = true`, which is why
 * completeness alone cannot gate the claim.
 */
fun searchCount(complete: Boolean, loading: Boolean): SearchCount =
    if (complete && !loading) SearchCount.EXACT else SearchCount.AT_LEAST

/**
 * An answer is complete only when EVERY leg ran to the end — the inbox's bar has two, the local
 * header index and the server's full-text index. Named, and called from the search itself, because
 * the mistake it guards is quiet: the server leg writes its verdict last, so `complete =
 * server.complete` let a good server answer forgive a local index that had already fallen over.
 */
fun searchComplete(local: Boolean, server: Boolean): Boolean = local && server

/** Which of the four mutually exclusive things a search surface puts on screen. */
enum class SearchDisplay {
    RESULTS,
    SPINNER,
    /** Nothing found, but the search stopped short — so nothing has been PROVEN absent. */
    INCOMPLETE_EMPTY,
    /** Nothing found by a search that ran to the end: the only case that may say "no results". */
    EMPTY,
}

/**
 * Results win over the spinner: once there are rows they stay, replacing them when the second pass
 * starts taking away what the user is already reading.
 *
 * The two empty cases are NOT the same claim: "No results" asserts that nothing matches, while a
 * search that stopped short has established no such thing, and saying so invites the user to reword
 * a query that was never actually run.
 */
fun searchDisplay(resultCount: Int, loading: Boolean, complete: Boolean): SearchDisplay = when {
    resultCount > 0 -> SearchDisplay.RESULTS
    loading -> SearchDisplay.SPINNER
    !complete -> SearchDisplay.INCOMPLETE_EMPTY
    else -> SearchDisplay.EMPTY
}
