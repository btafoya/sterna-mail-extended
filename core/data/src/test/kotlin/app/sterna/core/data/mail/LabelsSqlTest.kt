package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailLabels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shipped `@Query` text of `EmailDao.labelsJsonOf`, `setLabels` and `labelsOfAccount`, read from
 * the source by [DaoQuerySource] and run over SQLite with two accounts of one server holding the same
 * message id (#31). What this does not cover: Room's own binding, and the `Flow` of `labelsOfAccount`,
 * whose statement alone is run here.
 */
class LabelsSqlTest {

    private val cache = JdbcMailCache()

    @After fun close() = cache.close()

    private fun row(accountId: String, id: String, labels: List<String>) = EmailEntity(
        id = id,
        accountId = accountId,
        mailboxId = "inbox",
        threadId = "t-$id",
        subject = "Subject",
        preview = "Preview",
        receivedAt = "2026-09-01T10:00:00Z",
        fromName = "Alex",
        fromEmail = "alex@example.test",
        seen = false,
        flagged = false,
        hasAttachment = false,
        sortKey = 1L,
        labelsJson = EmailLabels.encode(labels),
    )

    private fun labelsJsonOf(accountId: String, id: String): String? =
        cache.query(DaoQuerySource.emailDaoQuery("labelsJsonOf"), mapOf("accountId" to accountId, "id" to id)) { it.getString(1) }
            .singleOrNull()

    @Test fun `a label written on one account leaves the same id of the other account alone`() {
        cache.upsert(listOf(row(A, "m1", listOf("work")), row(B, "m1", listOf("home"))))

        val changed = cache.update(
            DaoQuerySource.emailDaoQuery("setLabels"),
            mapOf("accountId" to A, "id" to "m1", "labelsJson" to EmailLabels.encode(listOf("work", "x"))),
        )

        assertEquals(1, changed)
        assertEquals(EmailLabels.encode(listOf("work", "x")), labelsJsonOf(A, "m1"))
        assertEquals(EmailLabels.encode(listOf("home")), labelsJsonOf(B, "m1"))
    }

    @Test fun `the labels of an account are its own rows' that carry any, each blob once`() {
        cache.upsert(
            listOf(
                row(A, "m1", listOf("work")),
                row(A, "m2", listOf("work")),
                row(A, "m3", emptyList()),
                row(A, "m4", listOf("b", "a")),
                row(B, "m1", listOf("home")),
            ),
        )

        val blobs = cache.query(DaoQuerySource.emailDaoQuery("labelsOfAccount"), mapOf("accountId" to A)) { it.getString(1) }

        assertEquals(
            setOf(EmailLabels.encode(listOf("work")), EmailLabels.encode(listOf("b", "a"))),
            blobs.toSet(),
        )
        assertEquals(2, blobs.size)
    }

    private companion object {
        const val A = "account-a"
        const val B = "account-b"
    }
}
