package app.sterna.core.data.db

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SmimeCertificateDao] over a real SQLite, as [JdbcSmimeCertificateDao] runs it: the shipped `@Query` statements,
 * the shipped insert-then-update and the shipped validity filter.
 *
 * The certificate is `reader-cert.pem`, valid from 2026-10-02T13:06:45Z to 2126-09-08T13:06:45Z, read at a clock
 * the test sets; the other "certificates" are octets that parse as none, which the store holds all the same.
 */
class SmimeCertificateDaoSqlTest {
    private val dao = JdbcSmimeCertificateDao()

    @After fun tearDown() = dao.close()

    private val reader: ByteArray = java.security.cert.CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/smime/reader-cert.pem")!!).encoded

    private val in2030 = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()

    private fun rows() = dao.rows()

    private val older = byteArrayOf(1, 1, 1)
    private val newer = byteArrayOf(2, 2, 2)

    @Test fun `a certificate from a more recent message replaces the one kept`() = runBlocking {
        dao.retain("bob@example.org", older, 100)
        dao.retain("bob@example.org", newer, 200)

        assertEquals(listOf(Triple("bob@example.org", newer.toList(), 200L)), rows())
    }

    @Test fun `a certificate from an older message, or one of the same date, replaces nothing`() = runBlocking {
        dao.retain("bob@example.org", newer, 200)
        dao.retain("bob@example.org", older, 100)
        dao.retain("bob@example.org", older, 200)

        assertEquals(listOf(Triple("bob@example.org", newer.toList(), 200L)), rows())
    }

    @Test fun `the address is kept lowercased, so another case of it is the same correspondent`() = runBlocking {
        dao.retain("Bob@Example.ORG", older, 100)
        dao.retain("bob@example.org", newer, 50)

        assertEquals(listOf(Triple("bob@example.org", older.toList(), 100L)), rows())
    }

    @Test fun `a valid certificate is found under the address as the caller wrote it, whatever its case`() = runBlocking {
        dao.retain("Reader@Example.test", reader, 100)

        val found = dao.certificatesFor(listOf("READER@example.TEST", "nobody@example.test"), now = in2030)

        assertEquals(setOf("READER@example.TEST"), found.keys)
        assertArrayEquals(reader, found.getValue("READER@example.TEST"))
    }

    @Test fun `an expired certificate, or one not yet valid, counts as none`() = runBlocking {
        dao.retain("reader@example.test", reader, 100)

        assertEquals(setOf("reader@example.test"), dao.certificatesFor(listOf("reader@example.test"), now = in2030).keys)
        assertEquals(
            "a day after it expired",
            emptyMap<String, ByteArray>(),
            dao.certificatesFor(listOf("reader@example.test"), now = Instant.parse("2126-09-09T13:06:45Z").toEpochMilli()),
        )
        assertEquals(
            "a day before it was valid",
            emptyMap<String, ByteArray>(),
            dao.certificatesFor(listOf("reader@example.test"), now = Instant.parse("2026-10-01T13:06:45Z").toEpochMilli()),
        )
        assertEquals(
            "octets that are no certificate",
            emptyMap<String, ByteArray>(),
            dao.retain("junk@example.test", older, 100).let { dao.certificatesFor(listOf("junk@example.test"), now = in2030) },
        )
    }

    @Test fun `more addresses than SQLite binds at once are all answered`() = runBlocking {
        dao.retain("reader@example.test", reader, 100)
        val many = (1..1200).map { "r$it@example.test" } + "reader@example.test"

        assertEquals(setOf("reader@example.test"), dao.certificatesFor(many, now = in2030).keys)
    }
}
