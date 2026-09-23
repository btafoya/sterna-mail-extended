package app.sterna.core.data.settings

import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/** The time zone every outgoing date is written in (#120): a reply's attribution line, a forward's
 *  Date header, and the date of the sent message itself, the SMTP and APPEND `Date:` header and
 *  JMAP's `sentAt`. The reader and the list rows keep the device's clock whatever this says. */
fun outgoingDateZone(
    quotedDatesUtc: Boolean,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): ZoneId = if (quotedDatesUtc) ZoneOffset.UTC else deviceZone

/** [outgoingDateZone], resolved by SUSPENDING on the stored setting: the first emission is awaited,
 *  never substituted with a default. DataStore answers a beat late on a cold start, and "not answered
 *  yet" is not "off", a reply prefilled in that beat would write the local time with the switch on. */
suspend fun resolveOutgoingDateZone(
    quotedDatesUtc: Flow<Boolean>,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): ZoneId = outgoingDateZone(quotedDatesUtc.first(), deviceZone)

/**
 * Where a sending path asks for its date's zone, at the moment the bytes are built and never before:
 * a message queued with the switch on and sent after it was turned off goes out in the device's zone.
 * The device zone is asked at the same moment, so a phone that changed zone meanwhile is honoured.
 */
class OutgoingDateZones(
    private val quotedDatesUtc: Flow<Boolean>,
    private val deviceZone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun atSend(): ZoneId = resolveOutgoingDateZone(quotedDatesUtc, deviceZone())

    companion object {
        /** No settings wired: the device's zone, the switch's default. */
        val DEVICE = OutgoingDateZones(flowOf(false))
    }
}
