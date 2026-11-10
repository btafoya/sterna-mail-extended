package app.sterna.ui.message

/**
 * The ring of the body area while the opened message's body is still being fetched. The header is
 * painted from the cached list row before the fetch returns, so the screen has left
 * `MessageState.Loading` and its whole-screen ring, while the body-layout ring waits on a body: with
 * neither, the body area would stay blank for as long as the fetch takes.
 */
internal object BodyFetchRing {
    /**
     * [hasBody] is whether the page holds a body yet, [spinnerDue] whether the page's 500 ms grace
     * period is over, so a body read from the cache never flashes a ring. A page that holds a body is
     * the layout ring's alone: answering true there would stack two rings over the same spot.
     */
    fun shown(hasBody: Boolean, spinnerDue: Boolean): Boolean = !hasBody && spinnerDue
}
