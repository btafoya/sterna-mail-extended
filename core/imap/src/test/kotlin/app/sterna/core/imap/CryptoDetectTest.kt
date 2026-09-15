package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoDetectTest {

    private val armor = "-----BEGIN PGP MESSAGE-----\r\n\r\nhQEMA0ci\r\n-----END PGP MESSAGE-----"

    private fun encryptedMessage(protocolParam: String = "; protocol=\"application/pgp-encrypted\"") =
        buildString {
            append("From: a@b.c\r\n")
            append("Content-Type: multipart/encrypted$protocolParam; boundary=\"ENC\"\r\n\r\n")
            append("--ENC\r\n")
            append("Content-Type: application/pgp-encrypted\r\n\r\n")
            append("Version: 1\r\n")
            append("--ENC\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
            append("$armor\r\n")
            append("--ENC--\r\n")
        }

    @Test
    fun detectsMultipartEncrypted() {
        val env = MimeParser.detectCrypto(encryptedMessage())
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_ENCRYPTED, env!!.kind)
        assertTrue(env.encryptedArmor!!.startsWith("-----BEGIN PGP MESSAGE-----"))
        assertTrue(env.encryptedArmor!!.trimEnd().endsWith("-----END PGP MESSAGE-----"))
    }

    @Test
    fun detectsMultipartEncryptedWithoutProtocolParam() {
        // Lax producer: no protocol= param, but the version part gives it away.
        val env = MimeParser.detectCrypto(encryptedMessage(protocolParam = ""))
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_ENCRYPTED, env!!.kind)
    }

    @Test
    fun detectsMultipartSignedAndKeepsSignedBytesVerbatim() {
        val signedEntity = "Content-Type: text/plain; charset=utf-8\r\n\r\nHello  signed\r\nworld"
        val sig = "-----BEGIN PGP SIGNATURE-----\r\n\r\nabc\r\n-----END PGP SIGNATURE-----"
        val raw = buildString {
            append("From: a@b.c\r\n")
            append("Content-Type: multipart/signed; micalg=pgp-sha256;\r\n")
            append(" protocol=\"application/pgp-signature\"; boundary=\"SIG\"\r\n\r\n")
            append("--SIG\r\n")
            append(signedEntity)
            append("\r\n--SIG\r\n")
            append("Content-Type: application/pgp-signature\r\n\r\n")
            append("$sig\r\n")
            append("--SIG--\r\n")
        }
        val env = MimeParser.detectCrypto(raw)
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_SIGNED, env!!.kind)
        // Byte-exact: headers included, boundary CRLFs excluded, spacing intact.
        assertEquals(signedEntity, env.signedEntityRaw)
        assertTrue(env.signatureArmor!!.contains("BEGIN PGP SIGNATURE"))
    }

    private fun signedUnderSig(
        entity: String,
        preamble: String = "",
        openLine: String = "--SIG",
        closeLine: String = "--SIG",
    ) = buildString {
        append("From: a@b.c\r\n")
        append("Content-Type: multipart/signed; micalg=pgp-sha256;\r\n")
        append(" protocol=\"application/pgp-signature\"; boundary=\"SIG\"\r\n\r\n")
        append(preamble)
        append("$openLine\r\n")
        append(entity)
        append("\r\n$closeLine\r\n")
        append("Content-Type: application/pgp-signature\r\n\r\n")
        append("-----BEGIN PGP SIGNATURE-----\r\n\r\nabc\r\n-----END PGP SIGNATURE-----\r\n")
        append("--SIG--\r\n")
    }

    private fun alternativeEntity(innerBoundary: String) = buildString {
        append("Content-Type: multipart/alternative; boundary=\"$innerBoundary\"\r\n\r\n")
        append("--$innerBoundary\r\n")
        append("Content-Type: text/plain; charset=utf-8\r\n\r\n")
        append("Hello\r\n")
        append("--$innerBoundary\r\n")
        append("Content-Type: text/html; charset=utf-8\r\n\r\n")
        append("<p>Hello</p>\r\n")
        append("--$innerBoundary--")
    }

    @Test
    fun innerBoundaryExtendingTheOuterOneDoesNotCloseTheSignedEntity() {
        val entity = alternativeEntity("SIG-alt")
        val env = MimeParser.detectCrypto(signedUnderSig(entity))
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_SIGNED, env!!.kind)
        assertEquals(entity, env.signedEntityRaw)
    }

    @Test
    fun innerBoundaryWithDoubleDashAfterTheOuterNameDoesNotCloseTheSignedEntity() {
        val entity = alternativeEntity("SIG--x")
        val env = MimeParser.detectCrypto(signedUnderSig(entity))
        assertNotNull(env)
        assertEquals(entity, env!!.signedEntityRaw)
    }

    @Test
    fun innerBoundaryWithAnotherCharacterAfterTheOuterNameDoesNotCloseTheSignedEntity() {
        val entity = alternativeEntity("SIG2")
        val env = MimeParser.detectCrypto(signedUnderSig(entity))
        assertNotNull(env)
        assertEquals(entity, env!!.signedEntityRaw)
    }

    @Test
    fun signedMessageWrittenWithBareLineFeedsKeepsTheExactEntity() {
        val entity = "Content-Type: text/plain; charset=utf-8\n\nHello  signed\nworld"
        val raw = buildString {
            append("From: a@b.c\n")
            append("Content-Type: multipart/signed; protocol=\"application/pgp-signature\"; boundary=\"SIG\"\n\n")
            append("--SIG\n")
            append(entity)
            append("\n--SIG\n")
            append("Content-Type: application/pgp-signature\n\n")
            append("-----BEGIN PGP SIGNATURE-----\n\nabc\n-----END PGP SIGNATURE-----\n")
            append("--SIG--\n")
        }
        val env = MimeParser.detectCrypto(raw)
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_SIGNED, env!!.kind)
        assertEquals(entity, env.signedEntityRaw)
    }

    @Test
    fun linearWhitespaceAfterTheOuterBoundaryStillDelimitsTheSignedEntity() {
        val entity = "Content-Type: text/plain; charset=utf-8\r\n\r\nHello  signed\r\nworld"
        val raw = signedUnderSig(entity, openLine = "--SIG  ", closeLine = "--SIG\t")
        val env = MimeParser.detectCrypto(raw)
        assertNotNull(env)
        assertEquals(entity, env!!.signedEntityRaw)
    }

    @Test
    fun preambleLinesStartingLikeTheBoundaryAreNotTheOpening() {
        val entity = "Content-Type: text/plain; charset=utf-8\r\n\r\nsee --SIG here\r\nbye"
        val preamble = "This is an OpenPGP/MIME signed message.\r\n--SIG-alt\r\nquoted --SIG\r\n\r\n"
        val env = MimeParser.detectCrypto(signedUnderSig(entity, preamble = preamble))
        assertNotNull(env)
        assertEquals(entity, env!!.signedEntityRaw)
    }

    @Test
    fun detectsInlinePgpInPlainText() {
        val raw = "Content-Type: text/plain; charset=utf-8\r\n\r\nsee below\r\n$armor\r\ncheers"
        val env = MimeParser.detectCrypto(raw)
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_INLINE, env!!.kind)
        assertTrue(env.encryptedArmor!!.startsWith("-----BEGIN PGP MESSAGE-----"))
        assertTrue(env.encryptedArmor!!.endsWith("-----END PGP MESSAGE-----"))
    }

    @Test
    fun detectsInlineCleartextSigned() {
        val block = "-----BEGIN PGP SIGNED MESSAGE-----\r\nHash: SHA256\r\n\r\nhi\r\n" +
            "-----BEGIN PGP SIGNATURE-----\r\n\r\nxyz\r\n-----END PGP SIGNATURE-----"
        val raw = "Content-Type: text/plain\r\n\r\n$block\r\n"
        val env = MimeParser.detectCrypto(raw)
        assertNotNull(env)
        assertEquals(CryptoKind.PGP_INLINE, env!!.kind)
        assertTrue(env.encryptedArmor!!.endsWith("-----END PGP SIGNATURE-----"))
    }

    @Test
    fun ordinaryMailIsNotCrypto() {
        assertNull(MimeParser.detectCrypto("Content-Type: text/plain\r\n\r\nJust a normal mail."))
        val mixed = buildString {
            append("Content-Type: multipart/mixed; boundary=\"B\"\r\n\r\n")
            append("--B\r\n")
            append("Content-Type: text/plain\r\n\r\nhello\r\n")
            append("--B--\r\n")
        }
        assertNull(MimeParser.detectCrypto(mixed))
    }

    @Test
    fun signedWithBase64SignaturePartDecodes() {
        val sig = "-----BEGIN PGP SIGNATURE-----\r\n\r\nabc\r\n-----END PGP SIGNATURE-----"
        val sigB64 = java.util.Base64.getMimeEncoder().encodeToString(sig.toByteArray())
        val raw = buildString {
            append("Content-Type: multipart/signed; protocol=\"application/pgp-signature\"; boundary=\"S\"\r\n\r\n")
            append("--S\r\n")
            append("Content-Type: text/plain\r\n\r\nbody\r\n")
            append("--S\r\n")
            append("Content-Type: application/pgp-signature\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append("$sigB64\r\n")
            append("--S--\r\n")
        }
        val env = MimeParser.detectCrypto(raw)
        assertNotNull(env)
        assertTrue(env!!.signatureArmor!!.contains("BEGIN PGP SIGNATURE"))
    }
}
