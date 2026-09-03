package app.sterna.pgp

import org.openintents.openpgp.util.OpenPgpApi

/**
 * What Sterna asks an OpenPGP provider for, as a value: an action and the extras that go with it,
 * with no `Intent` and no Android in sight.
 *
 * ⭐ **Why this exists at all.** The extras of `ACTION_GET_KEY` decide the OCTETS that leave this
 * phone in every message's `Autocrypt:` header, and until this file they were assembled inline in
 * [OpenKeychainPgpEngine] — a class no JVM test can build (it needs a `Context` and a live binder),
 * so nothing anywhere executed that decision. A counter-expertise showed what that costs: turning
 * `EXTRA_MINIMIZE` into `EXTRA_REQUEST_ASCII_ARMOR` left the whole suite green while the announced
 * key became an armored blob that no correspondent's client can import — a dead feature that looks
 * perfect on the wire. Built here, the arguments can be pinned by a test that RUNS them
 * ([app.sterna.pgp.PgpApiRequestTest]).
 *
 * [extras] values are limited to what `OpenKeychainPgpEngine.putAll` knows how to put on an Intent:
 * [Boolean], [Long], [String] and [PeerUpdateFacts]. That limit is itself pinned by a test — a
 * fourth type added here without a branch there would throw at the provider call.
 */
internal data class PgpApiRequest(
    val action: String,
    val extras: Map<String, Any>,
)

/**
 * The peer update of `ACTION_UPDATE_AUTOCRYPT_PEER`, kept as plain values here and turned into the
 * provider's `AutocryptPeerUpdate` parcelable where the Intent is built. Splitting it that way is
 * what lets [isMutual] be asserted by an executing test instead of a source lint.
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
 * `Autocrypt:` header ([app.sterna.core.data.account.autocryptHeaderValue]).
 *
 * ⭐ **`EXTRA_MINIMIZE` + `EXTRA_MINIMIZE_USER_ID`, both, exactly as K-9 asks.** Minimize alone
 * strips the third-party signatures, and leaves EVERY user id on the keyring: a private address, a
 * former employer, a pseudonym, all of them broadcast to every correspondent on every message.
 * [ownAddress] narrows the export to the one identity this account is entitled to announce — the
 * same address the header's `addr=` claims.
 *
 * ⛔ **No `EXTRA_REQUEST_ASCII_ARMOR`, and its ABSENCE is the request.** The extra is optional and
 * absent means binary, which is what the header needs: the value is base64 of the raw transferable
 * key (`pgpPublicKeyCacheValue`). Asking for armor gets base64 of an armored block — bigger, valid
 * looking, and unreadable to the receiving client.
 *
 * ⚠ A blank [ownAddress] puts no `EXTRA_MINIMIZE_USER_ID` at all rather than an empty one: what a
 * provider does with an empty filter is nobody's promise, and "no key at all" would kill the
 * announcement outright, where "every user id" merely says too much. Callers hand over the
 * account's address (`StoredAccount.username`), so this is the empty-record case, not the normal one.
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
 * ⛔ `isMutual = false`, always: `prefer-encrypt=mutual` is a standing instruction to start
 * encrypting, and that is not a decision taken from an incoming message.
 * ⛔ [effectiveDateMillis] is that message's own date, not now: the provider keeps the newest, so
 * the date is what stops an old message overwriting a newer key.
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
