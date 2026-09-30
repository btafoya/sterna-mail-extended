package app.sterna.ui.compose

/** What the composer shows of a forward's carried original, under the note. Never part of `body`. */
sealed interface ForwardedPreview {
    data object Pending : ForwardedPreview
    /** [text] is `forwarded.text` itself, the string `bodiesForSend` appends, never a rebuilt copy. */
    data class Ready(val text: String) : ForwardedPreview
    data object None : ForwardedPreview
}

/** [opening] is null for a reopened outbox row: no mode, and the original only if the row kept it apart. */
internal fun forwardedPreview(opening: ComposeOpening?, forwarded: ForwardedBlocks?, failed: Boolean): ForwardedPreview =
    when (opening) {
        null -> if (forwarded != null) ForwardedPreview.Ready(forwarded.text) else ForwardedPreview.None
        ComposeOpening.FORWARD -> when {
            forwarded != null -> ForwardedPreview.Ready(forwarded.text)
            failed -> ForwardedPreview.None
            else -> ForwardedPreview.Pending
        }
        // The original is its attachment chip, already on screen.
        ComposeOpening.FORWARD_ATTACHMENT -> ForwardedPreview.None
        ComposeOpening.REPLY, ComposeOpening.REPLY_ALL -> ForwardedPreview.None
    }
