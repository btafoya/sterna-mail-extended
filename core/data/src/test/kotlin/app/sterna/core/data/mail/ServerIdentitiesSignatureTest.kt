package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Identity
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerIdentitiesSignatureTest {

    @Test fun aServerIdentityWithOnlyAnHtmlImageSignatureGetsAText() {
        val img = "<img src=\"https://example.org/sig.png\">"
        val stored = storedServerIdentities(
            listOf(Identity(id = "i", email = "alex@masto.top", textSignature = null, htmlSignature = img)),
        ).single()
        assertEquals("[image]", stored.signature)
        assertEquals(img, stored.signatureHtml)
    }
}
