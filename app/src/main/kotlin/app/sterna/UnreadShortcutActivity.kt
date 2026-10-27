package app.sterna

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The "Unread" launcher shortcut's target, a relay for the same reason as [ComposeShortcutActivity]
 * and declared like it: the CLEAR_TASK a static shortcut is started with is absorbed here, and
 * [MainActivity] receives a fresh order without it. A class of its own rather than a shared relay,
 * so neither shortcut's hand-off can be changed by editing the other's.
 */
class UnreadShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // unguarded: one hand-off per launch into this same app, with no screen for a second tap.
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .setClassName(packageName, MainActivity::class.java.name)
                .putExtra(MainActivity.EXTRA_OPEN_UNREAD, true)
                .setFlags(LAUNCH_FLAGS),
        )
        // Theme.NoDisplay requires finishing before onCreate returns.
        finish()
    }

    companion object {
        const val LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK
    }
}
