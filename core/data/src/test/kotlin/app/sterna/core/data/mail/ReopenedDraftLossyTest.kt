package app.sterna.core.data.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [reopenedDraftIsLossy], EXECUTED — the verdict the composer computes when it reopens a saved
 * draft, and the one thing standing between that draft and its destruction on the next send
 * ([SendReplacesDraftTest] runs the decision that reads it, [DraftSaveTest] the rule underneath).
 *
 * Every case below is a hand-written pair (inputs → verdict): the test never recomputes the rule
 * it is checking, so inverting any leg of the shipped function has to turn a line red here.
 */
class ReopenedDraftLossyTest {

    /** The witness: a plain-text draft with nothing this editor drops may be replaced. */
    @Test fun aPlainDraftWithNothingThisEditorDropsIsNotLossy() {
        assertFalse(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    @Test fun anHtmlBodyAloneIsLossy() {
        // Flattened to plain text on open: sending and destroying loses the formatting for good.
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = true,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    @Test fun anInlineImageAloneIsLossy() {
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 1,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    @Test fun aCalendarPartAloneIsLossy() {
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 1,
                receiptHeader = null,
            ),
        )
    }

    /**
     * ⭐ The defect this leg closes: the draft asked for a read receipt, the composer's checkbox
     * comes back clear (debt D1), so the message goes out asking for nothing — and destroying the
     * original takes the only copy that carried the request, silently.
     */
    @Test fun aReadReceiptRequestAloneIsLossy() {
        assertTrue(
            "a draft whose Disposition-Notification-To names a destination cannot be reproduced " +
                "by this composer, so it must not be destroyed on send",
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "<ann@example.org>",
            ),
        )
    }

    /** The header as senders actually write it: a display name, and several destinations. */
    @Test fun aReadReceiptRequestNamingSeveralDestinationsIsLossy() {
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "Ann Lee <ann@example.org>, ops@example.org",
            ),
        )
    }

    /** No header at all: nothing was asked for, so nothing can be lost by this leg. */
    @Test fun noReceiptHeaderIsNotLossy() {
        assertFalse(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = null,
            ),
        )
    }

    @Test fun anEmptyReceiptHeaderIsNotLossy() {
        assertFalse(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "",
            ),
        )
    }

    @Test fun aBlankReceiptHeaderIsNotLossy() {
        assertFalse(
            "a header made of whitespace names no destination — keeping every such draft would " +
                "be a duplicate in Drafts on every save, bought with nothing",
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "   ",
            ),
        )
    }

    /**
     * ⭐ A header that is THERE but names no address (`<>`, the shape a server hands back for a
     * request it could not parse) is PRESENT-BUT-UNREADABLE, and that is an unknown, not a "no".
     * The sender wrote something in that header; this composer cannot reproduce it and cannot say
     * what it was, so the original is kept — exactly what [copiedAddressesOrNull] does with an
     * envelope entry it cannot read. ⛔ [ReadReceiptHeader.recipients] must NOT be the judge here:
     * it answers the same empty list for "absent" and for "unreadable", and using it would make
     * this case authorise the destroy of the only copy holding the request.
     */
    @Test fun aReceiptHeaderNamingNoAddressIsLossy() {
        assertTrue(
            "a present but unreadable receipt header is an unknown, and an unknown keeps the " +
                "original — the cost is a duplicate in Drafts, which is deletable",
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "<>",
            ),
        )
    }

    /** The other unreadable shapes, each one a header the sender did write. */
    @Test fun anUnreadableReceiptHeaderIsLossyWhateverItsShape() {
        for (header in listOf("<>", "Ann Lee <>", "not-an-address", "undisclosed-recipients:;", ",")) {
            assertTrue(
                "receiptHeader = \"$header\" is present but names no address: unknown, keep",
                reopenedDraftIsLossy(
                    hasHtmlBody = false,
                    inlineImageCount = 0,
                    calendarPartCount = 0,
                    receiptHeader = header,
                ),
            )
        }
    }

    /** Legs are independent: a receipt request stays decisive next to a clean body and back. */
    @Test fun theReceiptLegDecidesOnItsOwnBesideACleanBody() {
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = true,
                inlineImageCount = 2,
                calendarPartCount = 1,
                receiptHeader = "ann@example.org",
            ),
        )
        // …and a clean body does not rescue an unreadable header: the leg alone decides.
        assertTrue(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "not-an-address",
            ),
        )
        // The witness on the other side, so this is not a test that only ever says "lossy":
        assertFalse(
            reopenedDraftIsLossy(
                hasHtmlBody = false,
                inlineImageCount = 0,
                calendarPartCount = 0,
                receiptHeader = "\t \n ",
            ),
        )
    }
}
