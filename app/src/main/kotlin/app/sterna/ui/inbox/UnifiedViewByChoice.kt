package app.sterna.ui.inbox

/** Whether "open on" [openOnView] picks the opening view itself, the memory left unread.
 *  `UnifiedViewByChoiceTest` holds this level with [openOnSelection], which it may not call. */
internal fun openOnOverridesMemory(openOnView: String?): Boolean =
    openOnView == OPEN_ON_INBOX || openOnView == OPEN_ON_UNIFIED || openOnView == OPEN_ON_UNREAD

/**
 * Whether [sel] is an "All inboxes" the reader stands in, the question a tapped new-mail
 * notification asks before moving the list, its account half and its folder half alike. One the
 * "open on" setting opened on is not: the notification's folder wins over the setting (#185).
 * The push arm asks the other question, what is on screen, and keeps `restoredUnifiedView`.
 * The unread view of every account counts as "All inboxes", picked by the reader or opened by the
 * setting (#282), and the notification wins over the setting's the same way.
 */
internal fun unifiedViewByChoice(sel: Sel?, openedBySetting: Boolean): Boolean =
    (sel is Sel.Unified || unreadOfEveryAccount(sel)) && !openedBySetting
