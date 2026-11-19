package app.sterna.core.jmap

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision itself: given the size a message announces for a part, do we agree to pull it
 * into memory? No network, no server, that is the point, the refusal has to be decidable from
 * the message alone, before any round-trip.
 */
class DownloadLimitsTest {

    @Test fun anOrdinaryPartIsAccepted() {
        assertTrue(DownloadLimits.allows(180 * 1024, DownloadLimits.INLINE_IMAGE_MAX_BYTES))
        assertTrue(DownloadLimits.allows(4L * 1024 * 1024, DownloadLimits.ATTACHMENT_MAX_BYTES))
    }

    @Test fun exactlyTheLimitIsStillAccepted() {
        assertTrue(
            DownloadLimits.allows(DownloadLimits.INLINE_IMAGE_MAX_BYTES, DownloadLimits.INLINE_IMAGE_MAX_BYTES),
        )
        assertTrue(
            DownloadLimits.allows(DownloadLimits.ATTACHMENT_MAX_BYTES, DownloadLimits.ATTACHMENT_MAX_BYTES),
        )
    }

    @Test fun onePastTheLimitIsRefused() {
        assertFalse(
            DownloadLimits.allows(DownloadLimits.INLINE_IMAGE_MAX_BYTES + 1, DownloadLimits.INLINE_IMAGE_MAX_BYTES),
        )
        assertFalse(
            DownloadLimits.allows(DownloadLimits.ATTACHMENT_MAX_BYTES + 1, DownloadLimits.ATTACHMENT_MAX_BYTES),
        )
    }

    @Test fun anImageIsHeldToATighterCeilingThanAnAttachment() {
        // Nobody asked for the image; the user tapped the attachment.
        val twentyMb = 20L * 1024 * 1024
        assertFalse(DownloadLimits.allows(twentyMb, DownloadLimits.INLINE_IMAGE_MAX_BYTES))
        assertTrue(DownloadLimits.allows(twentyMb, DownloadLimits.ATTACHMENT_MAX_BYTES))
    }

    @Test fun anInviteIsCappedBelowWhatTheReaderWouldParse() {
        // Downloading more than the parser will accept only buys the buffer that OOMs the app.
        assertTrue(DownloadLimits.CALENDAR_MAX_BYTES < DownloadLimits.INLINE_IMAGE_MAX_BYTES)
        assertFalse(DownloadLimits.allows(30L * 1024 * 1024, DownloadLimits.CALENDAR_MAX_BYTES))
        assertTrue(DownloadLimits.allows(12_000, DownloadLimits.CALENDAR_MAX_BYTES))
    }

    @Test fun anUnstatedSizeIsNotATicket() {
        // A server that announces nothing passes this gate, the read is capped anyway.
        assertTrue(DownloadLimits.allows(0, DownloadLimits.INLINE_IMAGE_MAX_BYTES))
        assertTrue(DownloadLimits.allows(-1, DownloadLimits.INLINE_IMAGE_MAX_BYTES))
    }

    @Test fun enforceThrowsPastTheLimitAndCarriesTheSizes() {
        // The shared choke point downloadAttachment calls before the protocol branch, so IMAP and
        // JMAP alike refuse an oversized part (IMAP used to skip this, the check sat behind its
        // early return). The exception carries both sizes so the caller can word the refusal.
        val ex = assertThrows(ContentTooLargeException::class.java) {
            DownloadLimits.enforce(DownloadLimits.ATTACHMENT_MAX_BYTES + 1, DownloadLimits.ATTACHMENT_MAX_BYTES)
        }
        assertEquals(DownloadLimits.ATTACHMENT_MAX_BYTES + 1, ex.bytes)
        assertEquals(DownloadLimits.ATTACHMENT_MAX_BYTES, ex.maxBytes)
    }

    @Test fun enforceAllowsAtOrUnderTheLimitAndAnUnstatedSize() {
        // No throw for a part within the ceiling, exactly at it, or one whose size the server didn't
        // announce (0 / negative), the read is capped anyway.
        DownloadLimits.enforce(4L * 1024 * 1024, DownloadLimits.ATTACHMENT_MAX_BYTES)
        DownloadLimits.enforce(DownloadLimits.ATTACHMENT_MAX_BYTES, DownloadLimits.ATTACHMENT_MAX_BYTES)
        DownloadLimits.enforce(0, DownloadLimits.INLINE_IMAGE_MAX_BYTES)
        DownloadLimits.enforce(-1, DownloadLimits.INLINE_IMAGE_MAX_BYTES)
    }

    @Test fun aMessageCountsItsOversizedInlineImages() {
        val small = EmailBodyPart(blobId = "b1", cid = "small@x", type = "image/png", size = 40_000)
        val huge = EmailBodyPart(
            blobId = "b2",
            cid = "huge@x",
            type = "image/png",
            size = DownloadLimits.INLINE_IMAGE_MAX_BYTES + 1,
        )
        val attachment = EmailBodyPart(
            blobId = "b3",
            name = "doc.pdf",
            type = "application/pdf",
            size = DownloadLimits.INLINE_IMAGE_MAX_BYTES + 1,
        )
        val uncited = huge.copy(blobId = "b4", cid = "orphan@x")
        fun withHtml(vararg parts: EmailBodyPart) = Email(
            id = "m1",
            htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
            bodyValues = mapOf("h" to EmailBodyValue(value = "<img src=\"cid:small@x\"><img src=\"cid:huge@x\">")),
            attachments = parts.toList(),
        )
        // Only images the body cites count; the attachment and the uncited image are files, with their own ceiling.
        assertEquals(1, withHtml(small, huge, attachment, uncited).oversizedInlineImageCount())
        assertEquals(0, withHtml(small).oversizedInlineImageCount())
    }
}
