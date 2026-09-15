package app.sterna.core.imap

import java.nio.charset.Charset

/**
 * An inline armoured block as the octets of the part that carried it: transfer-encoding undone,
 * charset not applied, one char per octet like [CryptoEnvelope.signedEntityRaw]; plus the charset
 * that part declared.
 */
data class InlineArmorOctets(val armor: String, val charset: Charset)

private const val CLEARSIGNED_BEGIN = "-----BEGIN PGP SIGNED MESSAGE-----"

/**
 * What the verifier receives for a [CryptoKind.PGP_INLINE] envelope. A signature covers the octets
 * the sender hashed, so they are sliced out of the part, never decoded then re-encoded: an `é` in
 * iso-8859-1 would otherwise reach the verifier as two UTF-8 bytes. When the markers were not found
 * in the octets (a charset that is not ASCII-compatible, UTF-16 or UTF-32), the decoded armour in
 * UTF-8.
 */
fun inlineVerifyBytes(envelope: CryptoEnvelope): ByteArray =
    envelope.inlineArmorOctets?.armor?.toByteArray(Charsets.ISO_8859_1)
        ?: envelope.encryptedArmor!!.toByteArray(Charsets.UTF_8)

/**
 * The text shown for the [plaintext] the engine returned for a [CryptoKind.PGP_INLINE] envelope. A
 * cleartext-signed block's text is taken from the octets the verifier was handed, so it is read in
 * the part's charset; this assumes the engine does not transcode it. A decrypted block is what the
 * sender encrypted, which the part's charset does not describe, so it is read as UTF-8, as it is
 * when no octets were kept.
 */
fun inlineDisplayText(envelope: CryptoEnvelope, plaintext: ByteArray): String {
    val octets = envelope.inlineArmorOctets
    val charset = when {
        octets == null -> Charsets.UTF_8
        octets.armor.startsWith(CLEARSIGNED_BEGIN) -> octets.charset
        else -> Charsets.UTF_8
    }
    return plaintext.toString(charset)
}
