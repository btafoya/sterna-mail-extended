package app.sterna.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry

/** True only while this lifecycle is the resumed (settled, on-top) one. A destination
 *  mid-transition is at most STARTED, and one just navigated away from drops out of RESUMED
 *  synchronously, so the whole re-entrancy question reduces to this one read. */
private fun Lifecycle.isSettled(): Boolean = currentState == Lifecycle.State.RESUMED

private fun NavBackStackEntry.isSettled(): Boolean = lifecycle.isSettled()

/**
 * THE single decision point for every navigation action in the app. A new NavHost that forgets it is
 * the defect this exists to prevent (#106: the settings graph was written without the guard, and
 * rapid taps stacked copies of a page).
 *
 * [action] runs only if this entry is still the settled destination, which kills three things with
 * no timer to tune: a second tap on a screen already animating away, two fingers on two rows in one
 * frame, and a tap on a Back arrow still visible during a pop. When the action is a pop whose
 * FAILURE means something (#34), put the whole `if (!nav.popBackStack()) onBack()` inside [action].
 *
 * Deliberately NOT used for single-consumption navigations: those arrive once, possibly
 * mid-transition, and dropping one loses the user's action rather than de-duplicating it.
 */
internal fun NavBackStackEntry.navigateOnce(action: () -> Unit) {
    if (isSettled()) action()
}

/**
 * The same guarantee as [navigateOnce] for an action that LEAVES the app (#106 follow-up: a double
 * tap opened the browser twice). [navigateOnce] alone cannot cover them — it works because
 * `navigate()` demotes the entry below RESUMED synchronously, while `startActivity()` leaves our
 * activity resumed for hundreds of milliseconds — so the lifecycle read is paired with a latch,
 * released by the entry returning to RESUMED and never by a timer.
 *
 * [action] reports whether it really did leave, and only then is the latch armed: with no browser
 * the launch throws, nothing happened, and the row must stay live. Each call owns ONE latch, shared
 * by every hand-off holding the opener it returned — ask for it once per screen and two rows tapped
 * together open one page.
 */
@Composable
internal fun rememberLeaveOnce(entry: NavBackStackEntry): (() -> Boolean) -> Unit =
    rememberLeaveOnce(entry.lifecycle)

/**
 * [rememberLeaveOnce] for a hand-off written deep inside a screen, where threading the destination's
 * [NavBackStackEntry] down would mean a parameter on every composable between. It reads the same
 * object by another route: inside a NavHost destination `LocalLifecycleOwner` IS that destination's
 * back-stack entry. On a screen that is NOT a nav destination the owner is the activity instead, and
 * the guarantee degrades to what such a screen can promise.
 */
@Composable
internal fun rememberLeaveOnce(): (() -> Boolean) -> Unit =
    rememberLeaveOnce(LocalLifecycleOwner.current.lifecycle)

@Composable
private fun rememberLeaveOnce(lifecycle: Lifecycle): (() -> Boolean) -> Unit {
    val latch = remember(lifecycle) { LeaveLatch() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latch.release()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return { action -> latch.leave(lifecycle.isSettled(), action) }
}

/** The decision [rememberLeaveOnce] makes, on its own so it can be tested without a composition —
 *  which is why the caller's `startActivity` has to report back rather than swallow its exception.
 *  Plain fields, not Compose state: nothing reads the latch while composing. */
internal class LeaveLatch {
    private var left = false

    fun leave(settled: Boolean, action: () -> Boolean) {
        if (!left && settled) left = action()
    }

    fun release() {
        left = false
    }
}
