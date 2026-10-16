package app.sterna.core.data.mail

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import app.sterna.core.data.db.OutboxState
import app.sterna.core.jmap.model.Email

/**
 * Whether an outbox row in [state] lights the mark of the original it names. A shown mark is the
 * `emails` column or such a row, never a value written ahead: leaving these states, or the table,
 * is what puts the mark out, so cancelling, deleting, keeping as a draft and a final refusal have
 * nothing to clear. INTERRUPTED stays lit, the message may well have left.
 */
fun outboxRowLightsOriginalMark(state: OutboxState): Boolean = when (state) {
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING, OutboxState.EDITING, OutboxState.INTERRUPTED -> true
    OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT -> false
}

/** The two marks as a surface draws them for one message. */
data class ShownReplyMarks(val answered: Boolean, val forwarded: Boolean)

private val ReplyMark.column: String
    get() = when (this) {
        ReplyMark.ANSWERED -> "answered"
        ReplyMark.FORWARDED -> "forwarded"
    }

/**
 * [mark] as shown for the message keyed ([accountExpr], [idExpr]), whose stored column reads
 * [columnExpr]. The one statement of the rule: every reader below is built on it, so the list, the
 * search results and the open message cannot disagree. The account is part of the key: two
 * accounts of one server share ids.
 */
internal fun shownReplyMarkSql(accountExpr: String, idExpr: String, columnExpr: String, mark: ReplyMark): String {
    val lit = OutboxState.entries.filter(::outboxRowLightsOriginalMark).joinToString(", ") { "'${it.name}'" }
    return "($columnExpr != 0 OR EXISTS (SELECT 1 FROM outbox WHERE outbox.originalAccountId = $accountExpr " +
        "AND outbox.originalEmailId = $idExpr AND outbox.originalMark = '${mark.name}' AND outbox.state IN ($lit)))"
}

/**
 * [listSql], a paged list's statement, with `shownAnswered` and `shownForwarded` added to each of
 * its rows. Read in the same statement as the rows, so a delivery, which deletes the outbox row and
 * writes the column in one transaction, is seen whole: no read in between finds neither.
 */
internal fun withShownReplyMarksSql(listSql: String): String =
    "SELECT r.*, " +
        shownReplyMarkSql("r.accountId", "r.id", "r.${ReplyMark.ANSWERED.column}", ReplyMark.ANSWERED) + " AS shownAnswered, " +
        shownReplyMarkSql("r.accountId", "r.id", "r.${ReplyMark.FORWARDED.column}", ReplyMark.FORWARDED) + " AS shownForwarded " +
        "FROM ($listSql) AS r"

/** [list] read through [withShownReplyMarksSql]; it adds no placeholder, so the binds are [list]'s own.
 *  `EmailDao.pagingSource` and `EmailDao.conversationPagingSource` observe `OutboxEntity` for it,
 *  and `EmailDao.observeShownReplyMarks` too: an outbox row coming, changing state or going
 *  changes what is shown. */
internal class ShownReplyMarksQuery(private val list: SupportSQLiteQuery) : SupportSQLiteQuery {
    override val sql: String = withShownReplyMarksSql(list.sql)
    override val argCount: Int get() = list.argCount
    override fun bindTo(statement: SupportSQLiteProgram) = list.bindTo(statement)
}

/**
 * The shown marks of [keyCount] (account id, email id) pairs, bound in that order. One row per
 * pair, cached or not: an original the cache does not hold still lights from its outbox row.
 */
internal fun shownReplyMarksOfSql(keyCount: Int): String {
    val keys = List(keyCount) { "(?, ?)" }.joinToString(", ")
    return "WITH k(accountId, id) AS (VALUES $keys) " +
        "SELECT k.accountId AS accountId, k.id AS id, " +
        shownReplyMarkSql("k.accountId", "k.id", "COALESCE(emails.${ReplyMark.ANSWERED.column}, 0)", ReplyMark.ANSWERED) + " AS answered, " +
        shownReplyMarkSql("k.accountId", "k.id", "COALESCE(emails.${ReplyMark.FORWARDED.column}, 0)", ReplyMark.FORWARDED) + " AS forwarded " +
        "FROM k LEFT JOIN emails ON emails.accountId = k.accountId AND emails.id = k.id"
}

internal fun shownReplyMarksOfQuery(keys: List<Pair<String, String>>): SimpleSQLiteQuery =
    SimpleSQLiteQuery(shownReplyMarksOfSql(keys.size), keys.flatMap { listOf(it.first, it.second) }.toTypedArray())

/** Pairs per [shownReplyMarksOfQuery]: two binds each, under SQLite's 999. */
internal const val SHOWN_MARK_KEYS_PER_QUERY = 400

/**
 * This message with the marks [shown] holds for it added. Never takes one away: the message may
 * come from the server and not from the cache, where the column reads 0 and the keyword is the
 * only witness of a mark set elsewhere.
 */
fun Email.withShownReplyMarks(shown: Map<EmailKey, ShownReplyMarks>): Email {
    val marks = shown[EmailKey(accountId ?: return this, id)] ?: return this
    val added = listOfNotNull(
        ("\$answered" to true).takeIf { marks.answered && !isAnswered },
        ("\$forwarded" to true).takeIf { marks.forwarded && !isForwarded },
    )
    return if (added.isEmpty()) this else copy(keywords = keywords + added)
}
