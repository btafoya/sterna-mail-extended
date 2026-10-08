package app.sterna.core.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

/** The stored [MessageTextSize] name read back; an absent or unknown name is NORMAL. */
internal fun messageTextSizeOf(stored: String?): MessageTextSize =
    stored?.let { runCatching { MessageTextSize.valueOf(it) }.getOrNull() } ?: MessageTextSize.NORMAL

/**
 * The size [direction] steps away, positive towards HUGE, held at either end: a press past the
 * largest size must not land on the smallest.
 *
 * `SettingsRepository.stepMessageTextSize` calls it on the name stored in the transaction it
 * writes in, not on the last value its flow emitted: two presses that land before the flow
 * re-emits are then two steps, not the same step taken twice.
 */
internal fun MessageTextSize.stepped(direction: Int): MessageTextSize =
    MessageTextSize.entries[(ordinal + direction).coerceIn(0, MessageTextSize.entries.lastIndex)]

/** The key the volume-keys switch is stored under. */
internal val KEY_VOLUME_TEXT_SIZE = booleanPreferencesKey("volume_text_size")

/**
 * What the volume keys do when nobody has touched the switch: change the volume (#250). Off
 * deliberately, a key that stops doing what the phone says it does must be something the reader
 * asked for.
 */
const val VOLUME_TEXT_SIZE_DEFAULT = false

/** Whether the volume keys step the message text size, read from the stored preferences. */
internal fun volumeTextSizeFrom(prefs: Preferences): Boolean =
    prefs[KEY_VOLUME_TEXT_SIZE] ?: VOLUME_TEXT_SIZE_DEFAULT
