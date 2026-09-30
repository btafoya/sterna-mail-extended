package app.sterna.core.jmap.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SmimeSignaturePartsTest {

    @Test fun `a detached S-MIME signature is never listed as a file, named or not`() {
        val parts = listOf(
            EmailBodyPart(blobId = "b1", name = "smime.p7s", type = "application/pkcs7-signature"),
            EmailBodyPart(blobId = "b2", name = "smime.p7s", type = "application/x-pkcs7-signature"),
            EmailBodyPart(partId = "2", name = "pkcs7-signature", type = "application/pkcs7-signature"),
            EmailBodyPart(partId = "3", name = "x-pkcs7-signature", type = "application/x-pkcs7-signature"),
            EmailBodyPart(blobId = "b3", name = "smime.p7m", type = "application/pkcs7-mime"),
            EmailBodyPart(blobId = "b4", name = "doc.pdf", type = "application/pdf"),
        )
        assertEquals(
            listOf("smime.p7m", "doc.pdf"),
            Email(id = "1", attachments = parts).fileAttachmentParts().map { it.name },
        )
    }
}
