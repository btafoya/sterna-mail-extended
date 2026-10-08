package app.sterna.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The volume keys' step along [MessageTextSize] (#250), and the switch that arms them: both
 * functions are run, with every expected size written out.
 *
 * Not covered here: that `stepMessageTextSize` calls them on the value stored in the transaction,
 * which is a source lint in `SettingsWriteWiringTest`, and everything on the activity's side.
 */
class MessageTextSizeStepTest {

    @Test fun `a step up walks the four sizes in order`() {
        assertEquals(MessageTextSize.NORMAL, MessageTextSize.SMALL.stepped(1))
        assertEquals(MessageTextSize.LARGE, MessageTextSize.NORMAL.stepped(1))
        assertEquals(MessageTextSize.HUGE, MessageTextSize.LARGE.stepped(1))
    }

    @Test fun `a step down walks them back`() {
        assertEquals(MessageTextSize.LARGE, MessageTextSize.HUGE.stepped(-1))
        assertEquals(MessageTextSize.NORMAL, MessageTextSize.LARGE.stepped(-1))
        assertEquals(MessageTextSize.SMALL, MessageTextSize.NORMAL.stepped(-1))
    }

    @Test fun `the largest size does not step up onto the smallest`() {
        assertEquals(
            "volume up on HUGE must leave HUGE: wrapping to SMALL shrinks the text of a reader " +
                "who was asking for more",
            MessageTextSize.HUGE,
            MessageTextSize.HUGE.stepped(1),
        )
    }

    @Test fun `the smallest size does not step down onto the largest`() {
        assertEquals(MessageTextSize.SMALL, MessageTextSize.SMALL.stepped(-1))
    }

    @Test fun `two steps from a stored name are two sizes away`() {
        val once = messageTextSizeOf("NORMAL").stepped(1)
        assertEquals(MessageTextSize.HUGE, messageTextSizeOf(once.name).stepped(1))
    }

    @Test fun `a size never stored, or stored under a name that no longer exists, steps from NORMAL`() {
        assertEquals(MessageTextSize.NORMAL, messageTextSizeOf(null))
        assertEquals(MessageTextSize.NORMAL, messageTextSizeOf("GIGANTIC"))
        assertEquals(MessageTextSize.LARGE, messageTextSizeOf(null).stepped(1))
        assertEquals(MessageTextSize.SMALL, messageTextSizeOf("SMALL"))
    }

    @Test fun `the volume keys keep their role until the switch is turned on`() {
        assertFalse(
            "an untouched install must leave the volume keys alone: on by default, updating the " +
                "app would silently take the volume away from every open message",
            volumeTextSizeFrom(emptyPreferences()),
        )
        assertFalse("the shared default itself", VOLUME_TEXT_SIZE_DEFAULT)
        assertTrue(volumeTextSizeFrom(preferencesOf(booleanPreferencesKey("volume_text_size") to true)))
        assertFalse(volumeTextSizeFrom(preferencesOf(booleanPreferencesKey("volume_text_size") to false)))
        assertFalse(
            "another switch's key must not arm this one",
            volumeTextSizeFrom(preferencesOf(booleanPreferencesKey("pure_black") to true)),
        )
    }

    @Test fun `the switch survives an export and an import, and an older backup leaves it alone`() {
        val encoded = SettingsBackupCodec.encode(SettingsBackup(volumeTextSize = true))
        assertTrue("exported: $encoded", encoded.contains("\"volumeTextSize\": true"))
        assertEquals(true, SettingsBackupCodec.decode(encoded)?.volumeTextSize)
        assertNull(SettingsBackupCodec.decode("""{"version":1,"themeMode":"DARK"}""")!!.volumeTextSize)
    }
}
