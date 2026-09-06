package app.sterna.pgp

import org.openintents.openpgp.util.OpenPgpApi

/**
 * What Sterna asks an OpenPGP provider for, as a value: an action and the extras that go with it,
 * with no `Intent` and no Android in sight.
 *
 * The extras of `ACTION_GET_KEY` decide the OCTETS that leave this phone in every message's
 * `Autocrypt:` header, and assembled inline in [OpenKeychainPgpEngine] — a class no JVM test can
 * build — nothing anywhere executed that decision: turning `EXTRA_MINIMIZE` into
 * `EXTRA_REQUEST_ASCII_ARMOR` left the whole suite green while the announced key became a blob no
 * correspondent's client can import. Built here, the arguments are pinned by a test that RUNS them.
 *
 * [extras] values are limited to what `putAll` knows how to put on an Intent, and that limit is
 * itself pinned by a test: a fourth type added here without a branch there would throw at the call.
 */
internal data class PgpApiRequest(
    val action: String,
    val extras: Map<String, Any>,
)

/**
 * The peer update of `ACTION_UPDATE_AUTOCRYPT_PEER`, kept as plain values here and turned into the
 * provider's parcelable where the Intent is built — which is what lets [isMutual] be asserted by an
 * executing test instead of a source lint.
 */
internal data class PeerUpdateFacts(
    val keyData: ByteArray,
    val effectiveDateMillis: Long,
    val isMutual: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is PeerUpdateFacts &&
            keyData.contentEquals(other.keyData) &&
            effectiveDateMillis == other.effectiveDateMillis &&
            isMutual == other.isMutual

    override fun hashCode(): Int =
        (keyData.contentHashCode() * 31 + effectiveDateMillis.hashCode()) * 31 + isMutual.hashCode()
}

/**
 * Ask the provider for the public half of [keyId] — this account's OWN key, on its way into an
 * `Autocrypt:` header.
 *
 * `EXTRA_MINIMIZE` + `EXTRA_MINIMIZE_USER_ID`, both, exactly as K-9 asks. Minimize alone strips
 * third-party signatures and leaves EVERY user id on the keyring — a private address, a former
 * employer, a pseudonym — broadcast to every correspondent. [ownAddress] narrows the export to the
 * one identity this account is entitled to announce.
 *
 * No `EXTRA_REQUEST_ASCII_ARMOR`, and its ABSENCE is the request: absent means binary, which is
 * what the header needs. Asking for armor gets base64 of an armored block — bigger, valid looking,
 * and unreadable to the receiving client.
 *
 * A blank [ownAddress] puts no `EXTRA_MINIMIZE_USER_ID` at all rather than an empty one: what a
 * provider does with an empty filter is nobody's promise.
 */
internal fun publicKeyRequest(keyId: Long, ownAddress: String): PgpApiRequest = PgpApiRequest(
    action = OpenPgpApi.ACTION_GET_KEY,
    extras = buildMap {
        put(OpenPgpApi.EXTRA_KEY_ID, keyId)
        put(OpenPgpApi.EXTRA_MINIMIZE, true)
        if (ownAddress.isNotBlank()) put(OpenPgpApi.EXTRA_MINIMIZE_USER_ID, ownAddress)
    },
)

/**
 * File a CORRESPONDENT's key in the provider's public keyring under [peerId] (Autocrypt Level 1
 * §5.4), as read from a message they sent.
 *
 * `isMutual = false`, always: `prefer-encrypt=mutual` is a standing instruction to start
 * encrypting, and that is not a decision taken from an incoming message. [effectiveDateMillis]
 * is that message's own date, not now: the provider keeps the newest, so the date is what stops an
 * old message overwriting a newer key.
 */
internal fun autocryptPeerRequest(
    peerId: String,
    keyData: ByteArray,
    effectiveDateMillis: Long,
): PgpApiRequest = PgpApiRequest(
    action = OpenPgpApi.ACTION_UPDATE_AUTOCRYPT_PEER,
    extras = mapOf(
        OpenPgpApi.EXTRA_AUTOCRYPT_PEER_ID to peerId,
        OpenPgpApi.EXTRA_AUTOCRYPT_PEER_UPDATE to PeerUpdateFacts(
            keyData = keyData,
            effectiveDateMillis = effectiveDateMillis,
            isMutual = false,
        ),
    ),
)
