package app.sterna.core.imap

import java.util.Base64

/**
 * S/MIME (RFC 8551) wrappers. Pure MIME assembly: the signature octets come from the app's S/MIME engine. The
 * entity handed in is signed as [PgpMime.signablePayload] prepares it, and it is reproduced here unchanged, CRLF
 * only: the octets a reader verifies are the octets that were signed.
 */
object SmimeMime {

    /**
     * [innerEntity], the payload exactly as signed, and [signatureDer], the detached `signed-data` over it, as an
     * RFC 8551 section 3.5.3 `multipart/signed`. The signature travels as `smime.p7s`, base64 in 76 columns.
     */
    fun wrapSigned(innerEntity: String, signatureDer: ByteArray, boundary: String): String = buildString {
        val safe = boundary.filterNot { it == '\r' || it == '\n' || it == '"' || it.code < 32 }
        append("Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\";\r\n")
        append(" micalg=sha-256; boundary=\"$safe\"\r\n")
        append("\r\n")
        append("This is a cryptographically signed message in MIME format.\r\n")
        append("\r\n")
        append("--$safe\r\n")
        append(innerEntity)
        append("\r\n")
        append("--$safe\r\n")
        append("Content-Type: application/pkcs7-signature; name=\"smime.p7s\"\r\n")
        append("Content-Transfer-Encoding: base64\r\n")
        append("Content-Disposition: attachment; filename=\"smime.p7s\"\r\n")
        append("Content-Description: S/MIME Cryptographic Signature\r\n")
        append("\r\n")
        append(Base64.getMimeEncoder().encodeToString(signatureDer))
        append("\r\n")
        append("--$safe--\r\n")
    }

    /**
     * [envelopedDer], an `enveloped-data`, as the RFC 8551 section 3.3 `application/pkcs7-mime` entity that replaces
     * the body: `smime.p7m`, base64 in 76 columns. Nothing of the entity it carries shows outside it.
     */
    fun wrapEnveloped(envelopedDer: ByteArray): String = buildString {
        append("Content-Type: application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"\r\n")
        append("Content-Disposition: attachment; filename=\"smime.p7m\"\r\n")
        append("Content-Transfer-Encoding: base64\r\n")
        append("Content-Description: S/MIME Encrypted Message\r\n")
        append("\r\n")
        append(Base64.getMimeEncoder().encodeToString(envelopedDer))
        append("\r\n")
    }
}
