package app.sterna.ui.settings

import app.sterna.core.data.settings.DeliveryMode
import app.sterna.core.data.settings.NotificationContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The five stored values the notifications screen shows, carried as ONE value (#57).
 *
 * Each of them used to be its own `stateIn` with `initialValue` set to the repository's default, so
 * the screen was composed with five defaults and corrected a frame later, once DataStore answered.
 * A reader who had set "Neither sender nor subject" saw the radio land on "Sender + subject" on
 * every single visit — the view model is rebuilt each time, the back stack entry having been
 * destroyed on the way out — and the choice it displayed was not the choice that was stored.
 *
 * The reason this is not merely ugly: the radio rows are CLICKABLE during that frame. A tap that
 * lands on the default writes the default to DataStore, and a privacy setting is lost without a
 * word. So the screen must not be drawn at all until the stored values are known.
 *
 * [notificationSettingsState] is where "are they all known yet?" is decided: [combine] emits
 * nothing until every one of the five flows has produced a value, and the screen shows a spinner
 * while its state is null. The decision lives here, as a function, so a test can run it rather than
 * read it.
 */
data class NotificationSettingsState(
    val deliveryMode: DeliveryMode,
    val notificationContent: NotificationContent,
    val quietHoursEnabled: Boolean,
    val quietHoursStart: Int,
    val quietHoursEnd: Int,
)

/**
 * Combines the five stored settings into one value. Nothing is emitted until all five have
 * answered; afterwards every change to any of them emits again, as before.
 */
internal fun notificationSettingsState(
    deliveryMode: Flow<DeliveryMode>,
    notificationContent: Flow<NotificationContent>,
    quietHoursEnabled: Flow<Boolean>,
    quietHoursStart: Flow<Int>,
    quietHoursEnd: Flow<Int>,
): Flow<NotificationSettingsState> = combine(
    deliveryMode,
    notificationContent,
    quietHoursEnabled,
    quietHoursStart,
    quietHoursEnd,
) { mode, content, quietEnabled, quietStart, quietEnd ->
    NotificationSettingsState(
        deliveryMode = mode,
        notificationContent = content,
        quietHoursEnabled = quietEnabled,
        quietHoursStart = quietStart,
        quietHoursEnd = quietEnd,
    )
}
