package app.sterna.core.data.account

import app.sterna.core.data.text.htmlToText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signature split (plain text + optional HTML) must cost nothing to existing installs:
 */
class StoredIdentitySignatureTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun identityStoredBeforeTheSplitStillReadsBack() {
        val legacy = """{"id":"a","name":"Alex","email":"alex@masto.top","signature":"Alex\n+33"}"""
        val identity = json.decodeFromString(StoredIdentity.serializer(), legacy)
        assertEquals("Alex\n+33", identity.signature)
        assertEquals("", identity.signatureHtml)
    }

    @Test fun accountStoredBeforeTheSplitStillReadsBack() {
        val legacy = """
            {"id":"acc","server":"https://s","username":"alex@masto.top",
             "identities":[{"id":"a","name":"Alex","email":"alex@masto.top","signature":"Alex"}]}
        """.trimIndent()
        val account = json.decodeFromString(StoredAccount.serializer(), legacy)
        assertEquals("Alex", account.identities.single().signature)
        assertEquals("", account.identities.single().signatureHtml)
    }

    @Test fun roundTripsThroughJson() {
        val identity = StoredIdentity("a", "Alex", "alex@masto.top", "Alex", "<b>Alex</b>")
        val back = json.decodeFromString(
            StoredIdentity.serializer(),
            json.encodeToString(StoredIdentity.serializer(), identity),
        )
        assertEquals(identity, back)
    }

    // --- Legacy HTML sitting in the plain-text field (what the old "Import HTML" wrote) ----------

    @Test fun rawHtmlSignatureIsSplitIntoTextAndHtml() {
        val legacy = StoredIdentity("a", "Alex", "alex@masto.top", "<p>Alex Rivera</p><p>Acme</p>")
        val split = legacy.withSplitSignature()
        assertEquals("Alex Rivera\nAcme", split.signature)
        assertEquals("<p>Alex Rivera</p><p>Acme</p>", split.signatureHtml)
    }

    @Test fun splitIsIdempotent() {
        val once = StoredIdentity("a", "Alex", "alex@masto.top", "<p>Alex</p>").withSplitSignature()
        assertEquals(once, once.withSplitSignature())
    }

    @Test fun plainSignatureIsLeftAlone() {
        val plain = StoredIdentity("a", "Alex", "alex@masto.top", "Alex Rivera\nAcme")
        assertEquals(plain, plain.withSplitSignature())
        assertEquals("", plain.withSplitSignature().signatureHtml)
    }

    @Test fun plainSignatureWithAnAddressInChevronsIsLeftAlone() {
        val typed = "Jean Dupont\n<jean@example.org>\nAssociation X"
        val split = StoredIdentity("a", "Jean", "jean@example.org", typed).withSplitSignature()
        assertEquals(typed, split.signature)
        assertEquals("", split.signatureHtml)
    }

    @Test fun plainSignatureWithAUrlInChevronsIsLeftAlone() {
        val typed = "Site <https://jean.fr>"
        val split = StoredIdentity("a", "Jean", "jean@example.org", typed).withSplitSignature()
        assertEquals(typed, split.signature)
        assertEquals("", split.signatureHtml)
    }

    @Test fun legacyHtmlWithOnlyVoidTagsIsStillSplit() {
        for (raw in listOf(
            "Alex<br>Acme",
            "<img src=\"logo.png\">",
            "Alex<BR>Acme",
            "Alex<o:p></o:p>",
            "<img\nsrc=\"logo.png\">",
        )) {
            val split = StoredIdentity("a", "Alex", "alex@masto.top", raw).withSplitSignature()
            assertEquals(raw, split.signatureHtml)
        }
        assertEquals(
            listOf("Alex\nAcme", "[image]", "Alex\nAcme", "Alex", "[image]"),
            listOf(
                "Alex<br>Acme",
                "<img src=\"logo.png\">",
                "Alex<BR>Acme",
                "Alex<o:p></o:p>",
                "<img\nsrc=\"logo.png\">",
            ).map { StoredIdentity("a", "Alex", "alex@masto.top", it).withSplitSignature().signature },
        )
    }

    private fun split(raw: String) = StoredIdentity("a", "Alex", "alex@masto.top", raw).withSplitSignature()

    @Test fun anImageOnlySignatureKeepsATextAndItsHtml() {
        val raw = "<img src=\"https://example.org/sig.png\">"
        val split = split(raw)
        assertEquals("[image]", split.signature)
        assertEquals(raw, split.signatureHtml)
    }

    @Test fun anImageInsideATableKeepsATextToo() {
        val raw = "<table><tr><td><IMG SRC=\"https://example.org/sig.png\" width=\"200\"></td></tr></table>"
        val split = split(raw)
        assertEquals("[image]", split.signature)
        assertEquals(raw, split.signatureHtml)
    }

    @Test fun theAltOfTheImageIsTheText() {
        assertEquals("Acme logo", split("<img src=\"https://x/y.png\" alt=\"Acme logo\">").signature)
        assertEquals("Acme & co", split("<img alt='Acme &amp; co' src=x.png>").signature)
    }

    @Test fun onlyTheAltsThatSaySomethingAreKept() {
        assertEquals(
            "Acme",
            split("<p><img src=\"https://x/a.png\"><img src=\"https://x/b.png\" alt=\"Acme\"></p>").signature,
        )
        assertEquals("[image]", split("<img src=\"a.png\" alt=\"\"><img src=\"b.png\" alt=\"  \">").signature)
        assertEquals("Acme Rivera", split("<img alt=\"Acme\" src=a.png><img src=b.png alt=Rivera>").signature)
    }

    @Test fun anHtmlWithNeitherTextNorImageStaysEmpty() {
        assertEquals("", split("<p></p>").signature)
        assertEquals("", split("<html><body></body></html>").signature)
        assertEquals("", split("<p>  </p><br>").signature)
    }

    @Test fun aTextNextToTheImageWinsOverTheFallback() {
        assertEquals("Alex", split("<img src=\"a.png\" alt=\"logo\"><p>Alex</p>").signature)
    }

    @Test fun theReaderFlatteningIsNotGivenTheFallback() {
        assertEquals("", htmlToText("<img src=\"https://x/y.png\" alt=\"logo\">"))
    }

    @Test fun anHtmlOnlyIdentityGetsATextOnRead() {
        val img = "<img src=\"https://example.org/sig.png\">"
        val read = StoredIdentity("a", "Alex", "alex@masto.top", "", img).withSplitSignature()
        assertEquals("[image]", read.signature)
        assertEquals(img, read.signatureHtml)
        assertEquals(read, read.withSplitSignature())
    }

    @Test fun anHtmlOnlyIdentityGetsItsAltOnRead() {
        val img = "<img src=\"https://example.org/sig.png\" alt=\"Acme logo\">"
        val read = StoredIdentity("a", "Alex", "alex@masto.top", " ", img).withSplitSignature()
        assertEquals("Acme logo", read.signature)
        assertEquals(img, read.signatureHtml)
    }

    @Test fun aTypedTextBesideTheHtmlIsNeverReplaced() {
        val kept = StoredIdentity("a", "Alex", "alex@masto.top", "Alex", "<img src=\"x.png\" alt=\"logo\">")
        assertEquals(kept, kept.withSplitSignature())
    }

    @Test fun noTextAndNoHtmlStaysEmpty() {
        val empty = StoredIdentity("a", "Alex", "alex@masto.top", "", "")
        assertEquals(empty, empty.withSplitSignature())
        assertEquals("", StoredIdentity("a", "Alex", "alex@masto.top", "", "<p></p>").withSplitSignature().signature)
    }

    @Test fun anAlreadySplitIdentityIsNeverReflattened() {
        val split = StoredIdentity("a", "Alex", "alex@masto.top", "Alex", "<b>Alex</b>")
        assertEquals(split, split.withSplitSignature())
    }

    // --- Dedup key ------------------------------------------------------------------------------

    @Test fun identitiesDifferingOnlyByHtmlSignatureAreNotMerged() {
        val manual = listOf(
            StoredIdentity("1", "Alex", "alex@masto.top", "Alex", "<b>Alex</b>"),
            StoredIdentity("2", "Alex", "alex@masto.top", "Alex", "<i>Alex</i>"),
        )
        assertEquals(2, StoredAccount.normalizeManualIdentities(manual, emptyList()).size)
    }

    @Test fun frozenServerCopyIsStillDropped() {
        val server = listOf(StoredIdentity("s", "Alex", "alex@masto.top", "Alex", "<b>Alex</b>"))
        val manual = listOf(StoredIdentity("m", "Alex", "alex@masto.top", "Alex", "<b>Alex</b>"))
        assertTrue(StoredAccount.normalizeManualIdentities(manual, server).isEmpty())
    }

    @Test fun defaultIdentityFromALegacyHtmlAccountSignatureIsSplit() {
        val account = StoredAccount(
            id = "acc", server = "https://s", username = "alex@masto.top",
            signature = "<p>Alex Rivera</p>",
        )
        val identity = account.resolvedIdentities().single()
        assertEquals("Alex Rivera", identity.signature)
        assertEquals("<p>Alex Rivera</p>", identity.signatureHtml)
    }
}
