package app.sterna.core.data.db

import androidx.room.Embedded

/**
 * One row of the flat paged list: [email] as stored, and its marks as shown, which
 * `withShownReplyMarksSql` adds beside it. `ConversationRow` carries the same two columns.
 */
data class EmailListRow(
    @Embedded val email: EmailEntity,
    val shownAnswered: Boolean,
    val shownForwarded: Boolean,
)

/** One answer of `EmailDao.observeShownReplyMarks`, built by `shownReplyMarksOfSql`. */
data class ShownReplyMarkRow(
    val accountId: String,
    val id: String,
    val answered: Boolean,
    val forwarded: Boolean,
)
