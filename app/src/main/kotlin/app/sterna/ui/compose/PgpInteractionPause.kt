package app.sterna.ui.compose

/**
 * The composer's state once the screen takes [launched] to start the provider's screen: `Idle` when
 * that pause is still the current state, the current state untouched otherwise, so a send that
 * resumed, ended or paused again in the meantime is never overwritten.
 *
 * The view model outlives a rotation under the provider's screen, so a pause left in the state is
 * launched again by the recreated screen. The screen consumes it before the launch, with nothing in
 * between: writing `Idle` recomposes the screen and that recomposition cancels the effect, so a
 * suspension between the two calls lets the cancellation land before the launch.
 *
 * By identity: a data class compares its `PendingIntent`, whose `equals` compares the token the
 * system holds behind it, and throws in the unit-test `android.jar`.
 */
internal fun composeStateAfterPgpLaunch(
    current: ComposeState,
    launched: ComposeState.PgpInteraction,
): ComposeState = if (current === launched) ComposeState.Idle else current
