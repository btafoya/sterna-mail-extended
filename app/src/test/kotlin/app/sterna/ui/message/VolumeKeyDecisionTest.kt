package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The volume keys' rule (#250), run: [volumeKeyDecision] and [VolumeTextSizeRelay].
 *
 * Every number is the platform's own constant, written out as in `KeyArmsResizeProbeTest`, and
 * every expected decision is spelled in full: nothing here re-derives the shipped rule to choose
 * what to expect.
 *
 * Not covered: that the activity is handed these events at all, that it passes them through
 * unchanged, and that a `MessageScreen` really registers. The first needs a device, the other two
 * are the source lint `VolumeTextSizeWiringTest`.
 */
class VolumeKeyDecisionTest {

    private val left = VolumeKeyDecision(consumed = false, step = 0)
    private val swallowed = VolumeKeyDecision(consumed = true, step = 0)

    @Test fun `the first down of volume up is one step up, of volume down one step down`() {
        assertEquals(VolumeKeyDecision(consumed = true, step = 1), decide(DOWN, VOLUME_UP))
        assertEquals(VolumeKeyDecision(consumed = true, step = -1), decide(DOWN, VOLUME_DOWN))
    }

    @Test fun `a held key is swallowed without stepping again`() {
        assertEquals(
            "the repeats of a held volume key must not step: at one repeat every 50 ms the text " +
                "would cross the whole scale before the finger lifts",
            swallowed,
            decide(DOWN, VOLUME_UP, repeatCount = 1),
        )
        assertEquals(swallowed, decide(DOWN, VOLUME_DOWN, repeatCount = 7))
    }

    @Test fun `the up of a press is swallowed with its down, and steps nothing`() {
        assertEquals(swallowed, decide(UP, VOLUME_UP))
        assertEquals(swallowed, decide(UP, VOLUME_DOWN))
    }

    @Test fun `with the switch off both keys are left to the system`() {
        for (action in listOf(DOWN, UP)) for (key in listOf(VOLUME_UP, VOLUME_DOWN)) {
            assertEquals("action $action key $key", left, decide(action, key, enabled = false))
        }
    }

    @Test fun `with no message on screen both keys are left to the system`() {
        for (action in listOf(DOWN, UP)) for (key in listOf(VOLUME_UP, VOLUME_DOWN)) {
            assertEquals("action $action key $key", left, decide(action, key, messageShown = false))
        }
    }

    @Test fun `no other key is ever taken`() {
        for (key in listOf(VOLUME_MUTE, BACK, ENTER, MEDIA_PLAY_PAUSE, 0)) {
            assertEquals("key $key down", left, decide(DOWN, key))
            assertEquals("key $key up", left, decide(UP, key))
        }
    }

    @Test fun `an action that is neither a down nor an up is left alone`() {
        assertEquals(left, decide(MULTIPLE, VOLUME_UP))
    }

    @Test fun `one screen leaving does not unregister the one that stays`() {
        val relay = VolumeTextSizeRelay()
        assertFalse(relay.messageShown)
        relay.enter()
        relay.enter()
        relay.leave()
        assertTrue(
            "two screens entered and one left: a message is still on screen. A flag instead of a " +
                "count hands the keys back to the system here, mid-reading",
            relay.messageShown,
        )
        relay.leave()
        assertFalse(relay.messageShown)
    }

    @Test fun `a leave with nothing registered does not go below zero`() {
        val relay = VolumeTextSizeRelay()
        relay.leave()
        relay.enter()
        assertTrue(relay.messageShown)
    }

    private fun decide(
        action: Int,
        keyCode: Int,
        repeatCount: Int = 0,
        enabled: Boolean = true,
        messageShown: Boolean = true,
    ) = volumeKeyDecision(action, keyCode, repeatCount, enabled, messageShown)

    // android.view.KeyEvent, written out: `static final int`, frozen API.
    private companion object {
        const val DOWN = 0
        const val UP = 1
        const val MULTIPLE = 2
        const val BACK = 4
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val ENTER = 66
        const val MEDIA_PLAY_PAUSE = 85
        const val VOLUME_MUTE = 164
    }
}
