package app.sterna.core.data.account

import app.sterna.core.data.settings.DeliveryMode
import app.sterna.core.data.settings.resolveDeliveryMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a record already on disk decodes to once an account can choose its own delivery mode, and
 * what that record then runs under. The two halves belong together: the field is optional, so the
 * decode alone says nothing about the behaviour a device gets.
 */
class PerAccountDeliveryModeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** A record as a device that upgraded into this branch has it: no `deliveryMode` key. */
    private val preBranch =
        """[{"id":"a1","server":"https://mail.example.test","username":"demo@example.test",
           |"accountName":"Demo"}]""".trimMargin().replace("\n", "")

    @Test
    fun `an account that never chose follows the global setting, whichever it is`() {
        assertEquals(
            "an account with no choice of its own must run under the global setting. Answering " +
                "INSTANT here wakes every battery-saver install up on instant delivery, on the " +
                "update, with nothing on screen saying so.",
            DeliveryMode.BATTERY_SAVER,
            resolveDeliveryMode(null, DeliveryMode.BATTERY_SAVER),
        )
        assertEquals(
            DeliveryMode.INSTANT,
            resolveDeliveryMode(null, DeliveryMode.INSTANT),
        )
    }

    @Test
    fun `a choice of its own wins over the global setting`() {
        assertEquals(
            "an explicit per-account choice must win: the global setting is only the seed for the " +
                "accounts that never made one.",
            DeliveryMode.INSTANT,
            resolveDeliveryMode(DeliveryMode.INSTANT, DeliveryMode.BATTERY_SAVER),
        )
        assertEquals(
            DeliveryMode.BATTERY_SAVER,
            resolveDeliveryMode(DeliveryMode.BATTERY_SAVER, DeliveryMode.INSTANT),
        )
    }

    @Test
    fun `a record written before this field decodes to no choice, and runs on the global one`() {
        val decoded = json.decodeFromString<List<StoredAccount>>(preBranch).single()
        assertNull(
            "an account stored before 'deliveryMode' existed must decode to null, i.e. to 'never " +
                "chose'. Any non-null default here is a choice nobody made, and it silently " +
                "overrides the setting the account was really running under.",
            decoded.deliveryMode,
        )
        assertEquals(
            "and that record must then run under the global setting it was stored with.",
            DeliveryMode.BATTERY_SAVER,
            resolveDeliveryMode(decoded.deliveryMode, DeliveryMode.BATTERY_SAVER),
        )
    }

    @Test
    fun `a record that stored a mode decodes as that mode and keeps it under any global setting`() {
        val stored = preBranch.replace("\"id\":\"a1\"", "\"id\":\"a1\",\"deliveryMode\":\"BATTERY_SAVER\"")
        val decoded = json.decodeFromString<List<StoredAccount>>(stored).single()
        assertEquals(
            "the stored value must be read back. If this is null the field is write-only — the " +
                "account answers 'follows the global setting' again on the next cold start, " +
                "whatever the user chose.",
            DeliveryMode.BATTERY_SAVER,
            decoded.deliveryMode,
        )
        assertEquals(
            DeliveryMode.BATTERY_SAVER,
            resolveDeliveryMode(decoded.deliveryMode, DeliveryMode.INSTANT),
        )
    }
}
