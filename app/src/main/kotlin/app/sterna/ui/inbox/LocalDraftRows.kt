package app.sterna.ui.inbox

import androidx.paging.PagingData
import androidx.paging.TerminalSeparatorType
import androidx.paging.filter
import androidx.paging.insertHeaderItem
import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant

/*
 * The drafts this phone holds and the server has not got, shown at the top of the Drafts folder
 * (#95).
 *
 * The table has been written on every save and uploaded by a worker since the first volets of #95,
 * and NOTHING displayed it: with no network the composer closed like a success and the Drafts
 * folder stayed empty, so the user had no trace at all of the text she had just typed. Everything
 * here exists to end that, and to do so without lending a local row any power it does not have.
 *
 * ⛔ **No state filter.** A `STAGING` row is listed like any other: its text is on the phone, and
 * hiding it would re-open exactly the lie this closes. What a local row does NOT get is an action —
 * see [isLocalDraftRow].
 */

/** The role a folder must carry for its list to show the phone's own drafts. */
private const val DRAFTS_ROLE = "drafts"

/** The two-line preview of a draft body is cut here — the row shows at most two lines anyway. */
private const val PREVIEW_CHARS = 200

/**
 * What the phone's own drafts contribute to the list on screen: the rows to draw on top, and the
 * SERVER ids those rows stand in for.
 *
 * The two travel together because they are read from the same rows and must not be able to
 * disagree: a local row shown without hiding the server draft it replaces means the same draft
 * twice, and the copy the user would reach for is the one the upload is about to destroy.
 */
internal data class LocalDraftRows(
    val rows: List<InboxRow> = emptyList(),
    val replacedServerIds: Set<String> = emptySet(),
)

/**
 * True when [id] names a row of `local_drafts` rather than a message a server knows about.
 *
 * ⛔ **The one predicate every gesture site asks**, and it neutralises rather than routes. No action
 * path knows this table: `repo.delete` / `archive` / `moveToMailbox` / `setRead` are silent no-ops
 * on IMAP (they read a UID out of an id that carries none) and errors on JMAP, and none of them
 * removes the local row — so a swipe would announce "deleted" and offer an Undo over a row that
 * never moved.
 *
 * ⭐ The TAP is the exception, and the only one: it opens the draft in the composer (#95). That is
 * safe because `ComposeViewModel.prepare` asks `draftOpenRoute` which store the id belongs to and
 * fills the screen from `local_drafts` alone — no `fetchEmail`, no `cachedEmail`, nothing a server
 * could be asked about a row it has never heard of.
 */
internal fun isLocalDraftRow(id: String?): Boolean = id != null && id.startsWith(LOCAL_DRAFT_ID_PREFIX)

/**
 * Whether the list on screen is the one place the phone's own drafts belong: THIS account's Drafts
 * folder, with the "unread only" funnel off.
 *
 * Answered from the folder-role map, never from a [PageKey] member: every member added to that key
 * rebuilds the pager on its own, and `PageKeyFlowTest` / `PageKeyWiringLintTest` pin that member by
 * member. The unified inbox has no selected folder id, so it answers false here with no case of its
 * own.
 *
 * ⛔ [unreadOnly] is a refusal, not a filter to write later. A local row is read by construction
 * (`$seen`, [InboxRow.unread] false — a draft the user has just typed is not unread mail), so under
 * a funnel that promises "unread only" it is the one row on screen that does not belong there. The
 * price is accepted: with the funnel ON, a draft just saved with no network is not listed. The
 * funnel is something the user turned on and can see; drawing a read row under it is not.
 */
internal fun showsLocalDrafts(
    accountId: String?,
    mailboxId: String?,
    roles: Map<Pair<String, String>, String>,
    unreadOnly: Boolean,
): Boolean {
    if (accountId == null || mailboxId == null || unreadOnly) return false
    return roles[accountId to mailboxId] == DRAFTS_ROLE
}

/**
 * Whether the row drawn for [id] answers to a SWIPE at all — the guard that has to sit on
 * `gesturesEnabled`, i.e. BEFORE the gesture runs, not after.
 *
 * ⛔ The refusal inside `performSwipe` is a belt, never the braces: `commitSwipe` plays the whole
 * take-off arc — the row flies off the edge, tilts and fades to nothing — and only THEN calls
 * `onSwipe`. A left swipe is DELETE by default, so a local draft left swipeable is destroyed on
 * screen exactly as a real deletion is, with no snackbar and no Undo, while the text is still in the
 * table: the user is shown the loss of the only copy of what she has just typed. The "motion off"
 * branch is no better, it snaps the row to the same edge.
 *
 * [selectionActive] is the pre-existing half and is LOAD-BEARING beyond this row (see the call site
 * in `InboxScreen`): no row, no selection, which is what keeps "Select all" from reaching a folder
 * behind an empty list (#126). Both halves must survive together.
 */
internal fun rowGesturesEnabled(id: String?, selectionActive: Boolean): Boolean =
    !selectionActive && !isLocalDraftRow(id)

/**
 * Whether the row drawn for [id] gets a star at all.
 *
 * The star is a gesture like any other, on EVERY row, reachable by TalkBack. `toggleFlag` over a
 * local id is a silent nothing on IMAP (`imapTarget` gives back null, no row is touched, the star
 * never fills) and an "Action failed" banner on JMAP. So the row does not get one: an affordance
 * that cannot do what it draws is the lie this whole volet exists to end (WYSIWYG). Drawing a dead
 * star instead would leave the same tap, just quieter.
 */
internal fun showsFavouriteStar(id: String?): Boolean = !isLocalDraftRow(id)

/**
 * The keys "Select all" may take: the folder's own, minus the server rows a local draft is standing
 * in for and which are therefore NOT on screen.
 *
 * ⛔ This is the destructive half of #126 re-opened by our own hiding. `selectableIds` pages the
 * `emails` table, where a replaced server draft is still filed — so without this the counter says
 * N+1 and "Delete" moves to the Trash a message the user was never shown. The rule of #126 is
 * exactly that: a bulk action never touches a row that is not on screen.
 *
 * ⭐ It subtracts by id whether or not the row was really hidden (see [hidesServerRow], which spares
 * a conversation representative). The two can therefore disagree in conversation view, and the
 * direction of the disagreement is the point: a replaced id left OUT of a selection is one message
 * not deleted, which the user can redo; a hidden row left IN is a deletion she never saw. Aligning
 * this on [hidesServerRow] was considered and refused: the gap is the chosen meaning, not a slip
 * left standing.
 */
internal fun selectableKeysMinusHidden(folderKeys: List<EmailKey>, replacedServerIds: Set<String>): List<EmailKey> =
    if (replacedServerIds.isEmpty()) folderKeys else folderKeys.filter { it.emailId !in replacedServerIds }

/**
 * What `local_drafts` contributes to the list currently selected — nothing at all outside the Drafts
 * folder of the account those rows belong to.
 *
 * A flow of its own, with its inputs as parameters, so a JVM test can RUN it: `InboxViewModel` is an
 * `AndroidViewModel` and cannot be built here. [localDrafts] is the store, handed in.
 *
 * ⛔ The scope is deduped BEFORE the store is subscribed. The role map re-emits for reasons that
 * have nothing to do with this folder, and without the guard each one would re-subscribe the DAO and
 * hand the list a new, equal set of rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun localDraftRowsFlow(
    currentAccountId: Flow<String?>,
    selectedMailboxId: Flow<String?>,
    folderRoles: Flow<Map<Pair<String, String>, String>>,
    unreadOnly: Flow<Boolean>,
    localDrafts: (String) -> Flow<List<LocalDraftEntity>>,
): Flow<LocalDraftRows> =
    combine(currentAccountId, selectedMailboxId, folderRoles, unreadOnly) { accountId, mailboxId, roles, unread ->
        if (showsLocalDrafts(accountId, mailboxId, roles, unread)) accountId!! to mailboxId!! else null
    }
        .distinctUntilChanged()
        .flatMapLatest { scope ->
            if (scope == null) {
                flowOf(LocalDraftRows())
            } else {
                localDrafts(scope.first).map { drafts ->
                    LocalDraftRows(
                        rows = drafts.map { it.toInboxRow(scope.second) },
                        replacedServerIds = replacedServerIds(drafts),
                    )
                }
            }
        }

/**
 * The SERVER drafts the given local rows stand in for.
 *
 * A draft reopened from the server and re-saved with no network writes a local row carrying
 * [LocalDraftEntity.replacesEmailId] and LEAVES the server copy where it is: the original is only
 * destroyed once the upload has landed (`LocalDraftSave`). Both rows on screen is the same draft
 * twice.
 */
internal fun replacedServerIds(drafts: List<LocalDraftEntity>): Set<String> =
    drafts.mapNotNullTo(mutableSetOf()) { it.replacesEmailId }

/**
 * True when [row] is the server copy a local draft already stands in for, i.e. must not be drawn.
 *
 * ⛔ **Only a row that stands for itself.** Conversation view is the DEFAULT and the Drafts folder
 * is paged through it like any other, so a row here can be the REPRESENTATIVE of a group
 * ([InboxRow.threadCount] > 1). Hiding a representative hides its whole conversation: a server draft
 * that is perfectly present, plus every message filed with it, listed nowhere at all. The two errors
 * are not comparable — the same draft shown twice is confusing and recoverable, a draft that cannot
 * be found is not — so the duplicate is the one we accept.
 */
internal fun hidesServerRow(row: InboxRow, replaced: Set<String>): Boolean =
    row.threadCount == 1 && row.email.id in replaced

/**
 * The order local rows must be handed to `insertHeaderItem` in.
 *
 * Each call prepends ONE item, so a newest-first list applied front to back would leave the OLDEST
 * draft on top. Oldest in first, newest on top.
 */
internal fun headerInsertionOrder(rows: List<InboxRow>): List<InboxRow> = rows.reversed()

/**
 * The paged list as the Drafts folder must read it: the phone's own drafts on top, and the server
 * rows they replace taken out.
 *
 * ⛔ **Applied STRICTLY DOWNSTREAM of `cachedIn`, in a property only `InboxScreen` collects.**
 * `MessageScreen` pages `InboxViewModel.pagedEmails` literally, so a local id inserted upstream
 * lands in the READER's pager: a sideways swipe opens a page that cannot load, and a swipe-delete
 * from there calls `repo.delete`, which on IMAP deletes nothing and returns `trash` — "deleted",
 * with an Undo, over an untouched message. Paging re-applies downstream operators per collector,
 * which is what makes a per-screen view legitimate at all.
 *
 * ⛔ **The caller combines, it does not map.** The Room `PagingSource` behind `pagedEmails` observes
 * `EmailEntity` and `SnoozedEntity` only, never `local_drafts`: nothing would wake the list after a
 * save with no network, which is the very case this exists for. The wake-up is the combine's.
 *
 * [TerminalSeparatorType.SOURCE_COMPLETE] rather than the default: the header belongs to the phone,
 * not to the remote stream, so it must not wait on a `RemoteMediator` prepend saying it has reached
 * the end.
 */
internal fun withLocalDrafts(paged: PagingData<InboxRow>, local: LocalDraftRows): PagingData<InboxRow> {
    var data = paged.filter { row -> !hidesServerRow(row, local.replacedServerIds) }
    for (row in headerInsertionOrder(local.rows)) {
        data = data.insertHeaderItem(terminalSeparatorType = TerminalSeparatorType.SOURCE_COMPLETE, item = row)
    }
    return data
}

/**
 * One row of `local_drafts` as the list draws it: the phone's own id, the folder it is shown in, and
 * the text the user typed.
 *
 * `$draft` is set so the existing "(Draft)" chip follows on its own, and `$seen` so the row is not
 * drawn bold — a draft the user has just written is not unread mail. [InboxRow.threadCount] is 1 and
 * the row is not expandable: a local draft is in no conversation the cache knows about.
 */
internal fun LocalDraftEntity.toInboxRow(mailboxId: String): InboxRow = InboxRow(
    email = Email(
        id = id,
        accountId = accountId,
        mailboxId = mailboxId,
        subject = subject,
        preview = localDraftPreview(textBody),
        receivedAt = Instant.ofEpochMilli(updatedAtMillis).toString(),
        to = localDraftAddresses(toAddresses),
        cc = localDraftAddresses(cc),
        bcc = localDraftAddresses(bcc),
        keywords = mapOf("\$draft" to true, "\$seen" to true),
    ),
    threadCount = 1,
    unread = false,
)

/** A comma-separated address column as the list shows it, blanks dropped. */
private fun localDraftAddresses(column: String?): List<EmailAddress> =
    column.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { EmailAddress(email = it) }

/** The preview of a draft's body: whitespace collapsed, cut to [PREVIEW_CHARS]. */
private fun localDraftPreview(textBody: String): String =
    textBody.replace(Regex("""\s+"""), " ").trim().take(PREVIEW_CHARS)
