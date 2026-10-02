package app.sterna.core.data.mail

import app.sterna.core.data.pgp.PgpDecrypted
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.imap.CryptoEnvelope
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.MimeParser

/** RFC 3156 and RFC 8551: signatures are computed over the entity with CRLF line endings. */
internal fun canonicalizeCrlf(entity: String): String =
    entity.replace("\r\n", "\n").replace("\n", "\r\n")

/**
 * An S/MIME signature, detached or opaque, checked by [engine]. [wasEncrypted] is true when the signed entity
 * was itself the content of an envelope: the reader then shows the message as encrypted, and signed.
 */
internal fun smimeVerified(
    engine: SmimeEngine,
    envelope: CryptoEnvelope,
    sender: String?,
    wasEncrypted: Boolean,
): PgpDecrypted {
    val verified = when (envelope.kind) {
        CryptoKind.SMIME_OPAQUE_SIGNED -> engine.verifyOpaque(envelope.signedDataDer!!, sender)
        else -> engine.verifyDetached(
            canonicalizeCrlf(envelope.signedEntityRaw!!).toByteArray(Charsets.ISO_8859_1),
            envelope.signatureDer!!,
            sender,
        )
    }
    return PgpDecrypted(
        plaintext = verified.content ?: ByteArray(0),
        signature = verified.signature,
        signatureKeyId = null,
        signatureUserId = verified.signer,
        wasEncrypted = wasEncrypted,
    )
}

/**
 * Open the S/MIME `enveloped-data` [envelopedData] with the key the account stores under [alias]. No alias, or
 * one [keys] cannot use now, asks for a certificate, and the alias stays stored (see [SmimeKeySource]). A
 * certificate the envelope does not name gets its own answer before any decryption is tried: "wrong
 * certificate" is something the user can act on. Every other failure is [PgpResult.DecryptFailed], kept apart
 * from [PgpResult.Error], which also stands for a source that could not be fetched: the reader says nothing about
 * that one, and offers to retry this one. Its message is for the log, never shown. Never throws.
 */
internal fun openSmimeEnvelope(
    envelopedData: ByteArray,
    alias: String,
    keys: SmimeKeySource?,
    engine: SmimeEngine,
    sender: String?,
): PgpResult<PgpDecrypted> = try {
    if (alias.isEmpty()) {
        PgpResult.CertificateToChoose
    } else {
        val key = (keys ?: error("No S/MIME key store")).keyFor(alias)
        if (key == null) {
            PgpResult.CertificateToChoose
        } else {
            val certificate = key.chain.firstOrNull() ?: error("The S/MIME key has no certificate")
            if (!engine.namesRecipient(envelopedData, certificate)) {
                PgpResult.NotEncryptedForCertificate
            } else {
                val content = engine.decrypt(envelopedData, key.privateKey, certificate)
                PgpResult.Success(clearOfEnvelope(content, engine, sender))
            }
        }
    }
} catch (failure: Exception) {
    PgpResult.DecryptFailed(failure.message ?: failure.javaClass.simpleName)
}

/**
 * What a decrypted envelope shows, as [PgpDecrypted.plaintext]: the MIME entity it carries, its octets kept, as
 * the repository reads it one char per octet. When that entity is an S/MIME signature, detached or opaque, the
 * signature is checked on the spot, with no fetch, and the plaintext is what was signed. One level only: a signed
 * entity carrying another envelope is shown as it is.
 */
internal fun clearOfEnvelope(content: ByteArray, engine: SmimeEngine, sender: String?): PgpDecrypted {
    val inner = MimeParser.detectCrypto(content.toString(Charsets.ISO_8859_1))
    return when (inner?.kind) {
        CryptoKind.SMIME_SIGNED ->
            smimeVerified(engine, inner, sender, wasEncrypted = true).withPlaintext(inner.signedEntityRaw!!.toByteArray(Charsets.ISO_8859_1))
        CryptoKind.SMIME_OPAQUE_SIGNED -> smimeVerified(engine, inner, sender, wasEncrypted = true)
        else -> PgpDecrypted(
            plaintext = content,
            signature = PgpSignatureState.NONE,
            signatureKeyId = null,
            signatureUserId = null,
            wasEncrypted = true,
        )
    }
}

private fun PgpDecrypted.withPlaintext(shown: ByteArray) = PgpDecrypted(
    plaintext = shown,
    signature = signature,
    signatureKeyId = signatureKeyId,
    signatureUserId = signatureUserId,
    wasEncrypted = wasEncrypted,
)
