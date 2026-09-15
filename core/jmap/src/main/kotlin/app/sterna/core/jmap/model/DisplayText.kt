package app.sterna.core.jmap.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * [s] without the control characters and bidi marks a sender could use to disguise a header:
 * `Support <U+202E>moc.knab@troppus` otherwise reads as a bank's address.
 *
 * The list is the one IMAP applies in `:core:imap`'s internal `stripBidiAndControls`; a parity test
 * in `:core:data` compares the two over the whole BMP. Tab and line feed are kept, as IMAP keeps
 * them; the original-sender panel clamps its lines for that reason. No trimming: callers decide.
 */
fun withoutBidiAndControls(s: String): String = s.filterNot { c ->
    val code = c.code
    (code in 0x00..0x08) || (code in 0x0B..0x1F) || (code in 0x7F..0x9F) ||
        (code in 0x202A..0x202E) || (code in 0x2066..0x2069) || code == 0x200F || code == 0x200E
}

/**
 * Filters a display-only string field each time it is decoded, whatever the source: a JMAP
 * response, a cached body, a stored recipients column. Encoding writes the value unchanged.
 *
 * Only on `Email.subject`, `EmailAddress.name` and `EmailBodyPart.name`. The address beside a name
 * is where a reply goes, and a filtered address is another one; `headers` is the raw view (#60).
 * Plain columns do not come through here, and a cached row may hold the server's raw bytes: no
 * migration rewrites it. `EmailEntity.toEmail`, `FtsHit.toEmail` and the widget's projection filter
 * subject and sender name themselves; the snoozed list, the by-sender screen and the recipient
 * suggestions read those columns through their own projections and do not.
 */
internal object DisplayTextSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.sterna.core.jmap.model.DisplayText", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String = withoutBidiAndControls(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
