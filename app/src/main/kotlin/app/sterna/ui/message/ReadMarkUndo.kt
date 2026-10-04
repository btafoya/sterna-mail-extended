package app.sterna.ui.message

import app.sterna.core.jmap.model.Email

/**
 * A read mark painted by the reader's settle and taken back because its write failed: the page then
 * shows what the cache and the server hold, and the next settle writes again.
 *
 * The view model applies it to what the page holds at that moment, never to the message captured at
 * the settle, or a star tapped during the write would be lost. It does so on the main dispatcher:
 * the write runs on the app's scope, and the fields it resets are otherwise only written from the
 * main thread. Resetting `anchorMarked` is the half that matters most: left set, `anchorDisplay`
 * repaints the mark on the next publication and no later settle writes it.
 */
internal fun Email.withoutReadMark(): Email =
    copy(keywords = keywords - "\$seen")

/**
 * The message whose painted read mark is to be taken back, or null when the reader has moved on.
 * The write outlives the page and can return after this view model was handed another message, or
 * the same id on another account: ids repeat across accounts.
 */
internal fun readMarkToUndo(shownId: String?, shownAccount: String?, writtenId: String, writtenAccount: String): String? =
    when {
        shownId != writtenId -> null
        shownAccount != writtenAccount -> null
        else -> writtenId
    }
