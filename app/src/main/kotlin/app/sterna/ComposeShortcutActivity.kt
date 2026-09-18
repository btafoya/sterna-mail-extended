package app.sterna

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * The "New message" launcher shortcut's target. Android starts a static shortcut with
 * NEW_TASK | CLEAR_TASK, which aimed at [MainActivity] would wipe its task and a composer open in
 * it. This relay takes that hit in a task of its own (empty `taskAffinity` in the manifest), then
 * hands [MainActivity] a fresh mailto: intent without CLEAR_TASK, so it arrives through
 * `onNewIntent` like a link from another app. The received intent is never forwarded: its flags
 * are the very ones being shed.
 */
class ComposeShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // unguarded: one hand-off per launch into this same app, with no screen for a second tap.
        startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .setClassName(packageName, MainActivity::class.java.name)
                .setFlags(LAUNCH_FLAGS),
        )
        // Theme.NoDisplay requires finishing before onCreate returns.
        finish()
    }

    companion object {
        const val LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK
    }
}
