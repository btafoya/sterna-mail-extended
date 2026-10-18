package app.sterna.core.data.mail

import app.sterna.core.data.db.CatchUpMark
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.MAILBOX_MODSEQ_READ_SQL
import app.sterna.core.data.db.MAILBOX_MODSEQ_WRITE_SQL
import app.sterna.core.data.db.MAILBOX_UIDVALIDITY_ADD_MODSEQ_SQL
import app.sterna.core.data.db.MAILBOX_UIDVALIDITY_CREATE_SQL
import app.sterna.core.data.db.MAILBOX_UIDVALIDITY_FIRST_SIGHT_SQL
import app.sterna.core.data.db.MAILBOX_UIDVALIDITY_RENUMBER_SQL
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.READ_AND_STAR_WRITE_SQL
import app.sterna.core.data.db.ReadAndStar
import app.sterna.core.data.db.RowNumbering
import java.io.Closeable
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The cache an IMAP folder read writes to, on in-memory SQLite, through the statements the DAOs
 * ship: the constants where a statement is one, the `@Query` text read from the source otherwise.
 * `upsert` is a whole-row `INSERT OR REPLACE`, which is what Room's `@Upsert` does to a row and
 * the reason a flag-only answer may not go through it.
 */
internal class JdbcMailCache : Closeable {

    private val db: Connection = run {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    init {
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE emails(" +
                    "id TEXT NOT NULL, accountId TEXT NOT NULL, mailboxId TEXT NOT NULL, threadId TEXT, " +
                    "subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT, " +
                    "seen INTEGER NOT NULL, flagged INTEGER NOT NULL, hasAttachment INTEGER NOT NULL, " +
                    "sortKey INTEGER NOT NULL, recipientsJson TEXT, replyToJson TEXT, ccJson TEXT, " +
                    "bccJson TEXT, uidValidity INTEGER, catchUp INTEGER NOT NULL DEFAULT 0, " +
                    "answered INTEGER NOT NULL DEFAULT 0, forwarded INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(accountId, id))",
            )
            st.executeUpdate(MAILBOX_UIDVALIDITY_CREATE_SQL)
            st.executeUpdate(MAILBOX_UIDVALIDITY_ADD_MODSEQ_SQL)
        }
    }

    override fun close() = db.close()

    private fun bind(sql: String, values: Map<String, Any?>): java.sql.PreparedStatement {
        val lists = values.filterValues { it is List<*> }.mapValues { (it.value as List<*>).size }
        val (positional, order) = DaoQuerySource.bindOrder(sql, lists)
        val flat = mutableListOf<Any?>()
        var i = 0
        while (i < order.size) {
            val value = values.getValue(order[i])
            if (value is List<*>) {
                flat.addAll(value)
                i += value.size
            } else {
                flat.add(value)
                i++
            }
        }
        val statement = db.prepareStatement(positional)
        flat.forEachIndexed { index, value ->
            when (value) {
                null -> statement.setNull(index + 1, java.sql.Types.NULL)
                is Boolean -> statement.setInt(index + 1, if (value) 1 else 0)
                is Long -> statement.setLong(index + 1, value)
                is Int -> statement.setInt(index + 1, value)
                else -> statement.setString(index + 1, value.toString())
            }
        }
        return statement
    }

    fun update(sql: String, values: Map<String, Any?> = emptyMap()): Int = bind(sql, values).use { it.executeUpdate() }

    fun <T> query(sql: String, values: Map<String, Any?> = emptyMap(), read: (ResultSet) -> T): List<T> =
        bind(sql, values).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } } }

    private fun longOrNull(rs: ResultSet, column: Int): Long? = rs.getLong(column).takeUnless { rs.wasNull() }

    // ---- emails ---------------------------------------------------------------------------------

    fun upsert(rows: List<EmailEntity>) = write("INSERT OR REPLACE", rows)

    /** What Room's `@Insert(onConflict = IGNORE)` does to a row: the cached one stays whole. */
    fun insertIgnoring(rows: List<EmailEntity>) = write("INSERT OR IGNORE", rows)

    private fun write(verb: String, rows: List<EmailEntity>) = rows.forEach { row ->
        update(
            "$verb INTO emails(id, accountId, mailboxId, threadId, subject, preview, receivedAt, " +
                "fromName, fromEmail, seen, flagged, hasAttachment, sortKey, recipientsJson, replyToJson, " +
                "ccJson, bccJson, uidValidity, catchUp, answered, forwarded) VALUES(:id, :accountId, :mailboxId, " +
                ":threadId, :subject, :preview, :receivedAt, :fromName, :fromEmail, :seen, :flagged, " +
                ":hasAttachment, :sortKey, :recipientsJson, :replyToJson, :ccJson, :bccJson, :uidValidity, " +
                ":catchUp, :answered, :forwarded)",
            mapOf(
                "id" to row.id, "accountId" to row.accountId, "mailboxId" to row.mailboxId,
                "threadId" to row.threadId, "subject" to row.subject, "preview" to row.preview,
                "receivedAt" to row.receivedAt, "fromName" to row.fromName, "fromEmail" to row.fromEmail,
                "seen" to row.seen, "flagged" to row.flagged, "hasAttachment" to row.hasAttachment,
                "sortKey" to row.sortKey, "recipientsJson" to row.recipientsJson,
                "replyToJson" to row.replyToJson, "ccJson" to row.ccJson, "bccJson" to row.bccJson,
                "uidValidity" to row.uidValidity, "catchUp" to row.catchUp,
                "answered" to row.answered, "forwarded" to row.forwarded,
            ),
        )
    }

    fun markWalked(accountId: String, mailboxId: String, ids: List<String>): Int =
        update(DaoQuerySource.emailDaoQuery("markWalked"), mapOf("accountId" to accountId, "mailboxId" to mailboxId, "ids" to ids))

    fun walkedCount(accountId: String, mailboxId: String): Int = query(
        DaoQuerySource.emailDaoQuery("walkedCountForMailbox"),
        mapOf("accountId" to accountId, "mailboxId" to mailboxId),
    ) { it.getInt(1) }.single()

    fun catchUpOf(accountId: String, ids: List<String>): List<CatchUpMark> =
        if (ids.isEmpty()) emptyList()
        else query(DaoQuerySource.emailDaoQuery("catchUpOf"), mapOf("accountId" to accountId, "ids" to ids)) {
            CatchUpMark(it.getString(1), it.getInt(2))
        }

    fun numberingOfRows(accountId: String, ids: List<String>): List<RowNumbering> =
        if (ids.isEmpty()) emptyList()
        else query(DaoQuerySource.emailDaoQuery("numberingOfRows"), mapOf("accountId" to accountId, "ids" to ids)) {
            RowNumbering(it.getString(1), longOrNull(it, 2))
        }

    fun unreadIds(accountId: String, mailboxId: String, limit: Int): List<String> = query(
        DaoQuerySource.emailDaoQuery("unreadKeys"),
        mapOf("accountId" to accountId, "mailboxId" to mailboxId, "limit" to limit),
    ) { it.getString(2) }

    /** `EmailDao.evictFromCacheKeepingIndex`: the statement of the delete it delegates to. */
    fun evict(accountId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        update(DaoQuerySource.emailDaoQuery("deleteRowsByIds"), mapOf("accountId" to accountId, "ids" to ids))
    }

    fun readAmong(accountId: String, ids: List<String>): List<String> =
        if (ids.isEmpty()) emptyList()
        else query(DaoQuerySource.emailDaoQuery("readAmong"), mapOf("accountId" to accountId, "ids" to ids)) { it.getString(1) }

    fun markUnread(accountId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        update(DaoQuerySource.emailDaoQuery("markUnread"), mapOf("accountId" to accountId, "ids" to ids))
    }

    fun idsForMailbox(accountId: String, mailboxId: String): List<String> =
        query(DaoQuerySource.emailDaoQuery("idsForMailbox"), mapOf("accountId" to accountId, "mailboxId" to mailboxId)) { it.getString(1) }

    fun idsWithPreviewForMailbox(accountId: String, mailboxId: String): List<String> = query(
        DaoQuerySource.emailDaoQuery("idsWithPreviewForMailbox"),
        mapOf("accountId" to accountId, "mailboxId" to mailboxId),
    ) { it.getString(1) }

    fun cachedPreviews(accountId: String, ids: List<String>, uidValidity: Long?): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        return query(
            DaoQuerySource.emailDaoQuery("cachedPreviews"),
            mapOf("accountId" to accountId, "ids" to ids, "uidValidity" to uidValidity),
        ) { it.getString(1) to it.getString(2) }.toMap()
    }

    fun setReadAndStar(accountId: String, ids: List<String>, seen: Boolean, flagged: Boolean, answered: Boolean, forwarded: Boolean) {
        update(
            READ_AND_STAR_WRITE_SQL,
            mapOf(
                "accountId" to accountId, "ids" to ids, "seen" to seen, "flagged" to flagged,
                "answered" to answered, "forwarded" to forwarded,
            ),
        )
    }

    fun readAndStarOf(accountId: String, id: String): ReadAndStar? =
        query(DaoQuerySource.emailDaoQuery("readAndStarOf"), mapOf("accountId" to accountId, "id" to id)) {
            ReadAndStar(
                seen = it.getBoolean("seen"),
                flagged = it.getBoolean("flagged"),
                answered = it.getBoolean("answered"),
                forwarded = it.getBoolean("forwarded"),
            )
        }.singleOrNull()

    /** `EmailDao.reconcileMailbox`'s decision, the shipped one, then its delete. */
    fun reconcile(accountId: String, mailboxId: String, keepIds: Set<String>, spareIds: List<String>, evictableIds: Set<String>?) {
        reconcileEvictions(
            cachedIds = evictableCachedIds(idsForMailbox(accountId, mailboxId), evictableIds),
            keepIds = keepIds,
            spareIds = spareIds.toHashSet(),
        ).forEach { batch ->
            update("DELETE FROM emails WHERE accountId = :accountId AND id IN (:ids)", mapOf("accountId" to accountId, "ids" to batch))
        }
    }

    /** Every column of every row of [accountId], as text, ordered by id. */
    fun rows(accountId: String): List<Map<String, String?>> =
        query("SELECT * FROM emails WHERE accountId = :accountId ORDER BY id", mapOf("accountId" to accountId)) { rs ->
            (1..rs.metaData.columnCount).associate { rs.metaData.getColumnName(it) to rs.getString(it) }
        }

    fun row(accountId: String, id: String): Map<String, String?>? = rows(accountId).firstOrNull { it["id"] == id }

    // ---- mailbox_uidvalidity --------------------------------------------------------------------

    val uidValidityDao: MailboxUidValidityDao = object : MailboxUidValidityDao {
        private fun key(accountId: String, mailboxId: String): Map<String, Any?> =
            mapOf("accountId" to accountId, "mailboxId" to mailboxId)

        override suspend fun recorded(accountId: String, mailboxId: String): Long? =
            query(DaoQuerySource.daoQuery("MailboxUidValidityEntity", "recorded"), key(accountId, mailboxId)) { longOrNull(it, 1) }
                .firstOrNull()

        override suspend fun recordIfAbsent(accountId: String, mailboxId: String, uidValidity: Long) {
            update(MAILBOX_UIDVALIDITY_FIRST_SIGHT_SQL, key(accountId, mailboxId) + ("uidValidity" to uidValidity))
        }

        override suspend fun renumber(accountId: String, mailboxId: String, uidValidity: Long) {
            update(MAILBOX_UIDVALIDITY_RENUMBER_SQL, key(accountId, mailboxId) + ("uidValidity" to uidValidity))
        }

        override suspend fun highestModSeq(accountId: String, mailboxId: String): Long? =
            query(MAILBOX_MODSEQ_READ_SQL, key(accountId, mailboxId)) { longOrNull(it, 1) }.firstOrNull()

        override suspend fun recordHighestModSeq(accountId: String, mailboxId: String, uidValidity: Long, highestModSeq: Long) {
            update(
                MAILBOX_MODSEQ_WRITE_SQL,
                key(accountId, mailboxId) + ("uidValidity" to uidValidity) + ("highestModSeq" to highestModSeq),
            )
        }

        override suspend fun deleteForAccount(accountId: String) {
            update(DaoQuerySource.daoQuery("MailboxUidValidityEntity", "deleteForAccount"), mapOf("accountId" to accountId))
        }

        override suspend fun deleteAll() {
            update(DaoQuerySource.daoQuery("MailboxUidValidityEntity", "deleteAll"))
        }
    }

    /** (uidValidity, highestModSeq) of every recorded folder, keyed "account/folder". */
    fun numbering(): Map<String, Pair<Long, Long?>> =
        query("SELECT accountId, mailboxId, uidValidity, highestModSeq FROM mailbox_uidvalidity") { rs ->
            "${rs.getString(1)}/${rs.getString(2)}" to (rs.getLong(3) to longOrNull(rs, 4))
        }.toMap()

    /** The shipped store over this table; what a renumbering drops elsewhere is not observed here. */
    fun store(): MailboxUidValidityStore =
        MailboxUidValidityStore(uidValidityDao, permissive(), permissive(), permissive(), PendingExpungeStore.None)

    private inline fun <reified T : Any> permissive(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> Unit } as T
}

/**
 * One IMAP folder behind a [ScriptedImapServer], answering the commands of a full walk and of a
 * delta from the same state. A UID sits [UID_OFFSET] above its first sequence number, so a command
 * naming one where the other was meant answers with the wrong messages. `HIGHESTMODSEQ` is stated
 * only to a SELECT that asked for CONDSTORE.
 */
internal class CondstoreFolder(count: Int, @Volatile var uidValidity: Long = 9L) {

    private val flags = LinkedHashMap<Long, MutableSet<String>>()
    private val modSeq = HashMap<Long, Long>()

    @Volatile var highest = 100L
    @Volatile var capabilities = "IMAP4rev1 CONDSTORE"

    /** Untagged lines slipped once into the answer of the next command starting with the key. */
    val slipIn: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /** Commands, by prefix, answered `NO` once. */
    val refuseOnce: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Commands, by prefix, answered `NO` every time. */
    val refuseAlways: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Commands, by prefix, on which the connection is dropped once. */
    val hangUpOnce: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile var down = false
    @Volatile var searchAnswersNothing = false

    init {
        for (seq in 1..count) arrive(seq + UID_OFFSET)
    }

    @Synchronized fun arrive(uid: Long) {
        flags[uid] = mutableSetOf("\\Seen")
        modSeq[uid] = ++highest
    }

    @Synchronized fun flag(uid: Long, flag: String) {
        flags.getValue(uid) += flag
        modSeq[uid] = ++highest
    }

    @Synchronized fun unflag(uid: Long, flag: String) {
        flags.getValue(uid) -= flag
        modSeq[uid] = ++highest
    }

    @Synchronized fun expunge(uid: Long) {
        flags.remove(uid)
        modSeq.remove(uid)
        highest++
    }

    /** The mod-sequences counted again from nothing, the numbering kept: a server that rebuilt its index. */
    @Synchronized fun rebuildIndex() {
        highest = 0L
        flags.keys.forEach { modSeq[it] = ++highest }
    }

    private fun once(prefixes: MutableSet<String>, line: String): Boolean =
        prefixes.firstOrNull { line.startsWith(it) }?.let { prefixes.remove(it) } ?: false

    private fun slipped(line: String): String =
        slipIn.keys.firstOrNull { line.startsWith(it) }?.let { slipIn.remove(it) }.orEmpty()

    private fun envelope(seq: Int, uid: Long): String =
        "* $seq FETCH (UID $uid FLAGS (${flags.getValue(uid).joinToString(" ")}) " +
            "INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Message $uid\" " +
            "((\"Dana\" NIL \"dana\" \"example.org\")) NIL NIL " +
            "((\"Alex\" NIL \"alex\" \"example.org\")) NIL NIL NIL \"<m$uid@example.org>\") " +
            "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n"

    /** The UIDs a UID set names among [uids]; `n:*` above the last one still names the last (RFC 3501). */
    private fun named(set: String, uids: List<Long>): List<Long> = set.split(',').flatMap { part ->
        val low = part.substringBefore(':').toLong()
        when {
            ':' !in part -> uids.filter { it == low }
            part.endsWith(":*") -> if (uids.isNotEmpty() && low > uids.last()) uids.takeLast(1) else uids.filter { it >= low }
            else -> uids.filter { it in low..part.substringAfter(':').toLong() }
        }
    }.distinct()

    /** Never silent: a responder that threw would leave the client waiting for ever. */
    fun respond(tag: String, line: String): String =
        try {
            answer(tag, line)
        } catch (failed: Throwable) {
            "$tag BAD the scripted folder failed on '$line': $failed\r\n"
        }

    @Synchronized private fun answer(tag: String, line: String): String {
        if (down || once(hangUpOnce, line)) return ScriptedImapServer.HANG_UP
        if (once(refuseOnce, line) || refuseAlways.any { line.startsWith(it) }) return "$tag NO refused\r\n"
        val uids = flags.keys.toList()
        val ok = "$tag OK done\r\n"
        val extra = slipped(line)
        return when {
            line.startsWith("CAPABILITY") -> "* CAPABILITY $capabilities\r\n$ok"
            line.startsWith("LIST") -> "* LIST (\\HasNoChildren) \".\" \"INBOX\"\r\n$ok"
            line.startsWith("SELECT") ->
                "* ${uids.size} EXISTS\r\n* OK [UIDVALIDITY $uidValidity] ok\r\n" +
                    (if (line.endsWith("(CONDSTORE)")) "* OK [HIGHESTMODSEQ $highest] ok\r\n" else "") +
                    "$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("SEARCH UNSEEN") -> {
                val unseen = uids.withIndex().filter { "\\Seen" !in flags.getValue(it.value) }.map { it.index + 1 }
                "* SEARCH ${unseen.joinToString(" ")}\r\n$ok"
            }
            line.startsWith("UID SEARCH ") -> {
                val floor = line.removePrefix("UID SEARCH ").substringBefore(":*").toInt()
                val inWindow = if (floor > uids.size) uids.takeLast(1) else uids.drop(floor - 1)
                val live = if (searchAnswersNothing) emptyList() else inWindow.filterNot { "\\Deleted" in flags.getValue(it) }
                "$extra* SEARCH ${live.joinToString(" ")}\r\n$ok"
            }
            line.startsWith("UID FETCH ") && "CHANGEDSINCE" in line -> {
                val since = line.substringAfter("CHANGEDSINCE ").substringBefore(')').toLong()
                val set = line.removePrefix("UID FETCH ").substringBefore(' ')
                extra + named(set, uids).filter { modSeq.getValue(it) > since }.joinToString("") { uid ->
                    "* ${uids.indexOf(uid) + 1} FETCH (UID $uid FLAGS (${flags.getValue(uid).joinToString(" ")}) " +
                        "MODSEQ (${modSeq.getValue(uid)}))\r\n"
                } + ok
            }
            line.startsWith("UID FETCH ") && "ENVELOPE" in line -> {
                val set = line.removePrefix("UID FETCH ").substringBefore(' ')
                extra + named(set, uids).joinToString("") { envelope(uids.indexOf(it) + 1, it) } + ok
            }
            line.startsWith("UID FETCH ") && "BODY.PEEK[" in line -> {
                val set = line.removePrefix("UID FETCH ").substringBefore(' ')
                val section = line.substringAfter("BODY.PEEK[").substringBefore(']')
                named(set, uids).joinToString("") { uid ->
                    val text = "Opening of $uid"
                    "* ${uids.indexOf(uid) + 1} FETCH (UID $uid BODY[$section]<0> {${text.length}}\r\n$text)\r\n"
                } + ok
            }
            line.startsWith("FETCH ") -> {
                val range = line.removePrefix("FETCH ").substringBefore(' ')
                val first = range.substringBefore(':').toInt()
                val last = range.substringAfter(':').toInt()
                extra + (first..minOf(last, uids.size)).joinToString("") { envelope(it, uids[it - 1]) } + ok
            }
            else -> ok
        }
    }

    companion object {
        const val UID_OFFSET = 100L
    }
}

internal fun ScriptedImapServer.sequenceFetches(): List<String> = issued().filter { it.startsWith("FETCH ") }

internal fun ScriptedImapServer.envelopeFetchesByUid(): List<String> =
    issued().filter { it.startsWith("UID FETCH ") && "ENVELOPE" in it }

internal fun ScriptedImapServer.selects(): List<String> = issued().filter { it.startsWith("SELECT") }
