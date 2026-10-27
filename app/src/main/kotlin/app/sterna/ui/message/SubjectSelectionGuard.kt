package app.sterna.ui.message

/**
 * Decides when a selection possibly open on the reader's subject must be dropped: at the first touch
 * on the header outside the subject, and as soon as the subject starts moving up, the header folding
 * away under a scrolled body. `SelectionContainer` exposes no selection to read or clear, so the
 * caller drops it by recomposing the container under a new `key`, which disposes its manager and,
 * with it, both the handles and the floating toolbar.
 *
 * Only a touch on the subject arms it: a long-press can only start there. Without the arming, every
 * frame of a scroll would rebuild the subject.
 */
internal class SubjectSelectionGuard {
    private var armed = false
    private var lastTop: Float? = null

    /** A pointer went down on the header, [onSubject] or elsewhere. True when the caller must drop. */
    fun touched(onSubject: Boolean): Boolean {
        if (onSubject) {
            armed = true
            return false
        }
        return drop()
    }

    /** The subject was placed with its top at [top], in root coordinates. True when the caller must drop. */
    fun placed(top: Float): Boolean {
        val last = lastTop
        lastTop = top
        return last != null && top < last && drop()
    }

    private fun drop(): Boolean {
        if (!armed) return false
        armed = false
        return true
    }
}
