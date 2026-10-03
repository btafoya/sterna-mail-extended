package app.sterna.ui.inbox

/** A drawer entry's label: [name] alone while [count] is zero, [withCount] once it holds rows.
 *  Inline so [withCount] may call `stringResource` from the drawer. */
internal inline fun countedLabel(name: String, count: Int, withCount: (String, Int) -> String): String =
    if (count > 0) withCount(name, count) else name

/** Whether a folder row's name is bold: while the row shows [unread], the number its label carries,
 *  and only if the reader kept the setting ([enabled]). */
internal fun folderLabelBold(unread: Int, enabled: Boolean): Boolean = enabled && unread > 0

/** The lists the drawer offers under Unread. */
internal enum class DrawerList { SCHEDULED, SNOOZED, OUTBOX }

/**
 * Which of [DrawerList] the drawer draws, in drawing order. A list with nothing to show is not
 * drawn at all, so a drawer with nothing scheduled, snoozed or waiting is the one it was before
 * these lists existed. No Trash exception: they are offered from every folder.
 */
internal fun drawerListsToShow(scheduled: Int, snoozed: Int, outbox: Int): List<DrawerList> =
    buildList {
        if (scheduled > 0) add(DrawerList.SCHEDULED)
        if (snoozed > 0) add(DrawerList.SNOOZED)
        if (outbox > 0) add(DrawerList.OUTBOX)
    }
