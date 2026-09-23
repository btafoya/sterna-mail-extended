package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * The `Date:` header as [OutgoingMime.build] writes it: the device's wall clock and offset.
 * The build container runs in UTC, so every case here pins a zone with a non-zero offset.
 */
class OutgoingMimeDateTest {

    // 2026-09-01T14:22:37Z
    private val instant = 1_788_272_557_000L

    private fun msg() = OutgoingMessage(
        from = "alice@example.com",
        to = listOf("bob@example.com"),
        subject = "Hi",
        body = "hello",
        messageId = "id-1@example.com",
        dateMillis = instant,
    )

    private fun dateLine(mime: String): String = mime.split("\r\n").single { it.startsWith("Date: ") }

    private fun <T> withDefaultZone(id: String, block: () -> T): T {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        try {
            return block()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test fun `the header is the wall clock and offset of the zone it is given`() {
        val mime = OutgoingMime.build(msg(), zone = ZoneId.of("America/Sao_Paulo"))
        assertEquals("Date: Tue, 1 Sep 2026 11:22:37 -0300", dateLine(mime))
    }

    @Test fun `without a zone, the header carries the device's offset`() {
        val mime = withDefaultZone("Asia/Tokyo") { OutgoingMime.build(msg()) }
        assertEquals("Date: Tue, 1 Sep 2026 23:22:37 +0900", dateLine(mime))
    }

    @Test fun `a French device still writes the header in English`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
        val mime = try {
            withDefaultZone("Europe/Paris") { OutgoingMime.build(msg()) }
        } finally {
            Locale.setDefault(saved)
        }
        assertEquals("Date: Tue, 1 Sep 2026 16:22:37 +0200", dateLine(mime))
    }

    @Test fun `the header names the instant of the message, whatever the zone`() {
        val mime = withDefaultZone("America/Sao_Paulo") { OutgoingMime.build(msg()) }
        val parsed = ZonedDateTime.parse(
            dateLine(mime).removePrefix("Date: "),
            DateTimeFormatter.RFC_1123_DATE_TIME,
        )
        assertEquals(instant, parsed.toInstant().toEpochMilli())
    }
}
