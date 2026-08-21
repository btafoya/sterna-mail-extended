package app.sterna.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.account.K9SettingsImporter
import app.sterna.core.data.settings.LIST_MONOGRAM_DEFAULT
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.MessageTextSize
import app.sterna.core.data.settings.PLAIN_TEXT_DEFAULT
import app.sterna.core.data.settings.QUOTED_DATES_UTC_DEFAULT
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.data.settings.PURE_BLACK_DEFAULT
import app.sterna.core.data.settings.REPLY_BAR_DEFAULT
import app.sterna.core.data.settings.UNREAD_TINT_DEFAULT
import app.sterna.core.data.settings.SettingsBackup
import app.sterna.core.data.settings.SettingsBackupCodec
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.data.settings.DeliveryMode
import app.sterna.core.data.settings.NotificationContent
import app.sterna.push.NewMailNotifier
import app.sterna.push.PushController
import app.sterna.security.canAuthenticate
import app.sterna.widget.RecentMailWidgetDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val appLock = application.container.appLock
    private val settings = application.container.settingsRepository

    private val _pushAllAccounts = MutableStateFlow(store.pushAllAccounts())
    val pushAllAccounts = _pushAllAccounts.asStateFlow()

    private val _appLock = MutableStateFlow(store.appLockEnabled())
    val appLockEnabled = _appLock.asStateFlow()

    /** Set when the user tries to enable app lock but no biometric / screen lock exists. */
    private val _appLockUnavailable = MutableStateFlow(false)
    val appLockUnavailable = _appLockUnavailable.asStateFlow()

    val themeMode = settings.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.SYSTEM,
    )

    val dynamicColor = settings.dynamicColor.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    val listDensity = settings.listDensity.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ListDensity.NORMAL,
    )

    val previewLines = settings.previewLines.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PreviewLines.ONE,
    )

    // UNREAD_TINT_DEFAULT for the first frame, like replyBar below and for the same reason: a
    // literal here would be a fourth copy of the default, free to disagree with the repository for
    // as long as DataStore takes to answer.
    val unreadTint = settings.unreadTint.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UNREAD_TINT_DEFAULT,
    )

    fun setUnreadTint(enabled: Boolean) {
        viewModelScope.launch { settings.setUnreadTint(enabled) }
    }

    // PURE_BLACK_DEFAULT for the first frame, like unreadTint above and for the same reason: a
    // literal here would be a third copy of the default, free to disagree with the repository for
    // as long as DataStore takes to answer — the switch would show the wrong position on entry.
    val pureBlack = settings.pureBlack.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PURE_BLACK_DEFAULT,
    )

    fun setPureBlack(enabled: Boolean) {
        viewModelScope.launch { settings.setPureBlack(enabled) }
    }

    // LIST_MONOGRAM_DEFAULT for the first frame, like the two above and for the same reason: a
    // literal here would be a fourth copy of the default, free to disagree with the repository for
    // as long as DataStore takes to answer — the switch would show the wrong position on entry.
    val listMonogram = settings.listMonogram.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LIST_MONOGRAM_DEFAULT,
    )

    fun setListMonogram(enabled: Boolean) {
        viewModelScope.launch { settings.setListMonogram(enabled) }
    }

    val swipeRight = settings.swipeRightAction.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SwipeAction.TOGGLE_READ,
    )

    val swipeLeft = settings.swipeLeftAction.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SwipeAction.DELETE,
    )

    val contactSuggestions = settings.contactSuggestions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setContactSuggestions(value: Boolean) {
        viewModelScope.launch { settings.setContactSuggestions(value) }
    }

    val stripTrackingParams = settings.stripTrackingParams.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setStripTrackingParams(value: Boolean) {
        viewModelScope.launch { settings.setStripTrackingParams(value) }
    }

    val confirmLinks = settings.confirmLinks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setConfirmLinks(value: Boolean) {
        viewModelScope.launch { settings.setConfirmLinks(value) }
    }

    /**
     * The read-receipt switch (#148): whether an opened message may ASK whether to answer the
     * sender who requested one. Off by default, and off is the position the switch shows while
     * DataStore answers — here, unlike in the reader, that literal is only what a switch DRAWS
     * for a frame, and the stored default is what it is drawn against.
     *
     * ⛔ The screen must not grow a third position. "Always send" is not a setting this app has.
     */
    val askReadReceipt = settings.askReadReceipt.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setAskReadReceipt(value: Boolean) {
        viewModelScope.launch { settings.setAskReadReceipt(value) }
    }

    val imageAllowlist = settings.imageAllowlist.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet(),
    )

    fun clearImageAllowlist() {
        viewModelScope.launch { settings.clearImageAllowlist() }
    }

    fun setImageAllowed(sender: String, allowed: Boolean) {
        viewModelScope.launch { settings.setImageAllowed(sender, allowed) }
    }

    /**
     * Everything the notifications screen shows — delivery mode (#17), notification content (#25)
     * and quiet hours — as ONE value, `null` until DataStore has answered for all five.
     *
     * Five separate `stateIn`s, each seeded with the repository's default, meant the screen was
     * composed from defaults and corrected a frame later; the reader saw the wrong radio selected
     * on every visit, and a tap landing in that frame wrote the default over the stored choice
     * (#57). See [NotificationSettingsState] for the gate itself.
     */
    val notificationSettings: StateFlow<NotificationSettingsState?> = notificationSettingsState(
        deliveryMode = settings.deliveryMode,
        notificationContent = settings.notificationContent,
        quietHoursEnabled = settings.quietHoursEnabled,
        quietHoursStart = settings.quietHoursStart,
        quietHoursEnd = settings.quietHoursEnd,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    fun setDeliveryMode(mode: DeliveryMode) {
        viewModelScope.launch {
            settings.setDeliveryMode(mode)
            // Re-arm with the new outcome (stops or restarts the foreground service).
            PushController.apply(getApplication(), userInitiated = true)
        }
    }

    fun setNotificationContent(mode: NotificationContent) {
        viewModelScope.launch {
            settings.setNotificationContent(mode)
            // Same reason as setAppLock: this setting governs the home-screen widget too, and a
            // cell already drawn does not re-read it. Tightening it has to reach the cell now.
            RecentMailWidgetDraw.forget()
            RecentMailWidgetDraw.refresh(getApplication())
        }
    }

    fun setPushAllAccounts(value: Boolean) {
        store.setPushAllAccounts(value)
        _pushAllAccounts.value = value
        if (value) {
            // Accounts (re)entering the watched scope kept frozen baselines while out of
            // it; diffing those would burst stale notifications — drop them so the first
            // pass reseeds silently.
            store.allCredentials().filter { it.id != store.currentId() }
                .forEach { NewMailNotifier.clear(getApplication(), it.id) }
        }
        // Reconnect push with the new scope.
        PushController.apply(getApplication(), userInitiated = true)
    }

    fun setQuietHoursEnabled(value: Boolean) {
        viewModelScope.launch { settings.setQuietHoursEnabled(value) }
    }

    fun setQuietHoursStart(minutes: Int) {
        viewModelScope.launch { settings.setQuietHoursStart(minutes) }
    }

    fun setQuietHoursEnd(minutes: Int) {
        viewModelScope.launch { settings.setQuietHoursEnd(minutes) }
    }

    /**
     * Writes a JSON snapshot of the app's preferences and account configuration
     * (never credentials) to [uri]. [onResult] is invoked on the main thread with
     * success/failure.
     */
    fun exportSettings(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                val backup = settings.snapshotBackup().copy(
                    pushAllAccounts = store.pushAllAccounts(),
                    language = currentAppLanguage().tag,
                    accounts = store.accountsForBackup(),
                )
                val text = SettingsBackupCodec.encode(backup)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(text.toByteArray())
                    } ?: error("no output stream")
                }
            }.isSuccess
            onResult(ok)
        }
    }

    /**
     * Reads a backup file from [uri] and applies it. [onResult] reports success plus how many
     * accounts were newly added (0 if none/failure) — imported accounts have no password and must
     * be signed into. [onLanguageChanged] fires (with the new language) only when the language
     * differs, so the caller can recreate the activity to load the new strings.
     */
    fun importSettings(
        uri: Uri,
        onResult: (ok: Boolean, accountsAdded: Int) -> Unit,
        onLanguageChanged: (AppLanguage) -> Unit,
    ) {
        viewModelScope.launch {
            val backup: SettingsBackup? = runCatching {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("no input stream")
                }
                SettingsBackupCodec.decode(text)
            }.getOrNull()
            // Reject a file that isn't parseable, or that parses but carries no recognizable backup
            // fields (an unrelated JSON) — the caller shows an error and stays on its menu.
            if (backup == null || !backup.isPlausible()) {
                onResult(false, 0)
                return@launch
            }
            settings.restoreBackup(backup)
            backup.pushAllAccounts?.let { setPushAllAccounts(it) }
            _pushAllAccounts.value = store.pushAllAccounts()
            val accountsAdded = backup.accounts?.let { store.importAccounts(it) } ?: 0
            onResult(true, accountsAdded)
            backup.language?.let { tag ->
                val target = AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
                if (target != currentAppLanguage()) onLanguageChanged(target)
            }
        }
    }

    /** Parse a K-9 / Thunderbird `.k9s` export and import its (inert) accounts. [onResult] reports
     *  success, how many accounts were added (0 on failure/none), how many were skipped
     *  (POP3 / unsupported), and how many got a guessed connection security the user should
     *  check. Imported accounts have no credentials and must be signed into. */
    fun importK9Settings(uri: Uri, onResult: (ok: Boolean, added: Int, skipped: Int, unverified: Int) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        K9SettingsImporter.parse(it)
                    } ?: error("no input stream")
                }
            }.getOrNull()
            if (result == null) { onResult(false, 0, 0, 0); return@launch }
            val added = store.importAccounts(result.accounts)
            onResult(true, added, result.skipped.size, result.securityUnverified.size)
        }
    }

    fun setAppLock(value: Boolean) {
        if (value && !canAuthenticate(getApplication())) {
            _appLockUnavailable.value = true
            _appLock.value = false
            return
        }
        _appLockUnavailable.value = false
        appLock.setEnabled(value)
        _appLock.value = value
        // The home screen is outside this window and keeps whatever it was last drawn with. With
        // no periodic update by design, the latest-messages widget would go on listing senders and
        // subjects until mail next ARRIVED — days, on a quiet mailbox — for someone who has just
        // switched the lock on to hide exactly that. SECURITY.md states the lock silences that
        // widget with no conditions attached, so this is what makes the sentence true.
        viewModelScope.launch {
            RecentMailWidgetDraw.forget()
            RecentMailWidgetDraw.refresh(getApplication())
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setListDensity(density: ListDensity) {
        viewModelScope.launch { settings.setListDensity(density) }
    }

    fun setPreviewLines(value: PreviewLines) {
        viewModelScope.launch { settings.setPreviewLines(value) }
    }

    fun setSwipeRight(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeRightAction(action) }
    }

    fun setSwipeLeft(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeLeftAction(action) }
    }

    val conversationView = settings.conversationView.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setConversationView(enabled: Boolean) {
        viewModelScope.launch { settings.setConversationView(enabled) }
    }

    val markReadOnDelete = settings.markReadOnDelete.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setMarkReadOnDelete(enabled: Boolean) {
        viewModelScope.launch { settings.setMarkReadOnDelete(enabled) }
    }

    val markReadOnArchive = settings.markReadOnArchive.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setMarkReadOnArchive(enabled: Boolean) {
        viewModelScope.launch { settings.setMarkReadOnArchive(enabled) }
    }

    val markReadOnMove = settings.markReadOnMove.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setMarkReadOnMove(enabled: Boolean) {
        viewModelScope.launch { settings.setMarkReadOnMove(enabled) }
    }

    val unarchiveOnReply = settings.unarchiveOnReply.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setUnarchiveOnReply(enabled: Boolean) {
        viewModelScope.launch { settings.setUnarchiveOnReply(enabled) }
    }

    val signatureOnReplies = settings.signatureOnReplies.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setSignatureOnReplies(enabled: Boolean) {
        viewModelScope.launch { settings.setSignatureOnReplies(enabled) }
    }

    val signatureBelowQuote = settings.signatureBelowQuote.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setSignatureBelowQuote(enabled: Boolean) {
        viewModelScope.launch { settings.setSignatureBelowQuote(enabled) }
    }

    val signatureDelimiter = settings.signatureDelimiter.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setSignatureDelimiter(enabled: Boolean) {
        viewModelScope.launch { settings.setSignatureDelimiter(enabled) }
    }

    // The first frame, before DataStore has answered: REPLY_BAR_DEFAULT, the same definition the
    // repository reads the stored value against (#63). A literal here was a second copy of the
    // default, and the two could disagree for as long as the store takes to open.
    val replyBar = settings.replyBar.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = REPLY_BAR_DEFAULT,
    )

    fun setReplyBar(enabled: Boolean) {
        viewModelScope.launch { settings.setReplyBar(enabled) }
    }

    // The first frame, before DataStore has answered: PLAIN_TEXT_DEFAULT, like replyBar above and
    // for the same reason — a literal here would be a second copy of the default, and the switch
    // would show the wrong position on entry for as long as the store takes to open (#149). The
    // READER seeds the same setting with null instead, and deliberately: see MessageViewModel.
    val plainText = settings.plainText.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PLAIN_TEXT_DEFAULT,
    )

    fun setPlainText(enabled: Boolean) {
        viewModelScope.launch { settings.setPlainText(enabled) }
    }

    // The first frame, before DataStore has answered: QUOTED_DATES_UTC_DEFAULT, like replyBar above
    // and for the same reason — a literal here would be a second copy of the default (#120). The
    // COMPOSER never reads this state: it suspends on the repository flow itself, so a prefill in
    // that first beat waits for the stored answer instead of taking this seed.
    val quotedDatesUtc = settings.quotedDatesUtc.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = QUOTED_DATES_UTC_DEFAULT,
    )

    fun setQuotedDatesUtc(enabled: Boolean) {
        viewModelScope.launch { settings.setQuotedDatesUtc(enabled) }
    }

    val messageTextSize = settings.messageTextSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MessageTextSize.NORMAL,
    )

    fun setMessageTextSize(size: MessageTextSize) {
        viewModelScope.launch { settings.setMessageTextSize(size) }
    }
}
