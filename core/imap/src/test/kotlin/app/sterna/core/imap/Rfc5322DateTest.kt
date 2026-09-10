package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [rfc5322DateMillis] executed, the reading of the `Date:` header the sender wrote. The instant
 * each shape denotes is written out in millis rather than recomputed here, so that inverting a
 * rule inside the function cannot be matched by the test.
 */
class Rfc5322DateTest {

    /** 2026-09-01T19:17:27Z, the instant nearly every case here denotes. */
    private val instant = 1_788_290_247_000L

    @Test fun `the day may be space-padded to two characters`() {
        assertEquals(instant, rfc5322DateMillis("Tue,  1 Sep 2026 19:17:27 +0000"))
        assertEquals(instant, rfc5322DateMillis("Tue, 01 Sep 2026 19:17:27 +0000"))
    }

    @Test fun `a day of week that contradicts the date is ignored, not obeyed`() {
        // The 1st of September 2026 is a Tuesday. Senders get this wrong and the header stays
        // legal, so the answer must be the same instant as the correct spelling.
        assertEquals(instant, rfc5322DateMillis("Mon, 1 Sep 2026 19:17:27 +0000"))
        assertEquals(instant, rfc5322DateMillis("Sun, 1 Sep 2026 19:17:27 +0000"))
    }

    @Test fun `the obsolete alphabetic zones of RFC 5322 are translated`() {
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 UT"))
        assertEquals(1_788_308_247_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 EST"))
        assertEquals(1_788_304_647_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 EDT"))
        assertEquals(1_788_311_847_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 CST"))
        assertEquals(1_788_308_247_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 CDT"))
        assertEquals(1_788_315_447_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 MST"))
        assertEquals(1_788_311_847_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 MDT"))
        assertEquals(1_788_319_047_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 PST"))
        assertEquals(1_788_315_447_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 PDT"))
    }

    @Test fun `an unlisted alphabetic zone is neither refused nor guessed at`() {
        // RFC 5322 §4.3 gives the military letters the opposite sign to what they mean, so any
        // zone not in the table is the unknown offset, which lands on the same instant `+0000`
        // does. What this case can still hold is the set of shapes that must not fall to 0L.
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 A"))
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 CEST"))
        // A zone the table does name, spelled in lower case: it is looked up, not defaulted.
        assertEquals(1_788_308_247_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 est"))
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 -0000"))
    }

    @Test fun `a numeric offset is read with or without its colon`() {
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 +00:00"))
        assertEquals(1_788_279_447_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 +03:00"))
        assertEquals(1_788_279_447_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 +0300"))
        assertEquals(1_788_308_247_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 -0500"))
        // Half-hour offsets: the minutes of the offset carry the sign of its hours.
        assertEquals(1_788_270_447_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 +0530"))
        assertEquals(1_788_302_847_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 -0330"))
    }

    @Test fun `blanks of any width and a folded header read the same`() {
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27\t+0000"))
        assertEquals(instant, rfc5322DateMillis("Tue,\t 1  Sep   2026 19:17:27  +0000"))
        // A header folded onto a second line (RFC 5322 §2.2.3): the CRLF and the blank that
        // continues it are part of the value the caller hands over.
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 2026\r\n 19:17:27 +0000"))
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep\r\n\t2026 19:17:27\r\n +0000"))
        assertEquals(instant, rfc5322DateMillis(" Tue, 1 Sep 2026 19:17:27 +0000 "))
    }

    @Test fun `a two-digit year sits in the 1970-2069 window`() {
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 26 19:17:27 +0000"))
        assertEquals(3_145_288_647_000L, rfc5322DateMillis("Tue, 1 Sep 69 19:17:27 +0000"))
        assertEquals(21_064_647_000L, rfc5322DateMillis("Tue, 1 Sep 70 19:17:27 +0000"))
        assertEquals(946_684_799_000L, rfc5322DateMillis("Fri, 31 Dec 99 23:59:59 +0000"))
        // Reading `26` as the year 26 of the common era is the shape this window exists against:
        // it costs nothing to parse and puts the message at the bottom of the folder for ever.
        assertNotEquals(-61_325_613_753_000L, rfc5322DateMillis("Tue, 1 Sep 26 19:17:27 +0000"))
    }

    @Test fun `a three-digit year is 1900 plus its value`() {
        assertEquals(-1_367_469_753_000L, rfc5322DateMillis("Tue, 1 Sep 026 19:17:27 +0000"))
    }

    // The four shapes below were read before this function existed and each has its own case, so
    // that a mutation killing one is never masked by an earlier assertion failing first.

    @Test fun `the month name is read whatever its case`() {
        assertEquals(instant, rfc5322DateMillis("Tue, 1 SEP 2026 19:17:27 +0000"))
        assertEquals(instant, rfc5322DateMillis("Tue, 1 sep 2026 19:17:27 +0000"))
    }

    @Test fun `GMT is UTC`() {
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 GMT"))
    }

    @Test fun `the day of week may be absent`() {
        assertEquals(instant, rfc5322DateMillis("1 Sep 2026 19:17:27 +0000"))
    }

    @Test fun `the seconds may be absent`() {
        assertEquals(1_788_290_220_000L, rfc5322DateMillis("Tue, 1 Sep 2026 19:17 +0000"))
    }

    @Test fun `a parenthesised comment is dropped rather than read`() {
        // Contrast witness on the guard that removes comments: the same header with and without
        // its `(MSK)` must be the one instant `+0300` denotes. Stop dropping the comment and the
        // first of these falls to 0L; read the comment as the zone and it moves off `+0300`.
        val commented = rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 +0300 (MSK)")
        assertEquals(rfc5322DateMillis("Tue, 1 Sep 2026 19:17:27 +0300"), commented)
        assertEquals(1_788_279_447_000L, commented)
        assertNotEquals(instant, commented)
        // A comment may sit where folding whitespace may, and against the token that follows it
        // (RFC 5322 §3.2.2), so dropping it has to leave a blank behind.
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 2026 (the first) 19:17:27 +0000"))
        assertEquals(instant, rfc5322DateMillis("Tue, 1 Sep 2026 (the first)19:17:27 +0000"))
        assertEquals(instant, rfc5322DateMillis("(sent) Tue, 1 Sep 2026 19:17:27 +0000"))
    }

    @Test fun `what cannot be read at all is zero`() {
        listOf(
            "",
            "   ",
            "Tue, 1 Sep 2026",
            "yesterday",
            // A month that is not one of the twelve, and an impossible calendar value.
            "Tue, 1 Sfp 2026 19:17:27 +0000",
            "Tue, 31 Sep 2026 19:17:27 +0000",
            "Tue, 1 Sep 2026 25:17:27 +0000",
            // An offset outside the range java.time accepts.
            "Tue, 1 Sep 2026 19:17:27 +9900",
            // The `INTERNALDATE` shape: it reaches this function only through a caller that took
            // the wrong value, and guessing on its behalf would hide that.
            "01-Sep-2026 19:17:27 +0000",
            // A legal date with anything else around it: the whole value must be the date, or
            // the reading is a guess at which part of the value was meant.
            "Delivered Tue, 1 Sep 2026 19:17:27 +0000",
            // A comment holding a comment: legal, never seen on the wire, and not handled.
            "Tue, 1 Sep 2026 19:17:27 +0300 (a(b))",
        ).forEach { assertEquals("«$it» must not be read as a date", 0L, rfc5322DateMillis(it)) }
    }
}
