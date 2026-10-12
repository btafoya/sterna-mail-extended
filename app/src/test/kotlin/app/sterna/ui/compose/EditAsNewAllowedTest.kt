package app.sterna.ui.compose

import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.imap.CryptoKind
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import app.sterna.ui.message.CryptoUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mayEditAsNew] executed (#254): which messages may be reopened as new ones. Two witnesses, the
 * structure of the message and what the reader knows of its encryption, and either one refuses.
 *
 * Not covered: `CryptoUiState.NeedsInteraction`, which holds a `PendingIntent` the JVM's android.jar
 * stub cannot build. The `when` that classifies it has no `else`, so it cannot go unclassified.
 */
class EditAsNewAllowedTest {

    // -- the structure alone, which is all `prepare` has ----------------------------------------

    @Test fun `an OpenPGP encrypted message is refused on its structure`() {
        assertFalse(mayEditAsNew(PGP_MIME_ENCRYPTED, reader = null))
        assertFalse(mayEditAsNew(PGP_MIME_ENCRYPTED, CLEAR_READER))
    }

    @Test fun `an inline armoured encrypted message is refused on its structure`() {
        assertFalse(mayEditAsNew(PGP_INLINE_ENCRYPTED, reader = null))
        assertFalse(mayEditAsNew(PGP_INLINE_ENCRYPTED, CLEAR_READER))
    }

    /** An envelope and an opaque signature look the same on an `Email`, so both are refused. */
    @Test fun `an opaque S-MIME message is refused on its structure, under either media type`() {
        assertFalse("smime.p7m under application/pkcs7-mime", mayEditAsNew(SMIME_OPAQUE, reader = null))
        assertFalse(mayEditAsNew(SMIME_OPAQUE, CLEAR_READER))
        assertFalse("an unnamed application/x-pkcs7-mime part", mayEditAsNew(SMIME_OPAQUE_UNNAMED, reader = null))
    }

    @Test fun `a message that is only signed is accepted on its structure`() {
        assertTrue("OpenPGP multipart/signed", mayEditAsNew(PGP_MIME_SIGNED, reader = null))
        assertTrue("S/MIME multipart/signed", mayEditAsNew(SMIME_DETACHED_SIGNED, reader = null))
        assertTrue("inline signed armour", mayEditAsNew(PGP_INLINE_SIGNED, reader = null))
    }

    @Test fun `ordinary mail is accepted on its structure, a p7m file attached to it included`() {
        assertTrue(mayEditAsNew(PLAIN, reader = null))
        assertTrue(
            "a .p7m attached to a message that has its own text is a file, not the message's envelope",
            mayEditAsNew(PLAIN_WITH_P7M_FILE, reader = null),
        )
    }

    // -- what the reader knows, which only the menu has -------------------------------------------

    /**
     * The case the structure cannot see: once decrypted, the reader holds the clear message, and that
     * `Email` has nothing encrypted about it.
     */
    @Test fun `a clear body the reader decrypted is refused`() {
        assertFalse(
            "a decrypted message became eligible again: the entry would follow what the reader " +
                "happens to have decrypted, and open a composer on the ciphertext the server holds",
            mayEditAsNew(PLAIN, shown(CryptoUiState.Decrypted(decrypted(wasEncrypted = true)))),
        )
        assertFalse(mayEditAsNew(PLAIN, shown(CryptoUiState.Decrypted(decrypted(wasEncrypted = true, smime = true)))))
    }

    @Test fun `a clear body whose signature the reader checked is accepted`() {
        assertTrue(mayEditAsNew(PGP_MIME_SIGNED, shown(CryptoUiState.Decrypted(decrypted(wasEncrypted = false)))))
        assertTrue(mayEditAsNew(SMIME_DETACHED_SIGNED, shown(CryptoUiState.Decrypted(decrypted(wasEncrypted = false, smime = true)))))
        assertTrue(mayEditAsNew(PGP_INLINE_SIGNED, shown(CryptoUiState.Decrypted(decrypted(wasEncrypted = false)))))
    }

    /**
     * The states that carry the kind the message was locked as, whatever the message looks like: an
     * encrypted or an opaque kind refuses, and so do the two cards of an S/MIME envelope that did not open.
     */
    @Test fun `a state that names an encrypted or an opaque kind refuses`() {
        val refusing = listOf(
            CryptoUiState.Locked(CryptoKind.PGP_ENCRYPTED),
            CryptoUiState.Locked(CryptoKind.PGP_ENCRYPTED, decrypting = true),
            CryptoUiState.Locked(CryptoKind.SMIME_ENCRYPTED),
            CryptoUiState.Locked(CryptoKind.SMIME_OPAQUE_SIGNED),
            CryptoUiState.Failed("no key", CryptoKind.PGP_ENCRYPTED),
            CryptoUiState.Failed(null, CryptoKind.PGP_ENCRYPTED),
            CryptoUiState.Failed("unreadable", CryptoKind.SMIME_ENCRYPTED),
            CryptoUiState.SmimeNotForCertificate,
            CryptoUiState.SmimeFailed,
        )
        refusing.forEach { state -> assertFalse("$state offered Edit as new", mayEditAsNew(PLAIN, shown(state))) }
    }

    /**
     * A signed message is locked until its signature is checked, and stays so for good with no OpenPGP
     * app installed or with the source out of reach. Nothing about it is encrypted in any of these.
     */
    @Test fun `a signed message keeps the entry while its signature is unchecked or could not be checked`() {
        val detached = listOf(
            CryptoUiState.Locked(CryptoKind.PGP_SIGNED),
            CryptoUiState.Locked(CryptoKind.PGP_SIGNED, decrypting = true),
            CryptoUiState.Failed(null, CryptoKind.PGP_SIGNED),
            CryptoUiState.Failed("the source could not be fetched", CryptoKind.PGP_SIGNED),
        )
        detached.forEach { state -> assertTrue("$state withheld Edit as new", mayEditAsNew(PGP_MIME_SIGNED, shown(state))) }
        val armoured = listOf(
            CryptoUiState.Locked(CryptoKind.PGP_INLINE),
            CryptoUiState.Failed(null, CryptoKind.PGP_INLINE),
            CryptoUiState.Failed("the source could not be fetched", CryptoKind.PGP_INLINE),
        )
        armoured.forEach { state -> assertTrue("$state withheld Edit as new", mayEditAsNew(PGP_INLINE_SIGNED, shown(state))) }
        assertTrue(mayEditAsNew(SMIME_DETACHED_SIGNED, shown(CryptoUiState.Locked(CryptoKind.SMIME_SIGNED))))
    }

    /** The inline kind is one for both armours, so under it the structure is what refuses. */
    @Test fun `an encrypted message is refused under those same states`() {
        listOf(
            CryptoUiState.Locked(CryptoKind.PGP_INLINE),
            CryptoUiState.Failed(null, CryptoKind.PGP_INLINE),
            CryptoUiState.Failed("the source could not be fetched", CryptoKind.PGP_INLINE),
        ).forEach { state -> assertFalse("$state offered Edit as new", mayEditAsNew(PGP_INLINE_ENCRYPTED, shown(state))) }
        listOf(
            CryptoUiState.Locked(CryptoKind.PGP_ENCRYPTED),
            CryptoUiState.Failed(null, CryptoKind.PGP_ENCRYPTED),
            CryptoUiState.Locked(CryptoKind.PGP_SIGNED),
        ).forEach { state -> assertFalse("$state offered Edit as new", mayEditAsNew(PGP_MIME_ENCRYPTED, shown(state))) }
        listOf(
            CryptoUiState.Locked(CryptoKind.SMIME_ENCRYPTED),
            CryptoUiState.SmimeNotForCertificate,
            CryptoUiState.SmimeFailed,
            CryptoUiState.Locked(CryptoKind.SMIME_SIGNED),
        ).forEach { state -> assertFalse("$state offered Edit as new", mayEditAsNew(SMIME_OPAQUE, shown(state))) }
    }

    @Test fun `an armour that does not open the body is still an armour`() {
        assertFalse(
            "an encrypted armour under a line of text was taken for ordinary mail",
            mayEditAsNew(PGP_INLINE_ENCRYPTED_AFTER_TEXT, reader = null),
        )
        assertFalse(mayEditAsNew(PGP_INLINE_ENCRYPTED_AFTER_TEXT, CLEAR_READER))
    }

    @Test fun `ordinary mail the reader has opened is accepted`() {
        assertTrue(mayEditAsNew(PLAIN, CLEAR_READER))
    }

    /**
     * The list row painted before the body arrives has neither parts nor text, so its structure says
     * nothing, and the reader's state is still the one ordinary mail keeps for good.
     */
    @Test fun `a list row with no body yet is refused, clear or not`() {
        assertFalse(mayEditAsNew(LIST_ROW, EditAsNewReader(bodyShown = false, crypto = CryptoUiState.None)))
        assertFalse(mayEditAsNew(PLAIN, EditAsNewReader(bodyShown = false, crypto = CryptoUiState.None)))
        assertFalse(
            mayEditAsNew(PGP_MIME_SIGNED, EditAsNewReader(bodyShown = false, CryptoUiState.Decrypted(decrypted(wasEncrypted = false)))),
        )
    }

    private companion object {
        val CLEAR_READER = EditAsNewReader(bodyShown = true, crypto = CryptoUiState.None)

        fun shown(crypto: CryptoUiState) = EditAsNewReader(bodyShown = true, crypto = crypto)

        fun decrypted(wasEncrypted: Boolean, smime: Boolean = false) = MessageCrypto.Decrypted(
            signature = PgpSignatureState.VALID_CONFIRMED,
            signatureUserId = "sender@example.com",
            signatureKeyId = if (smime) null else 42L,
            wasEncrypted = wasEncrypted,
            smime = smime,
        )

        fun withText(text: String, attachments: List<EmailBodyPart> = emptyList()) = Email(
            id = "m-1",
            subject = "Subject",
            textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
            bodyValues = mapOf("t" to EmailBodyValue(text)),
            attachments = attachments,
        )

        val LIST_ROW = Email(id = "m-1", subject = "Subject")
        val PLAIN = withText("hello")
        val PLAIN_WITH_P7M_FILE = withText(
            "the signed invoice is attached",
            listOf(EmailBodyPart(partId = "2", type = "application/pkcs7-mime", name = "invoice.p7m")),
        )
        val PGP_MIME_ENCRYPTED = Email(
            id = "m-1",
            subject = "Subject",
            attachments = listOf(
                EmailBodyPart(partId = "1", type = "application/pgp-encrypted"),
                EmailBodyPart(partId = "2", type = "application/octet-stream", name = "encrypted.asc"),
            ),
        )
        val PGP_INLINE_ENCRYPTED = withText("-----BEGIN PGP MESSAGE-----\n\nhQEMA\n-----END PGP MESSAGE-----")
        val PGP_INLINE_ENCRYPTED_AFTER_TEXT =
            withText("Sent from my phone\n\n-----BEGIN PGP MESSAGE-----\n\nhQEMA\n-----END PGP MESSAGE-----")
        val PGP_INLINE_SIGNED = withText(
            "-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\nhello\n-----BEGIN PGP SIGNATURE-----\n\niQEz\n-----END PGP SIGNATURE-----",
        )
        val PGP_MIME_SIGNED = withText(
            "hello",
            listOf(EmailBodyPart(partId = "2", type = "application/pgp-signature", name = "signature.asc")),
        )
        val SMIME_DETACHED_SIGNED = withText(
            "hello",
            listOf(EmailBodyPart(partId = "2", type = "application/pkcs7-signature", name = "smime.p7s")),
        )
        val SMIME_OPAQUE = Email(
            id = "m-1",
            subject = "Subject",
            attachments = listOf(EmailBodyPart(partId = "1", type = "application/pkcs7-mime", name = "smime.p7m")),
        )
        val SMIME_OPAQUE_UNNAMED = Email(
            id = "m-1",
            subject = "Subject",
            attachments = listOf(EmailBodyPart(partId = "1", type = "application/x-pkcs7-mime")),
        )
    }
}
