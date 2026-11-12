package app.sterna.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * The phone's 12/24-hour setting, which `MainActivity` rereads on `onResume` and on
 * `ACTION_TIME_CHANGED`. Both are needed: the receiver is unregistered while Settings is in front,
 * and an app left resumed beside Settings in split screen never passes through `onResume`.
 * Recreating the activity instead was ruled out, it drops the pending notification, share and
 * mailto orders. Every surface that forms a time reads this and keys its cache on it, since the
 * switch is not a configuration change and recomposes nothing by itself.
 */
val LocalIs24Hour = compositionLocalOf { true }
