package app.sterna

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.net.MailTo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.sterna.core.data.settings.BOLD_UNREAD_FOLDERS_DEFAULT
import app.sterna.core.data.settings.HIDE_LIST_STAR_DEFAULT
import app.sterna.core.data.settings.STARRED_IN_DRAWER_DEFAULT
import app.sterna.core.data.settings.LIST_MONOGRAM_DEFAULT
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.data.settings.PURE_BLACK_DEFAULT
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.data.settings.UNREAD_TINT_DEFAULT
import app.sterna.core.data.settings.VOLUME_TEXT_SIZE_DEFAULT
import app.sterna.ui.SternaApp
import app.sterna.ui.compose.ComposerResumeSlot
import app.sterna.ui.message.LocalVolumeTextSizeRelay
import app.sterna.ui.message.NavFadeGuard
import app.sterna.ui.message.VolumeTextSizeRelay
import app.sterna.ui.message.volumeKeyDecision
import app.sterna.ui.components.LocalBoldUnreadFolders
import app.sterna.ui.components.LocalHideListStar
import app.sterna.ui.components.LocalStarredInDrawer
import app.sterna.ui.components.LocalListDensity
import app.sterna.ui.components.LocalListMonogram
import app.sterna.ui.components.LocalSenderNames
import app.sterna.ui.components.LocalPreviewLines
import app.sterna.ui.components.LocalUnreadTint
import app.sterna.ui.components.LocalIs24Hour
import app.sterna.ui.theme.SternaTheme
import app.sterna.util.MailDates
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    /** A mailto: link waiting to open the compose screen (#15). Set from the launch intent or
     *  [onNewIntent], consumed by the NavHost once it has navigated. */
    private val pendingMailto = androidx.compose.runtime.mutableStateOf<MailtoDraft?>(null)

    /** A new-mail notification tap waiting to open that message (Codeberg #17 follow-up). Same
     *  singleTask/onNewIntent plumbing as [pendingMailto]. */
    private val pendingEmailOpen = androidx.compose.runtime.mutableStateOf<EmailOpenTarget?>(null)

    /** A home-screen widget tap waiting to put the list on the unified inbox (#112). Same one-shot
     *  discipline as the two above: an order carried out once, not a state the activity is in. */
    private val pendingUnifiedOpen = androidx.compose.runtime.mutableStateOf(false)

    private val pendingUnreadOpen = androidx.compose.runtime.mutableStateOf(false)

    private val volumeTextSizeRelay = VolumeTextSizeRelay()

    private val is24Hour = androidx.compose.runtime.mutableStateOf(MailDates.is24Hour())

    private val timeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { is24Hour.value = MailDates.is24Hour() }
    }

    private var volumeTextSize = VOLUME_TEXT_SIZE_DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a real launch. The activity is singleTask, so the intent that opened a
        // notification stays the activity's intent for good, and re-parsing it on every recreation
        // would re-navigate to that message on top of whatever the user is doing.
        if (savedInstanceState == null) {
            pendingMailto.value = parseMailto(intent) ?: parseShare(intent)
            pendingEmailOpen.value = parseEmailOpen(intent)
            pendingUnifiedOpen.value = parseUnifiedOpen(intent)
            pendingUnreadOpen.value = parseUnreadOpen(intent)
            consumeOAuthRedirect()
            // Sweep the orphaned composer resumes. With no saved state there is no
            // `rememberSaveable` left that could consume any slot, so every file still sitting
            ComposerResumeSlot.sweep(filesDir)
        }
        val settings = application.container.settingsRepository
        lifecycleScope.launch { settings.volumeTextSize.collect { volumeTextSize = it } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                settings.dynamicColor.distinctUntilChanged().collect { enabled ->
                    splashScreen.setSplashScreenTheme(
                        if (enabled) R.style.Theme_Sterna_Splash_Dynamic else Resources.ID_NULL,
                    )
                }
            }
        }
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
            val density by settings.listDensity.collectAsState(initial = ListDensity.NORMAL)
            val previewLines by settings.previewLines.collectAsState(initial = PreviewLines.ONE)
            // UNREAD_TINT_DEFAULT, not a literal: this is the first frame's copy of a default that
            // exists in three places, and a literal that drifts flashes the wrong list on launch.
            val unreadTint by settings.unreadTint.collectAsState(initial = UNREAD_TINT_DEFAULT)
            // `LIST_MONOGRAM_DEFAULT`, `BOLD_UNREAD_FOLDERS_DEFAULT`, `STARRED_IN_DRAWER_DEFAULT`, `HIDE_LIST_STAR_DEFAULT`: same reason.
            val listMonogram by settings.listMonogram.collectAsState(initial = LIST_MONOGRAM_DEFAULT)
            val boldUnreadFolders by settings.boldUnreadFolders.collectAsState(initial = BOLD_UNREAD_FOLDERS_DEFAULT)
            val starredInDrawer by settings.starredInDrawer.collectAsState(initial = STARRED_IN_DRAWER_DEFAULT)
            val hideListStar by settings.hideListStar.collectAsState(initial = HIDE_LIST_STAR_DEFAULT)
            val senderNames by application.container.senderNames.names.collectAsState()
            // PURE_BLACK_DEFAULT, not a literal, for the same reason: one that drifts repaints the
            // whole theme a frame after launch.
            val pureBlack by settings.pureBlack.collectAsState(initial = PURE_BLACK_DEFAULT)
            SternaTheme(themeMode = themeMode, dynamicColor = dynamicColor, pureBlack = pureBlack) {
                CompositionLocalProvider(
                    LocalListDensity provides density,
                    LocalPreviewLines provides previewLines,
                    LocalUnreadTint provides unreadTint,
                    LocalListMonogram provides listMonogram,
                    LocalSenderNames provides senderNames,
                    LocalBoldUnreadFolders provides boldUnreadFolders,
                    LocalStarredInDrawer provides starredInDrawer,
                    LocalHideListStar provides hideListStar,
                    LocalVolumeTextSizeRelay provides volumeTextSizeRelay,
                    LocalIs24Hour provides is24Hour.value,
                ) {
                    SternaApp(
                        pendingMailto = pendingMailto.value,
                        onMailtoConsumed = {
                            pendingMailto.value = null
                            stripMailtoPayload()
                        },
                        pendingEmailOpen = pendingEmailOpen.value,
                        onEmailOpenConsumed = {
                            pendingEmailOpen.value = null
                            stripEmailOpenPayload()
                        },
                        pendingUnifiedOpen = pendingUnifiedOpen.value,
                        onUnifiedOpenConsumed = {
                            pendingUnifiedOpen.value = false
                            stripUnifiedOpenPayload()
                        },
                        pendingUnreadOpen = pendingUnreadOpen.value,
                        onUnreadOpenConsumed = {
                            pendingUnreadOpen.value = false
                            stripUnreadOpenPayload()
                        },
                    )
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val messageShown = volumeTextSizeRelay.messageShown && !application.container.appLock.locked.value
        val decision = volumeKeyDecision(event.action, event.keyCode, event.repeatCount, volumeTextSize, messageShown)
        if (!decision.consumed) return super.dispatchKeyEvent(event)
        if (decision.step != 0) {
            lifecycleScope.launch { application.container.settingsRepository.stepMessageTextSize(decision.step) }
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (parseMailto(intent) ?: parseShare(intent))?.let { pendingMailto.value = it }
        parseEmailOpen(intent)?.let { pendingEmailOpen.value = it }
        // The widget's usual path, not an edge case: the app is normally already running when a
        // cell is tapped. Set only when the extra is there, like the two above, a bare assignment
        // would clear a request that arrived a moment ago and has not been consumed.
        if (parseUnifiedOpen(intent)) pendingUnifiedOpen.value = true
        if (parseUnreadOpen(intent)) pendingUnreadOpen.value = true
        consumeOAuthRedirect()
    }

    /**
     * Hand an OAuth authorization-code redirect (#55) to the app-scoped driver, and strip it from
     * app-scoped driver, and strip it from the retained intent once taken.
     */
    private fun consumeOAuthRedirect() {
        val current = intent ?: return
        val data = current.data ?: return
        if (!application.container.oauthCodeSignIn.onRedirect(data)) return
        current.data = null
    }

    /**
     * Drop a consumed one-shot payload from the retained intent.
     */
    private fun stripEmailOpenPayload() {
        val i = intent ?: return
        i.removeExtra(EXTRA_OPEN_EMAIL_ID)
        i.removeExtra(EXTRA_OPEN_ACCOUNT_ID)
        i.removeExtra(EXTRA_OPEN_MAILBOX_ID)
    }

    /**
     * The same one-shot strip for the home-screen widget's request (#112), and its own function on
     * purpose, see [stripEmailOpenPayload]'s note: folded into that one, opening a message would
     * also discard a widget tap that had just arrived and not yet been carried out.
     */
    private fun stripUnifiedOpenPayload() {
        val i = intent ?: return
        i.removeExtra(EXTRA_OPEN_UNIFIED)
    }

    /** Does this intent carry a home-screen widget tap? (#112) */
    private fun parseUnifiedOpen(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_UNIFIED, false) == true

    private fun stripUnreadOpenPayload() {
        val i = intent ?: return
        i.removeExtra(EXTRA_OPEN_UNREAD)
    }

    private fun parseUnreadOpen(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_UNREAD, false) == true

    private fun stripMailtoPayload() {
        val i = intent ?: return
        if ("mailto".equals(i.scheme, ignoreCase = true)) i.data = null
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) {
            i.action = Intent.ACTION_MAIN
            i.removeExtra(Intent.EXTRA_SUBJECT)
            i.removeExtra(Intent.EXTRA_TEXT)
            i.removeExtra(Intent.EXTRA_STREAM)
        }
    }

    /** The message a tapped new-mail notification wants to open, or null. */
    private fun parseEmailOpen(intent: Intent?): EmailOpenTarget? {
        val emailId = intent?.getStringExtra(EXTRA_OPEN_EMAIL_ID) ?: return null
        return EmailOpenTarget(
            emailId = emailId,
            accountId = intent.getStringExtra(EXTRA_OPEN_ACCOUNT_ID)?.ifBlank { null },
            mailboxId = intent.getStringExtra(EXTRA_OPEN_MAILBOX_ID)?.ifBlank { null },
        )
    }

    /** RFC 6068 mailto: parsing, addresses plus the optional subject/body/cc/bcc fields. */
    private fun parseMailto(intent: Intent?): MailtoDraft? {
        val data = intent?.data ?: return null
        if (!"mailto".equals(data.scheme, ignoreCase = true)) return null
        return runCatching {
            val m = MailTo.parse(data.toString())
            MailtoDraft(
                to = m.to.orEmpty(),
                cc = m.cc.orEmpty(),
                bcc = m.headers?.get("bcc").orEmpty(),
                subject = m.subject.orEmpty(),
                body = m.body.orEmpty(),
            )
        }.getOrNull()
    }

    /**
     * A system "Share" opens the compose screen: shared text and subject prefill the fields, and any
     */
    private fun parseShare(intent: Intent?): MailtoDraft? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        @Suppress("DEPRECATION")
        val uris: List<Uri> = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }.filter { "content".equals(it.scheme, ignoreCase = true) }
        if (subject.isBlank() && text.isBlank() && uris.isEmpty()) return null
        application.container.pendingShareUris = uris
        return MailtoDraft(to = "", cc = "", bcc = "", subject = subject, body = text)
    }

    override fun onResume() {
        super.onResume()
        is24Hour.value = MailDates.is24Hour()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(timeChangedReceiver)
        application.container.appLock.onAppBackgrounded(System.currentTimeMillis())
        // A stopped activity draws no frames, so the reader's GL functor cannot draw, and a process kill
        // while backgrounded must not count as a fade-window crash.
        NavFadeGuard.onActivityStop(this)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            timeChangedReceiver,
            IntentFilter(Intent.ACTION_TIME_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        application.container.appLock.onAppForegrounded(System.currentTimeMillis())
        NavFadeGuard.onActivityStart(this)
        applySecureFlag()
    }

    /**
     * With app lock on, mark the window FLAG_SECURE so message bodies stay out of the recents
     */
    private fun applySecureFlag() {
        if (application.container.appLock.isEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        const val EXTRA_OPEN_EMAIL_ID = "app.sterna.OPEN_EMAIL_ID"
        const val EXTRA_OPEN_ACCOUNT_ID = "app.sterna.OPEN_ACCOUNT_ID"
        const val EXTRA_OPEN_MAILBOX_ID = "app.sterna.OPEN_MAILBOX_ID"

        /**
         * Open the cross-account unified inbox, set by the home-screen widget (#112), and only
         */
        const val EXTRA_OPEN_UNIFIED = "app.sterna.OPEN_UNIFIED"

        const val EXTRA_OPEN_UNREAD = "app.sterna.OPEN_UNREAD"
    }
}

/** Prefill fields parsed from a mailto: link, handed to the compose screen (Codeberg #15). */
data class MailtoDraft(
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
)

/**
 * The message a tapped new-mail notification should open, and the context it lives in: its account
 */
data class EmailOpenTarget(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String? = null,
)
