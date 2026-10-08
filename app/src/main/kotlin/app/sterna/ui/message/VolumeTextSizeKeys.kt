package app.sterna.ui.message

import android.view.KeyEvent
import androidx.compose.runtime.staticCompositionLocalOf

/** What the activity does with one key event: whether it swallows it, and the [step] it takes
 *  along `MessageTextSize`, 0 for none. */
internal data class VolumeKeyDecision(val consumed: Boolean, val step: Int)

/**
 * The volume keys' rule (#250), on raw arguments so it runs without a device. Its one caller is
 * `MainActivity.dispatchKeyEvent`, not the reader: the body WebView only sees a key while it holds
 * the focus, and nothing in the reader asks for it.
 *
 * The down and the up of a press are swallowed together: an up let through alone reaches the
 * system as half a press. Only the first down steps, a held key repeats and would run to the end
 * of the scale in a second. Nothing here knows the scale's ends: a press at HUGE is still
 * swallowed, so the volume does not suddenly move when the text stops growing.
 */
internal fun volumeKeyDecision(
    action: Int,
    keyCode: Int,
    repeatCount: Int,
    enabled: Boolean,
    messageShown: Boolean,
): VolumeKeyDecision {
    val direction = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> 1
        KeyEvent.KEYCODE_VOLUME_DOWN -> -1
        else -> 0
    }
    val press = action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP
    if (!enabled || !messageShown || direction == 0 || !press) return VolumeKeyDecision(consumed = false, step = 0)
    val step = if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) direction else 0
    return VolumeKeyDecision(consumed = true, step = step)
}

/**
 * How many `MessageScreen`s are in composition, which is what "a message is on screen" means to
 * the activity: the reading pane shows one without any `message/…` route.
 *
 * A count rather than a flag, and held by the screen rather than by a page: the entering screen
 * registers before the leaving one is disposed, and a pager page that drops out of composition
 * while its neighbour is being read would otherwise unregister the message still on screen.
 *
 * The lock screen is drawn over a `MessageScreen` that stays composed, so the activity also asks
 * `AppLock.locked` before it calls a message shown.
 */
internal class VolumeTextSizeRelay {
    private var screens = 0

    val messageShown: Boolean get() = screens > 0

    fun enter() {
        screens++
    }

    fun leave() {
        if (screens > 0) screens--
    }
}

/** Null outside `MainActivity`'s provider, where no key event can be routed anyway. */
internal val LocalVolumeTextSizeRelay = staticCompositionLocalOf<VolumeTextSizeRelay?> { null }
