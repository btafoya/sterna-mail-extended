package app.sterna

import android.content.Intent
import android.net.MailTo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.appcompat.app.AppCompatActivity
import app.sterna.core.data.settings.LIST_MONOGRAM_DEFAULT
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.data.settings.PURE_BLACK_DEFAULT
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.data.settings.UNREAD_TINT_DEFAULT
import app.sterna.ui.SternaApp
import app.sterna.ui.compose.ComposerResumeSlot
import app.sterna.ui.message.NavFadeGuard
import app.sterna.ui.components.LocalListDensity
import app.sterna.ui.components.LocalListMonogram
import app.sterna.ui.components.LocalPreviewLines
import app.sterna.ui.components.LocalUnreadTint
import app.sterna.ui.theme.SternaTheme

class MainActivity : AppCompatActivity() {
    /** A mailto: link waiting to open the compose screen (Codeberg #15). Set from the launch
     *  intent or [onNewIntent] (the activity is singleTask, so an external link re-enters the
     *  running instance), consumed by the NavHost once it has navigated. */
    private val pendingMailto = androidx.compose.runtime.mutableStateOf<MailtoDraft?>(null)

    /** A new-mail notification tap waiting to open that message (Codeberg #17 follow-up). Same
     *  singleTask/onNewIntent plumbing as [pendingMailto]. */
    private val pendingEmailOpen = androidx.compose.runtime.mutableStateOf<EmailOpenTarget?>(null)

    /** A home-screen widget tap waiting to put the list on the unified inbox (Codeberg #112).
     *  Same singleTask/onNewIntent plumbing as the two above, and the same one-shot discipline:
     *  it is an order carried out once, not a state the activity is in. */
    private val pendingUnifiedOpen = androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a real launch. The activity is singleTask, so the intent that opened a
        // notification (or a mailto: link) stays the activity's intent for good; re-parsing it
        // on every recreation — rotation, theme/locale change, font size, split screen — would
        // re-navigate to that message on top of whatever the user is doing, once per
        // recreation, and pile up back-stack entries they then have to unwind.
        if (savedInstanceState == null) {
            pendingMailto.value = parseMailto(intent) ?: parseShare(intent)
            pendingEmailOpen.value = parseEmailOpen(intent)
            pendingUnifiedOpen.value = parseUnifiedOpen(intent)
            consumeOAuthRedirect()
            // Sweep the orphaned composer resumes. With no saved state there is no
            // `rememberSaveable` left anywhere that could consume ANY slot, so every file still
            // sitting there is the leftover of a crash or a kill whose screen never came back —
            // this is the only moment that can be said without guessing. ⭐ ALL of them, which is
            // safer and not less so: there is one file per composer entry, and a sweep that knew
            // only a single name would leave the others' cleartext under filesDir for good.
            // ⛔ Not on every recreation: a rotation, a theme flip or a language change DOES come
            // back to that composer, and sweeping there would delete the very file the
            // recomposition is about to read.
            ComposerResumeSlot.sweep(filesDir)
        }
        val settings = application.container.settingsRepository
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
            val density by settings.listDensity.collectAsState(initial = ListDensity.NORMAL)
            val previewLines by settings.previewLines.collectAsState(initial = PreviewLines.ONE)
            // UNREAD_TINT_DEFAULT, not a literal: this is the first frame's copy of a default that
            // exists in three places (see LocalListDensity), and a literal that drifts from the
            // repository's flashes the wrong list on every launch.
            val unreadTint by settings.unreadTint.collectAsState(initial = UNREAD_TINT_DEFAULT)
            // LIST_MONOGRAM_DEFAULT, not a literal, for the same reason: a literal here that drifts
            // from the repository's flashes a list with the wrong start column on every launch.
            val listMonogram by settings.listMonogram.collectAsState(initial = LIST_MONOGRAM_DEFAULT)
            // PURE_BLACK_DEFAULT, not a literal, for the same reason as the line above: this is the
            // first frame's copy of a default that also lives in the repository and the view model,
            // and one that drifts repaints the whole theme a frame after launch.
            val pureBlack by settings.pureBlack.collectAsState(initial = PURE_BLACK_DEFAULT)
            SternaTheme(themeMode = themeMode, dynamicColor = dynamicColor, pureBlack = pureBlack) {
                CompositionLocalProvider(
                    LocalListDensity provides density,
                    LocalPreviewLines provides previewLines,
                    LocalUnreadTint provides unreadTint,
                    LocalListMonogram provides listMonogram,
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
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (parseMailto(intent) ?: parseShare(intent))?.let { pendingMailto.value = it }
        parseEmailOpen(intent)?.let { pendingEmailOpen.value = it }
        // The widget's usual path, not an edge case: the app is normally already running when a
        // home-screen cell is tapped, singleTask hands the intent here, and onCreate never runs
        // again. Set only when the extra is there, like the two above — a bare assignment would
        // clear a request that arrived a moment ago and has not been consumed yet.
        if (parseUnifiedOpen(intent)) pendingUnifiedOpen.value = true
        consumeOAuthRedirect()
    }

    /**
     * Hand an OAuth authorization-code redirect (`<applicationId>://oauth`, Codeberg #55) to the
     * app-scoped driver, and strip it from the retained intent once taken.
     *
     * One-shot like the two payloads above, and for a sharper reason: an authorization code is
     * redeemable exactly once, so a re-read of the intent (a rotation, a locale change) would hand
     * the driver a return it has already consumed — which correctly answers "no sign-in is in
     * progress" and would put that error on screen right after the account was added. The driver
     * refuses a redirect that is not ours without reading anything else of it, and only then is
     * the payload dropped; a mailto: or a share on the same intent keeps its own (they cannot
     * collide — `parseMailto` filters on the `mailto` scheme).
     */
    private fun consumeOAuthRedirect() {
        val current = intent ?: return
        val data = current.data ?: return
        if (!application.container.oauthCodeSignIn.onRedirect(data)) return
        current.data = null
    }

    /**
     * Drop a consumed one-shot payload from the retained intent. Belt and braces next to the
     * [onCreate] guard: whatever re-reads the intent later, it no longer carries an open/compose
     * order that was already carried out. Kept per-payload so consuming one can never cancel a
     * genuinely pending other.
     */
    private fun stripEmailOpenPayload() {
        val i = intent ?: return
        i.removeExtra(EXTRA_OPEN_EMAIL_ID)
        i.removeExtra(EXTRA_OPEN_ACCOUNT_ID)
        i.removeExtra(EXTRA_OPEN_MAILBOX_ID)
    }

    /**
     * The same one-shot strip for the home-screen widget's request (#112), and its OWN function on
     * purpose — see [stripEmailOpenPayload]'s note. Folded into that one, opening a message would
     * also discard a widget tap that had just arrived and not yet been carried out; the reverse
     * holds too. Belt to the `savedInstanceState == null` guard: without it, whatever re-reads the
     * retained intent later would find an order already obeyed and re-select the unified inbox
     * over the folder the user has since chosen.
     */
    private fun stripUnifiedOpenPayload() {
        val i = intent ?: return
        i.removeExtra(EXTRA_OPEN_UNIFIED)
    }

    /** Does this intent carry a home-screen widget tap? (#112) */
    private fun parseUnifiedOpen(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_UNIFIED, false) == true

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

    /** RFC 6068 mailto: parsing — addresses plus the optional subject/body/cc/bcc fields. */
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
     * A system "Share" (ACTION_SEND / SEND_MULTIPLE) opens the compose screen: shared text and
     * subject prefill the fields (reusing the mailto path), and any shared files are stashed for
     * the compose screen to attach (Codeberg #45).
     *
     * Only `content:` URIs are taken. The resolver would happily open a `file:` URI too, with OUR
     * permissions and no grant involved, so any app could hand us a path inside our own private
     * directory (the database, the account preferences) and have it pre-attached to a draft. A
     * legitimate share has been a `content:` URI since Android 7 anyway.
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

    override fun onStop() {
        super.onStop()
        application.container.appLock.onAppBackgrounded(System.currentTimeMillis())
        // Stopped activity → no frames → the reader's GL functor cannot draw, so a process
        // kill while backgrounded (LMK, swipe-away) must not count as a fade-window crash.
        NavFadeGuard.onActivityStop(this)
    }

    override fun onStart() {
        super.onStart()
        application.container.appLock.onAppForegrounded(System.currentTimeMillis())
        NavFadeGuard.onActivityStart(this)
        applySecureFlag()
    }

    /**
     * With app lock on, mark the window FLAG_SECURE so message bodies and the credential
     * screen are kept out of the recents thumbnail, screenshots, and screen recordings —
     * otherwise the lock overlay hides the live UI but the OS still snapshots it.
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
         * Open the cross-account unified inbox — set by the home-screen widget (#112), and only
         * when the app actually HAS that view (two accounts or more; see `UnreadWidgetTap`). With
         * one account or none the widget sends no extra at all and the app opens where it already
         * opens: that account's inbox, or its sign-in route.
         */
        const val EXTRA_OPEN_UNIFIED = "app.sterna.OPEN_UNIFIED"
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
 * The message a tapped new-mail notification should open (Codeberg #17 follow-up), and the
 * context it lives in: its account (#31) and the folder it sits in (#91), so the list underneath
 * ends up where the message is and Back lands in a list that holds it. Either may be null for a
 * notification posted by an older version.
 */
data class EmailOpenTarget(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String? = null,
)
