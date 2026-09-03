package app.sterna.core.data.mail

import app.sterna.core.data.pgp.PgpEngine
import app.sterna.core.data.pgp.PgpResult
import java.util.Base64

/**
 * What an incoming `Autocrypt` header is worth: the peer to file the key under, the key bytes, and
 * the date the key is effective at. Nothing else — no peer state, no encryption preference, nothing
 * on screen.
 */
internal class AutocryptPeerImport(
    val peerId: String,
    val keyData: ByteArray,
    val effectiveDateMillis: Long,
)

/** Autocrypt Level 1 §2.1: everything else that is not prefixed `_` makes the message worthless. */
private val KNOWN_ATTRIBUTES = setOf("addr", "keydata", "prefer-encrypt")

/**
 * The whole receiving decision of Autocrypt Level 1 §2.1, as ONE pure function so a test can EXECUTE
 * it: given a message's header fields in wire order with duplicates kept, the address its `From`
 * names, and the date it was received, either the key to give the OpenPGP provider or `null`.
 *
 * `null` means "ignore, in silence": no banner, no error, the message opens exactly as it would
 * have. Nothing an incoming header says is worth failing an open over.
 *
 * ⛔ **[headers] must come from `MimeParser.rawHeaders` or JMAP's `headers` property, NEVER from
 * `MimeParser.headerOf`.** That one is a Map keyed by field name, so it keeps the LAST occurrence of
 * a repeated field — and the very first rule below is that a message carrying TWO `Autocrypt`
 * headers is ignored whole. Fed a map, this function could not see the second one, and would import
 * whichever key the map happened to keep. The same reasoning is written above
 * [originalSenderHeaderOf], for a header where picking one of two is merely wrong; here it is the
 * key a correspondent's mail gets encrypted to.
 *
 * The four rules, all Level 1 §2.1, and all here rather than at the call sites:
 *  - **exactly one** `Autocrypt` field, matched without case. Two or more: nothing. This is the
 *    guard the feature stands on — a header appended anywhere along the path would otherwise file
 *    the appender's key under the sender's address, and everything written to that address
 *    afterwards is encrypted to them, silently, with no screen ever saying so;
 *  - `addr` present and naming the SAME mailbox as the message's `From`, compared `trim()` +
 *    `lowercase()` — the same comparison the sending half makes
 *    (`AutocryptHeaderValue.sameMailbox`), for the same reason: a key is being bound to an address,
 *    and it may only be bound to the address that announced it;
 *  - **no unknown critical attribute**. An attribute whose name starts with `_` is non-critical and
 *    is skipped whatever it holds; any other unknown name means a version of the standard this code
 *    does not implement, and the message is ignored entirely rather than half-read;
 *  - `keydata` present and decodable as base64 once **every whitespace character is removed** —
 *    §2.1 says the parser ignores whitespace, and it has to: `rawHeaders` joins a folded header's
 *    continuations with a space, so an RSA-4096 key (~1 550 base64 characters, far past RFC 5322's
 *    998-octet line, hence always folded) arrives with spaces inside it, and JMAP hands back the
 *    raw value with the sender's CRLF+WSP still in place.
 *
 * ⛔ `prefer-encrypt` is READ AND THROWN AWAY. It is named in [KNOWN_ATTRIBUTES] only so that a
 * header carrying it is not treated as unknown-critical and dropped. Whether this app starts
 * encrypting by itself is another decision, taken elsewhere or not at all.
 *
 * ⛔ A date that cannot be read is a refusal, not a "now". The provider keeps the newest key it was
 * given for a peer, so a made-up date on an old message can make a stale key win over the current
 * one INSIDE OpenKeychain, where this app cannot undo it. Refusing costs a key we never had; the
 * other way costs a key the user does have (arbitration rule 1, the irreversible option first).
 */
internal fun autocryptPeerImportOf(
    headers: List<Pair<String, String>>,
    fromAddress: String?,
    receivedAt: String?,
): AutocryptPeerImport? {
    val from = fromAddress?.trim()?.lowercase().orEmpty()
    if (from.isEmpty()) return null
    val effectiveDate = internalDateOf(receivedAt) ?: return null
    // singleOrNull, and it is the guard, not a convenience: zero and two both answer null.
    val value = headers
        .filter { (name, _) -> name.trim().equals(FIELD_NAME, ignoreCase = true) }
        .singleOrNull()
        ?.second
        ?: return null
    val attributes = mutableMapOf<String, String>()
    // base64 contains no ';' and an addr-spec that reaches a server contains none either, so the
    // split cannot fall inside a value.
    for (part in value.split(';')) {
        val text = part.trim()
        val equals = text.indexOf('=')
        // An empty piece (a doubled or trailing ';') or an attribute with no name or no '=' is not
        // an attribute-list. Malformed is treated as unknown-critical: ignore the message.
        if (equals <= 0) return null
        val name = text.substring(0, equals).trim().lowercase()
        if (name.startsWith("_")) continue
        if (name !in KNOWN_ATTRIBUTES) return null
        // A repeated attribute is malformed too, and a second `addr` or `keydata` is exactly the
        // shape of a message trying to have it both ways.
        if (attributes.put(name, text.substring(equals + 1).trim()) != null) return null
    }
    if (attributes["addr"]?.trim()?.lowercase() != from) return null
    val keydata = attributes["keydata"]?.filterNot { it.isWhitespace() }
    if (keydata.isNullOrEmpty()) return null
    // Strict decoder (never the MIME one, which silently skips characters it dislikes and would
    // turn a corrupt header into a plausible key), and never an exception out of here.
    val bytes = runCatching { Base64.getDecoder().decode(keydata) }.getOrNull()
    if (bytes == null || bytes.isEmpty()) return null
    return AutocryptPeerImport(peerId = from, keyData = bytes, effectiveDateMillis = effectiveDate)
}

/** The field name, once. */
private const val FIELD_NAME = "Autocrypt"

/**
 * Run [autocryptPeerImportOf] over one message's headers and, when it says so, hand the key to the
 * OpenPGP provider. Answers whether the provider took it — for the tests; the reader ignores it.
 *
 * ⛔ Every answer that is not [PgpResult.Success] is dropped in silence,
 * [PgpResult.UserInteractionRequired] included: this runs on the open path, which has no screen and
 * no launcher for a provider's `PendingIntent`, and nothing here queues one for later. No engine at
 * all (no provider wired) is the same non-event.
 */
internal suspend fun importAutocryptPeer(
    engine: PgpEngine?,
    headers: List<Pair<String, String>>,
    fromAddress: String?,
    receivedAt: String?,
): Boolean {
    if (engine == null) return false
    val import = autocryptPeerImportOf(headers, fromAddress, receivedAt) ?: return false
    return engine.updateAutocryptPeer(import.peerId, import.keyData, import.effectiveDateMillis) is
        PgpResult.Success
}
