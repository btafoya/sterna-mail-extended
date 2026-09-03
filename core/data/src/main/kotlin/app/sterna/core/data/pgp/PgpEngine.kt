package app.sterna.core.data.pgp

import android.app.PendingIntent
import android.content.Intent

/**
 * Per-message crypto mode chosen in the composer.
 *
 * [ENCRYPT] encrypts AND signs. [ENCRYPT_UNSIGNED] encrypts and does NOT sign: the body is still
 * readable only by the recipients, but it carries no proof of who wrote it — and nothing else is
 * hidden, the subject travels in the clear and the SMTP envelope names the sender either way.
 * Everything else the two share, which is what [encrypts] is for.
 */
enum class PgpMode { OFF, SIGN, ENCRYPT, ENCRYPT_UNSIGNED }

/**
 * "Does this mode turn the body into ciphertext?" — the ONE way to ask, because every consequence
 * of encrypting hangs off this single question and they must never disagree:
 *  - no draft may be kept (a draft is uploaded to the server as it stands — `draftSaveAllowed`);
 *  - the outbox row's body is blanked at rest (`outboxPayload`, redactBody);
 *  - the recipients' public keys are looked up, and the composer's missing-key warnings with them
 *    (`ComposeViewModel.updateRecipientKeys`, `keysReady`, `missingKeyFor`).
 *
 * ⛔ A fifth encrypting mode MUST come through here instead of being compared by name at the call
 * sites. `== PgpMode.ENCRYPT` was written at six of them; a mode added beside it answered "not
 * encrypted" at every single one, and the failures are silent ones: the plaintext goes up to the
 * server as a draft, and stays in the outbox row at rest.
 *
 * ⚠ It is deliberately NOT the same question as `!= PgpMode.OFF` ("any PGP at all"), which is what
 * gates scheduling and local attachment staging. Do not swap one for the other.
 */
val PgpMode.encrypts: Boolean get() = this == PgpMode.ENCRYPT || this == PgpMode.ENCRYPT_UNSIGNED

/** Outcome of verifying an OpenPGP signature, mapped for UI badge semantics. */
enum class PgpSignatureState {
    /** No signature present. */
    NONE,

    /** Valid signature from a key the user has confirmed (green). */
    VALID_CONFIRMED,

    /** Valid signature from a known but unconfirmed key (yellow). */
    VALID_UNCONFIRMED,

    /** Signature present but the public key is not available (grey). */
    KEY_MISSING,

    /** Signature did not verify — content altered or corrupt (red). */
    INVALID,

    /** Valid signature but the key is revoked (red). */
    KEY_REVOKED,

    /** Valid signature but the key is expired (red). */
    KEY_EXPIRED,

    /** Valid signature from a cryptographically weak key (red). */
    INSECURE,

    /** Valid signature but the key's user id does not match the sender (yellow). */
    SENDER_MISMATCH,
}

/**
 * Result of a [PgpEngine] operation. OpenPGP providers routinely need the user
 * (unlock passphrase, choose a key): that surfaces as [UserInteractionRequired],
 * whose [PendingIntent] must be launched with StartIntentSenderForResult; the
 * activity-result data Intent is then passed back as `interactionResult` to retry
 * the same call.
 */
sealed interface PgpResult<out T> {
    data class Success<T>(val value: T) : PgpResult<T>

    data class UserInteractionRequired(val pendingIntent: PendingIntent) : PgpResult<Nothing>

    data class Error(val message: String) : PgpResult<Nothing>

    /** No OpenPGP provider installed (or the service cannot be bound). */
    object NotAvailable : PgpResult<Nothing>
}

/** Detached-signature output: the armored signature + the micalg for the PGP/MIME wrapper. */
class PgpSignature(
    val armor: ByteArray,
    /** RFC 3156 micalg parameter, e.g. "pgp-sha256". */
    val micalg: String,
)

/** Decrypt/verify output: the plaintext plus what the signature (if any) told us. */
class PgpDecrypted(
    val plaintext: ByteArray,
    val signature: PgpSignatureState,
    val signatureKeyId: Long,
    val signatureUserId: String?,
    val wasEncrypted: Boolean,
    /** Pending intent to show/import the signing key in the provider, when offered. */
    val signaturePendingIntent: PendingIntent? = null,
)

/**
 * Message-level OpenPGP operations. Implemented in the app layer (the reference
 * implementation binds the OpenKeychain service); kept as an interface here so
 * core modules stay free of any crypto/provider dependency. All methods accept
 * the activity-result Intent from a prior [PgpResult.UserInteractionRequired]
 * round-trip to retry the operation.
 */
interface PgpEngine {
    /** True when an OpenPGP provider is installed and its service can be bound. */
    suspend fun isAvailable(): Boolean

    /** Let the user pick (or confirm) their signing key; returns the key id. */
    suspend fun getSignKeyId(
        userIdHint: String? = null,
        interactionResult: Intent? = null,
    ): PgpResult<Long>

    /**
     * This account's OWN public key, as the provider hands it back for [keyId]: the MINIMIZED
     * transferable public key (signatures stripped, and user ids narrowed to [ownAddress]), in
     * BINARY — no ASCII armor is requested.
     *
     * ⛔ [ownAddress] is not decoration and not a hint: it is the account's own address
     * (`StoredAccount.username`), and it is what keeps the export down to the ONE identity this
     * account may announce. A keyring holding several user ids — a private address, a former
     * employer, a pseudonym — would otherwise put all of them in the `Autocrypt:` header of every
     * message to every correspondent.
     *
     * ⛔ Raw bytes, like every other payload on this interface. What gets STORED is base64, and the
     * one encoder is `pgpPublicKeyCacheValue` in the account package: keeping it out of the
     * provider binding means both call sites that persist a key encode it the same way, and a
     * caller that wants the bytes (a header, a file) is not made to decode them back.
     *
     * ⚠ Expect [PgpResult.UserInteractionRequired] here as anywhere else — the provider may want to
     * be unlocked, or may not hold the key. That is precisely why the answer is cached on the
     * account: a caller with no screen cannot take that branch.
     */
    suspend fun getPublicKey(
        keyId: Long,
        ownAddress: String,
        interactionResult: Intent? = null,
    ): PgpResult<ByteArray>

    /**
     * File a CORRESPONDENT's public key in the provider under [peerId] (their address), as read
     * from the `Autocrypt` header of a message they sent — OpenPGP's Autocrypt Level 1 §5.4 peer
     * update. [effectiveDateMillis] is that message's own date: the provider keeps the newest, so a
     * date is not decoration, it is what stops an old message overwriting a newer key.
     *
     * The key lands in the public keyring, which is the whole point: [findKeys] then finds it with
     * no other call, and the composer stops saying the recipient has no key.
     *
     * ⛔ No `interactionResult`, unlike every other method here, and none can be added usefully:
     * this runs while a message is being OPENED, from a repository that has no screen and no way to
     * launch a [PgpResult.UserInteractionRequired] intent (the only launcher in the app is a
     * `@Composable`). Every answer that is not [PgpResult.Success] is dropped in silence by the
     * caller.
     *
     * ⛔ Never mutual. Autocrypt's `prefer-encrypt` is read off the header and thrown away: whether
     * this app starts encrypting by itself is a separate decision, and it is not this one.
     */
    suspend fun updateAutocryptPeer(
        peerId: String,
        keyData: ByteArray,
        effectiveDateMillis: Long,
    ): PgpResult<Unit>

    /**
     * Resolve recipient addresses to public-key ids. Fails (Error) when any
     * address has no usable key — callers surface which ones via [findKeysEach].
     */
    suspend fun findKeys(
        emails: List<String>,
        interactionResult: Intent? = null,
    ): PgpResult<LongArray>

    /** Per-address key availability (true = at least one usable key). */
    suspend fun findKeysEach(emails: List<String>): Map<String, Boolean>

    /** Detached-sign [data]; returns the ASCII-armored signature + micalg (RFC 3156). */
    suspend fun detachedSign(
        data: ByteArray,
        signKeyId: Long,
        interactionResult: Intent? = null,
    ): PgpResult<PgpSignature>

    /**
     * Encrypt [data] to [recipientKeyIds] (callers include the sender's own key
     * for encrypt-to-self), signing with [signKeyId] when non-null. Returns
     * ASCII-armored ciphertext.
     */
    suspend fun signAndEncrypt(
        data: ByteArray,
        signKeyId: Long?,
        recipientKeyIds: LongArray,
        interactionResult: Intent? = null,
    ): PgpResult<ByteArray>

    /**
     * Decrypt and/or verify [data]. For multipart/signed pass the exact signed
     * bytes plus [detachedSignature]; for encrypted messages pass the armored
     * ciphertext. [senderAddress] lets the provider flag sender/uid mismatches.
     */
    suspend fun decryptVerify(
        data: ByteArray,
        senderAddress: String?,
        detachedSignature: ByteArray? = null,
        interactionResult: Intent? = null,
    ): PgpResult<PgpDecrypted>
}
