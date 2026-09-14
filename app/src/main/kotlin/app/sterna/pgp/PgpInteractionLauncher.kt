package app.sterna.pgp

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.app.ActivityOptionsCompat

@Composable
fun rememberPgpInteractionLauncher(onResult: (Intent?) -> Unit): (PendingIntent) -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        onResult(if (result.resultCode == Activity.RESULT_OK) result.data ?: Intent() else null)
    }
    return remember(launcher) {
        { pendingIntent ->
            val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            val mode = pgpInteractionStartMode(Build.VERSION.SDK_INT)
            if (mode == null) {
                launcher.launch(request)
            } else {
                launcher.launch(
                    request,
                    ActivityOptionsCompat.makeBasic().setPendingIntentBackgroundActivityStartMode(mode),
                )
            }
        }
    }
}

// From Android 14 a windowless provider's PendingIntent starts only if the visible sender grants it;
// narrowest mode, never ALLOW_ALWAYS: Sterna does not check which app made it (SECURITY.md).
@Suppress("DEPRECATION")
internal fun pgpInteractionStartMode(sdkInt: Int): Int? = when {
    sdkInt >= Build.VERSION_CODES.BAKLAVA -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
    else -> null
}
