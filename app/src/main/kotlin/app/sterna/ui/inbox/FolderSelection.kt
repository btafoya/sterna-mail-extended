package app.sterna.ui.inbox

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.CrossAccountMove
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.jmap.model.Mailbox
import java.text.Collator

/**
 * True when the folder currently on screen is no longer part of the account's folder list,
 * so the list must fall back to the Inbox (Codeberg #89).
 *
 * Two ways a folder vanishes underneath the list, one rule for both:
 *  - it was deleted from the drawer here (its rows leave the cache while the undo window runs);
 *  - it was deleted from another client, and the next folder sync dropped it from the cache.
 *
 * Either way the app would otherwise stay parked in a folder that does not exist, header and
 * all — a displayed state with nothing behind it.
 *
 * Guards, deliberately conservative — this must never bounce a user out of a folder that is
 * merely not loaded yet:
 *  - no folder selected ([selectedMailboxId] null, i.e. the unified inbox): nothing to check;
 *  - an EMPTY folder list is "not known yet", not "the folder is gone": first launch, an
 *    account switch before the new account's folders are cached, a cleared cache.
 */
internal fun selectionIsGone(selectedMailboxId: String?, mailboxes: List<Mailbox>): Boolean {
    if (selectedMailboxId == null) return false
    if (mailboxes.isEmpty()) return false
    return mailboxes.none { it.id == selectedMailboxId }
}

/**
 * True when the folder currently on screen is one the user can no longer REACH, so the list must
 * fall back to the Inbox. Two ways in, one answer:
 *
 *  - it is GONE from the account's folder list ([selectionIsGone], #89) — deleted from the drawer
 *    here, or from another client and dropped by the next folder sync;
 *  - it is still in the list but HIDDEN, because the account asks for its subscribed folders only
 *    ([visibleFolders], #174) and this one is not subscribed, carries no role, and has nothing
 *    visible under it.
 *
 * The second half REVERSES what #174 first shipped ("hidden is not deleted"), on the bench pass
 * of 2026-08-25 and on rule 3, WYSIWYG: with the box ticked, the screen kept the header and the
 * messages of a folder that no list — neither the drawer nor "move to" — would still offer. It was
 * also a trap, because leaving that folder was a one-way trip until the box came back off.
 *
 * Both lists are judged, never the visible one alone, and [selectionIsGone]'s two conservative
 * guards are what make this safe to run on every setting change as well as on every folder list.
 * Its empty-list guard now covers a second case: a VISIBLE list that came out empty (a server
 * reporting nothing subscribed, an account whose folders are not cached yet) means "not known
 * yet", never "the folder on screen was taken away".
 *
 * This is the SELECTION's rule and no one else's. A hidden folder is still a real folder
 * everywhere else: `InboxViewModel.subfolderIdsOf` must still find its children when a parent is
 * deleted (a child left behind is mail nobody can reach), and the reader still names it in a path.
 */
internal fun selectionIsUnreachable(
    selectedMailboxId: String?,
    mailboxes: List<Mailbox>,
    showOnlySubscribed: Boolean,
): Boolean =
    selectionIsGone(selectedMailboxId, mailboxes) ||
        selectionIsGone(selectedMailboxId, visibleFolders(mailboxes, showOnlySubscribed))

/**
 * The folder a tapped notification may switch the list to, or null to leave the list where it is —
 * [NotificationFolderSwitch.resolve]'s answer, put through the one rule that outranks it: **one
 * cannot be parked in a folder no list offers, by any route** (#174, arbitration of 2026-08-25,
 * rule 3 — WYSIWYG).
 *
 * #91 opens the list behind the message on the message's own folder, so Back lands where the mail
 * is. When the account asks for its subscribed folders only and that folder is not one of them,
 * that route would drop the user exactly where [selectionIsUnreachable] exists to say nobody may
 * be: a header naming a folder the drawer does not list and "move to" does not offer, with no way
 * back in. So the switch is dropped and the list stays where it was — the MESSAGE still opens, it
 * is the reader that opens it, not this.
 *
 * [mailboxes] is the WHOLE list, and so is the one [NotificationFolderSwitch.resolve] was given:
 * handing that one a filtered list would make it answer null for the wrong reason (folder unknown)
 * on folders that are merely hidden, and its "not in the list" branch means deleted. It is the
 * RESULT that is judged here, never the input.
 *
 * The judgement is [selectionIsUnreachable]'s, the same function the fallback runs, deliberately:
 * two rules would disagree the day one of them is edited, and this route would put back exactly
 * what the other one takes away.
 */
internal fun notificationFolderToShow(
    resolvedMailboxId: String?,
    mailboxes: List<Mailbox>,
    showOnlySubscribed: Boolean,
): String? {
    val target = resolvedMailboxId ?: return null
    if (selectionIsUnreachable(target, mailboxes, showOnlySubscribed)) return null
    return target
}

/** Lead order of the standard (special-use) folders in a move-to-folder picker; everything
 *  else — a folder the user made on their own server — shares the last rank (#25). */
private fun roleRank(role: String?): Int = when (role) {
    "inbox" -> 0
    "drafts" -> 1
    "sent" -> 2
    "junk" -> 3
    "archive", "all" -> 4
    "trash" -> 5
    else -> 6
}

/**
 * The folders a move-to-folder picker offers: [mailboxes] minus the one the message(s) are
 * already filed under, minus what [showOnlySubscribed] hides ([visibleFolders], #174), standard
 * folders first in a fixed order, then the user's own folders BY PATH — parent before child,
 * siblings in alphabetical order.
 *
 * Custom folders used to come out in the order the server sent them (an IMAP `LIST`, a JMAP
 * `sortOrder`), which is no order at all on screen: "Zulu" before "Alpha". The path is compared
 * SEGMENT BY SEGMENT, shortest prefix first, so a child always follows its own parent and no
 * root folder can slip between the two — a whole-path string compare would let "Work_Notes" do
 * exactly that, since "_" sorts before "/".
 *
 * Names go through a [Collator], never [String.compareTo]: comparing UTF-16 units files "Über"
 * after "Zoo" and all of Cyrillic after all of Latin, in an app translated into nine languages.
 * The default follows the device locale; the parameter exists so a test can pin one.
 *
 * [showOnlySubscribed] is a parameter here rather than a filter applied by the CALLER, and that
 * is the whole reason it exists: [mailboxes] is passed twice — once as the set of targets, once as
 * the context the ancestors are resolved against — and only the TARGETS may be filtered. Hand this
 * function a pre-filtered list and a child whose parent is hidden loses a segment of the path shown
 * under its name (#109), which is the one thing that path is there to prevent.
 *
 * Each folder's ancestors are resolved ONCE, before the sort ([ancestorsOf], decorate/sort/
 * undecorate), not from inside the comparator: a resolution walks a map rebuilt over the whole
 * folder list, and this function runs in composition without `remember`. They are resolved
 * against the full [mailboxes], not against the filtered targets — a child's path must not lose
 * a segment just because its parent is the folder being moved out of.
 *
 * [mailboxes] must be ONE account's folder list — the account of what is being moved. Mailbox
 * ids collide across accounts of the same server (#92), so a picker fed a sibling account's
 * folders would name a folder the move cannot reach (or, worse, a different one).
 *
 * A null [currentMailboxId] excludes nothing: the folder isn't known (unified inbox, a message
 * whose body fetch dropped it), and hiding a guess would be worse than listing one no-op.
 *
 * Shared by the list's selection-bar picker and the reader's overflow entry (#73), so both
 * offer the same folders in the same order.
 */
internal fun moveTargets(
    mailboxes: List<Mailbox>,
    currentMailboxId: String?,
    showOnlySubscribed: Boolean = false,
    collator: Collator = Collator.getInstance(),
    ancestorsOf: (Mailbox, List<Mailbox>) -> List<String> = ::mailboxAncestors,
): List<Mailbox> =
    visibleFolders(mailboxes, showOnlySubscribed).filter { it.id != currentMailboxId }
        .map { it to (ancestorsOf(it, mailboxes) + it.name) }
        .sortedWith(
            compareBy<Pair<Mailbox, List<String>>> { roleRank(it.first.role) }
                .thenComparator { a, b -> comparePath(a.second, b.second, collator) },
        )
        .map { it.first }

/**
 * Two folder paths compared segment by segment, the shorter prefix first — so "Work" leads
 * "Work/2026", which leads "Work/Alpha".
 *
 * Comparing the joined paths as one string instead would sort on whatever separator was used,
 * and every separator loses to some legal folder name: with "/", a sibling named "Work_Notes"
 * lands between "Work" and "Work/2026" and reads as a child of nothing.
 */
private fun comparePath(a: List<String>, b: List<String>, collator: Collator): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val order = collator.compare(a[i], b[i])
        if (order != 0) return order
    }
    return a.size - b.size
}

/**
 * The ancestors of [mailbox], outermost first — empty when the folder sits at the account root.
 *
 * Two servers, one answer:
 *  - **JMAP** ids are opaque, so the tree is the `parentId` chain and the path is the chain of
 *    NAMES (RFC 8621 §2);
 *  - **IMAP** has no such field: [Mailbox.id] IS the full path the server named
 *    ("INBOX.ProjectA.Done") and [Mailbox.name] only its last segment, so the ancestors are
 *    read straight off the id.
 *
 * The IMAP branch never resolves the ancestors as mailboxes: a parent marked `\Noselect` is
 * dropped from the folder list entirely, and "ProjectA" not being browsable does not stop
 * "ProjectA.Done" from living inside it.
 *
 * [mailboxes] must be ONE account's folder list — the same rule as [moveTargets]: mailbox ids
 * collide across accounts of the same server (#92), and a chain walked through the wrong
 * account's folders would spell out a path nobody has.
 */
internal fun mailboxAncestors(mailbox: Mailbox, mailboxes: List<Mailbox>): List<String> {
    val (chain, _) = parentNameChain(mailbox, mailboxes)
    if (chain.isNotEmpty()) return chain
    val (parentPath, delimiter) = imapParentPath(mailbox) ?: return emptyList()
    return parentPath.split(delimiter).filter { it.isNotEmpty() }
}

/**
 * The path a SERVER-SIDE rule must name to file mail into [mailbox] — a value, not a label: it
 * goes on the wire (a Sieve `fileinto`), so it is never translated and never sanitized, and it
 * keeps the exact spelling the server used. Sieve names a subfolder by its whole path, so a
 * leaf on its own ("Done") names the wrong folder, or none, as soon as two folders share it.
 *
 * Null when this folder cannot be named with certainty — a nested folder whose parent is not in
 * the list, a `parentId` loop, or a name that itself contains [JMAP_PATH_SEPARATOR], which no
 * `fileinto` syntax can tell from a folder boundary. A folder that cannot be named is left out
 * of the rule editor's choices: not offering it is visible, filing mail somewhere nobody chose
 * is not — the very failure this function exists to end.
 *
 * Only the JMAP branch runs today: server-side rules ARE JMAP-only
 * (`MailRepository.loadFilterRules` answers `Unsupported` for every IMAP account), and this
 * function has one caller, the rule editor. The IMAP branch is written and tested against the
 * day IMAP filters exist; nothing has ever sent it on a wire.
 */
internal fun mailboxFilePath(mailbox: Mailbox, mailboxes: List<Mailbox>): String? {
    val (chain, rooted) = parentNameChain(mailbox, mailboxes)
    if (!rooted) return null
    if (chain.isNotEmpty()) {
        val segments = chain + mailbox.name
        if (segments.any { JMAP_PATH_SEPARATOR in it }) return null
        return segments.joinToString(JMAP_PATH_SEPARATOR)
    }
    if (imapParentPath(mailbox) != null) return mailbox.id
    return mailbox.name
}

/**
 * The line shown UNDER a folder's name in a picker so two folders with the same leaf can be
 * told apart (#109): "ProjectA.Done" and "ProjectB.Done" both read "Done" otherwise, while the
 * drawer's tree has always shown the difference. Null when there is nothing to add — a folder
 * at the root, or a standard folder, whose localized name ("Trash") is already unique in the
 * account and would only be muddied by the raw "INBOX" it may hang under.
 *
 * The ancestors go through [stripBidiAndControls]: this is the first place a folder PATH is
 * displayed, and the IMAP layer only ever filtered the leaf it expected to show (#101).
 */
internal fun mailboxPathLabel(mailbox: Mailbox, mailboxes: List<Mailbox>): String? {
    val role = mailbox.role
    if (role != null && role in TRANSLATED_ROLES) return null
    val ancestors = mailboxAncestors(mailbox, mailboxes)
        .map(::stripBidiAndControls)
        .filter { it.isNotBlank() }
    if (ancestors.isEmpty()) return null
    return elideOutermost(ancestors)
}

/**
 * Separator assumed between two segments of a JMAP mailbox path on the wire.
 *
 * NOT established: JMAP itself has no path syntax (a mailbox is an opaque id and a parent), so
 * how a server's Sieve spells a nested mailbox in `fileinto` is the server's own convention,
 * and "/" is the common one. Unverified against a live server so far — if a server disagrees,
 * this constant is the single place to change.
 */
private const val JMAP_PATH_SEPARATOR = "/"

/** Separator between two ancestors on screen — spaced, so it cannot be read as part of a name. */
private const val PATH_LABEL_SEPARATOR = " / "

private const val ELLIPSIS = "…"

/** How much of a parent path a picker row shows before its outermost ancestors are dropped. */
private const val PATH_LABEL_MAX_CHARS = 40

/**
 * Shorten a parent path from the OUTSIDE in, dropping whole ancestors and marking the cut.
 *
 * Which end to cut is the whole point: the nearest ancestor is what tells one "Done" from
 * another, and eliding at the end ("ProjectA / Sou…") would throw away exactly the segment the
 * row exists to show — the reported bug, one line lower. Compose's own start/middle elision
 * (`TextOverflow.StartEllipsis`) does not exist in the Compose version this app builds against,
 * so the cut is made on the string, where it is also testable.
 *
 * When a SINGLE ancestor is longer than the budget there is no outer folder left to drop, and
 * cutting either end picks a favourite: "ProjectAlpha - Archives" and "ProjectBeta - Archives"
 * differ only at the front, "Archives 2025" and "Archives 2026" only at the back. So that last
 * name is cut in the MIDDLE, which keeps both ends and therefore both kinds of discriminant.
 */
private fun elideOutermost(ancestors: List<String>): String {
    var kept = ancestors
    while (kept.size > 1 &&
        kept.joinToString(PATH_LABEL_SEPARATOR).length + ELLIPSIS.length > PATH_LABEL_MAX_CHARS
    ) {
        kept = kept.drop(1)
    }
    val dropped = kept.size < ancestors.size
    val budget = if (dropped) PATH_LABEL_MAX_CHARS - ELLIPSIS.length else PATH_LABEL_MAX_CHARS
    val text = elideMiddle(kept.joinToString(PATH_LABEL_SEPARATOR), budget)
    return if (dropped) ELLIPSIS + text else text
}

/** [text] shortened to [maxChars] by taking out its middle, keeping both ends. */
private fun elideMiddle(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val kept = maxChars - ELLIPSIS.length
    return text.take(kept - kept / 2) + ELLIPSIS + text.takeLast(kept / 2)
}

/**
 * The `parentId` chain of names above [mailbox], outermost first, and whether the walk actually
 * reached a folder at the root. Empty (and rooted) for IMAP, which has no such field, and for a
 * JMAP folder already at the root.
 *
 * The walk stops short — and says so — on a parent the list does not hold, or on a `parentId`
 * loop. What it gathered is still worth SHOWING, but it no longer spells a whole path, so it
 * must never be sent as one: a path cut halfway names a different folder, silently.
 */
private fun parentNameChain(
    mailbox: Mailbox,
    mailboxes: List<Mailbox>,
): Pair<List<String>, Boolean> {
    val parentId = mailbox.parentId ?: return emptyList<String>() to true
    val byId = mailboxes.associateBy { it.id }
    val chain = ArrayDeque<String>()
    val seen = mutableSetOf(mailbox.id)
    var parent = byId[parentId]
    while (parent != null) {
        if (!seen.add(parent.id)) return chain.toList() to false
        chain.addFirst(parent.name)
        val next = parent.parentId ?: return chain.toList() to true
        parent = byId[next]
    }
    return chain.toList() to false
}

/**
 * The parent path an IMAP id carries, with the delimiter that separates it from the leaf —
 * null when the id is not a path at all (a folder at the root, or a JMAP id, which is opaque).
 *
 * The delimiter is read off the id, as the character sitting just before the leaf, rather than
 * guessed by looking for "/" or ".": a folder legitimately named "Foo/Bar" on a dot-delimited
 * server would otherwise be cut in the middle. An id that does not end with the folder's own
 * name claims nothing — better no path than an invented one.
 */
private fun imapParentPath(mailbox: Mailbox): Pair<String, Char>? {
    val id = mailbox.id
    val name = mailbox.name
    if (name.isEmpty() || id.length <= name.length + 1 || !id.endsWith(name)) return null
    val delimiter = id[id.length - name.length - 1]
    if (delimiter != '/' && delimiter != '.') return null
    return id.take(id.length - name.length - 1).takeIf { it.isNotEmpty() }?.let { it to delimiter }
}

/** The roles [mailboxDisplayName] answers with a localized label — keep the two in step. */
private val TRANSLATED_ROLES = setOf(
    "inbox", "archive", "drafts", "sent", "junk", "trash", "all", "flagged", "important",
)

/**
 * Drop control characters and Unicode bidi overrides from a path segment about to be shown.
 * The IMAP layer applies the same filter to the folder NAME it decodes (#101) but deliberately
 * not to the path, which was an identifier until this row started displaying it. Duplicated
 * rather than shared because the original is internal to `:core:imap`, which the app module
 * does not depend on.
 */
private fun stripBidiAndControls(s: String): String = s.filterNot { c ->
    val code = c.code
    (code in 0x00..0x08) || (code in 0x0B..0x1F) || (code in 0x7F..0x9F) ||
        (code in 0x202A..0x202E) || (code in 0x2066..0x2069) || code == 0x200F || code == 0x200E
}

/**
 * One row of a move-to-folder picker, ALREADY PAINTED: the folder itself, the name as the row
 * shows it, and the parent path line under it (null when there is none).
 */
internal data class FolderPickerRow(val folder: Mailbox, val name: String, val path: String?)

/**
 * The rows a move-to-folder picker still shows once [query] is typed into its filter field
 * (#182): an account with hundreds of folders stacked them all in one scroller, with nothing to
 * narrow them down.
 *
 * It FILTERS and never reorders, and that is the promise made on the thread: a fuzzy match
 * needs a score, a score needs a sort, and a sort moves the first row for a reason nothing on
 * screen explains — right under a finger about to file mail somewhere. The output is a
 * SUBSEQUENCE of the input, by construction, so the picker's own order ([moveTargets]: standard
 * folders first, then the user's own by path) is what the user still reads.
 *
 * The rows arrive PAINTED because the screen is the only place that can paint them: the name
 * goes through `mailboxDisplayName`, which is `@Composable`, and matching the raw [Mailbox.name]
 * would have someone type "Corb" and be answered nothing, because the row underneath is spelled
 * "Trash" in the model and "Corbeille" on screen.
 *
 * The path compared is [mailboxPathLabel]'s ELIDED path, the very string under the name. An
 * ancestor that was replaced by "…" is therefore not reachable — an assumed limit, not a defect:
 * WYSIWYG, a row must never surface for text the screen does not show.
 *
 * Case is folded by [String.contains]'s own `ignoreCase`, never by a fold the DEFAULT LOCALE
 * decides — `lowercase(Locale.getDefault())`, or Java's `toLowerCase()`. In Turkish "I" folds to
 * "ı", so an account's Inbox would stop answering "inbox" on a Turkish phone and only there.
 * (Kotlin's argument-less `lowercase()` is root-locale and would be safe; it is the locale-taking
 * overload that is the trap, and it is one keystroke away.)
 * Diacritics are NOT folded ("Über" does not answer "Uber") — KISS, settled with the same reply.
 *
 * One rule and no special case for a blank query: a typed space filters on a space, which is
 * exactly what the field shows.
 */
internal fun filterFolderRows(rows: List<FolderPickerRow>, query: String): List<FolderPickerRow> {
    if (query.isEmpty()) return rows
    return rows.filter { row ->
        row.name.contains(query, ignoreCase = true) ||
            row.path?.contains(query, ignoreCase = true) == true
    }
}

// ── a move into ANOTHER account's folder (#189) ───────────────────────────────────────────────

/**
 * The account whose folders the move-to-folder picker lists: the one the user chose on the
 * picker's account row, else the OWNER — the account of the message (reader) or of the selection
 * (list). The list handed to [moveTargets] is then that ONE account's, which is the invariant the
 * picker lives by (#92: mailbox ids collide between accounts of the same server).
 */
internal fun pickerAccount(chosen: String?, owner: String?): String? = chosen ?: owner

/**
 * The folder the picker leaves out — the one the message is filed under — but ONLY while the
 * picker lists the owner's own folders. Listing another account's, the id names nothing there, or
 * worse a homonym (#92): a message in A's "Archive" must still be offered B's "Archive".
 */
internal fun pickerExcludedMailbox(chosen: String?, owner: String?, current: String?): String? =
    if (chosen == null || chosen == owner) current else null

/**
 * What the account row records when [picked] is tapped: null for the owner, so that picking the
 * message's own account back is exactly the state the picker opened in (and never a move "between"
 * A and A), the id of any other.
 */
internal fun pickerChoice(picked: String, owner: String?): String? = picked.takeIf { it != owner }

/** The account row's order: the owner first, then the others as the store lists them. */
internal fun pickerAccounts(accounts: List<StoredAccount>, owner: String?): List<StoredAccount> {
    val lead = accounts.firstOrNull { it.id == owner } ?: return accounts
    return listOf(lead) + accounts.filter { it.id != owner }
}

/**
 * Whether the move leaves the message's account at all. False when no account was chosen, or the
 * chosen one is the owner's: the gesture then takes the path it always took (same-account move,
 * Undo included). True otherwise — including an unknown owner with a chosen target, because the
 * user did ask for that account and the repository checks both are still configured.
 */
internal fun isCrossAccountMove(targetAccountId: String?, ownerAccountId: String?): Boolean =
    targetAccountId != null && targetAccountId != ownerAccountId

/** The one banner a multi-message move between accounts ends on. */
internal enum class CrossAccountOutcome { ALL_MOVED, SOME_COPIED_NOT_REMOVED, PARTLY_FAILED, ALL_FAILED }

/**
 * Which banner: a copy whose original STAYED is the most important truth to tell (the user now has
 * a duplicate, and would otherwise believe the message moved), so it wins over a failure; then a
 * batch where nothing was CONFIRMED written; then a partial failure, which is also where a message
 * set aside because it belongs to another account ([skipped]) lands; "all moved" only when nothing
 * else is true.
 *
 * "NOTHING WAS CONFIRMED WRITTEN" IS ALL A BATCH OF [CrossAccountMove.Failed] SAYS, and this
 * rule must not claim more: a lost answer to an APPEND and an import account B did store look the
 * same from here, so a copy MAY exist on B. What is certain is that no original left A. The
 * sentence served — "Couldn't complete the action." — says that much and nothing more.
 *
 * "SOME messages couldn't be processed" IS A COUNT, AND IT WAS AN EXISTENTIAL TEST. `any {
 * Failed }` is true for one failure out of forty and true for forty out of forty, so a batch where
 * nothing was confirmed written ended on a line saying part of it had gone through — on the one
 * destroying gesture with no Undo, where the user then has to work out by hand which half of a
 * selection the screen no longer shows is on which account. The neighbour one file over has
 * compared cardinalities from the start (`bulkOutcome`, in `BulkOutcome.kt`: `failed >= attempted
 * -> TOTAL`), and `MailBySenderViewModel` already reuses these two very sentences for this very
 * distinction.
 *
 * THE ORDER OF THE TWO FAILURE BRANCHES IS THE DECISION. The existential one must come AFTER,
 * or it catches every total failure on the way past and this can never be raised — it compiles,
 * every name still reads the same, and the banner goes back to lying. [CrossAccountPickerTest]
 * runs both directions.
 *
 * AND [results] MUST BE NON-EMPTY. `all {}` is true of nothing: without that guard a batch that
 * wrote no verdict at all — the race where nothing was attempted, already ruled out of scope —
 * would flip from "0 messages moved" to "couldn't complete the action". It keeps shipping exactly
 * as it did.
 */
internal fun crossAccountBanner(results: List<CrossAccountMove>, skipped: Int): CrossAccountOutcome = when {
    results.any { it is CrossAccountMove.CopiedNotRemoved } -> CrossAccountOutcome.SOME_COPIED_NOT_REMOVED
    results.isNotEmpty() && results.all { it is CrossAccountMove.Failed } -> CrossAccountOutcome.ALL_FAILED
    skipped > 0 || results.any { it is CrossAccountMove.Failed } -> CrossAccountOutcome.PARTLY_FAILED
    else -> CrossAccountOutcome.ALL_MOVED
}

/**
 * Which keys of a selection's move between accounts go BACK under the tick (#189) — pure, and
 * EXECUTED by [CrossAccountPickerTest], because this is the one destroying gesture with no Undo:
 * what it silently drops cannot be taken back, and what it silently gives back can be sent twice.
 *
 * Given back: every key of [outcomes] whose verdict is NEITHER [CrossAccountMove.Moved] NOR
 * [CrossAccountMove.CopiedNotRemoved] — that is, everything that wrote NOTHING on account B — and
 * whose row is still in [cached], in [outcomes]' own iteration order.
 *
 * THE CRITERION IS "NOTHING ON B", NOT "STAYED ON A". A [CrossAccountMove.CopiedNotRemoved]
 * stayed on A too — its original is still there, that IS the verdict — and it is on B all the
 * same. Read as "did not leave A" this rule would hand it back, and the next tap would copy it to
 * B a second time, with no Undo to take either copy away.
 *
 * INDEXED ON THE VERDICT, NEVER ON A CLASS OF REFUSAL. The first shape of this rule keyed
 * the give-back on the rows the numbering split had refused, and it was empty by construction for
 * the OTHER refusal of this path — the one raised at the read, which comes back as an ordinary
 * `Failed`: those lines did not travel, did not come back ticked, and the user learned neither
 * which ones had failed nor that any had. Read off the verdict, a refusal is given back whatever
 * raised it, with no list of refusal kinds to be kept in step.
 *
 * AND [cached] IS THE OTHER HALF OF THE RULE — this does NOT promise that every refusal comes
 * back. A key absent from [cached] is never given back (a row the list does not draw must not be
 * ticked), so a verdict raised BECAUSE the key is missing from that very set can never return
 * here: the caller's `Failed("no cached row for …")` is empty by construction for this rule,
 * exactly as the first shape was for the read's `Failed`. Neither does this see a refusal that
 * returns before any verdict is written at all. Whether the caller's promise still holds is a
 * question to ask again AT THE CALLER each time a refusal is added, not one this answers.
 *
 * The price is assumed: a batch that failed because the phone is offline now hands the whole
 * selection back, which is exactly what the caller's KDoc has been promising all along.
 *
 * What is lost if this is undone:
 *  - a give-back that includes [CrossAccountMove.CopiedNotRemoved] hands back a message that IS
 *    already on B: the second tap copies it there a second time, with no Undo to take it back;
 *  - a give-back that includes a key absent from [cached] ticks a row the screen does not show;
 *  - a give-back keyed on one class of refusal drops every other one in silence — a move that
 *    quietly dropped part of a selection, and a screen that agrees with it.
 */
internal fun crossAccountGiveBack(
    outcomes: Map<EmailKey, CrossAccountMove>,
    cached: Set<EmailKey>,
): Set<EmailKey> = outcomes
    .filterValues { it !is CrossAccountMove.Moved && it !is CrossAccountMove.CopiedNotRemoved }
    .keys.filterTo(LinkedHashSet()) { it in cached }

/** "Account › Folder", the way the banners name where a message went. */
internal fun crossAccountTargetLabel(account: String, folder: String): String = "$account › $folder"

/**
 * The line ONE message's move between accounts leaves behind (#189) — chosen by the verdict, and
 * by nothing else.
 *
 * Pure and EXECUTED by a test, because which verdict says "moved" and which says "copied, the
 * original stayed" is the whole difference between a move and a duplicate the user still has to
 * deal with, and there is no Undo behind either: a banner reading "Moved to B › Archive" over a
 * [CrossAccountMove.CopiedNotRemoved] sends her away from a message that never left A. It is also
 * the most frequent verdict on IMAP, where a copy the server did not name may not be findable
 * back.
 *
 * A failure carries no throwable to read "offline" off — [CrossAccountMove.Failed] is a verdict,
 * not an exception — so the phone's link is judged by the caller and handed in as [online], the
 * same shape as [actionFailureMessage].
 *
 * The formatting arguments are the caller's: "moved" takes account and folder, "copied" takes
 * account, folder and the account the original stayed on, and a failure takes none.
 */
@StringRes
internal fun crossAccountMessageRes(move: CrossAccountMove, online: Boolean): Int = when (move) {
    CrossAccountMove.Moved -> R.string.status_moved_to_account
    is CrossAccountMove.CopiedNotRemoved -> R.string.status_copied_not_removed
    is CrossAccountMove.Failed -> if (online) R.string.status_action_failed else R.string.status_action_offline
}

/**
 * The plural a SELECTION's banner counts with, for the two outcomes that count something (#189):
 * how many were moved, or how many were copied with their originals left behind.
 *
 * Null for [CrossAccountOutcome.PARTLY_FAILED] and [CrossAccountOutcome.ALL_FAILED], which count
 * nothing and are plain strings —
 * [crossAccountSelectionFailedRes]. Two functions and not one: a plural id and a string id are
 * both an `Int` and neither `getQuantityString` nor `getString` accepts the other's, so the split
 * is the resources API's, not a taste. Same reason as [mailboxRoleNameRes] for the nullability.
 */
@PluralsRes
internal fun crossAccountSelectionPlural(outcome: CrossAccountOutcome): Int? = when (outcome) {
    CrossAccountOutcome.ALL_MOVED -> R.plurals.status_selection_moved
    CrossAccountOutcome.SOME_COPIED_NOT_REMOVED -> R.plurals.status_selection_copied_not_removed
    CrossAccountOutcome.PARTLY_FAILED, CrossAccountOutcome.ALL_FAILED -> null
}

/**
 * The plain line a SELECTION's banner ends on when the batch did not go through whole — null for
 * the two outcomes that COUNT ([crossAccountSelectionPlural]).
 *
 * TWO OUTCOMES, TWO SENTENCES, AND NEVER ONE BRANCH FOR BOTH. "Some messages couldn't be
 * processed" over a batch where NOTHING moved tells the user part of her selection travelled when
 * none of it did, and this gesture has no Undo to find out with. Merging the two branches here
 * compiles and reads fine, which is why the two expected ids are written out by hand in
 * [CrossAccountPickerTest].
 *
 * `status_action_failed` is reused rather than translated: it exists in all nine languages and
 * says exactly this. Same reuse, for the same distinction, as `MailBySenderViewModel`'s
 * `deleteMessageRes`, whose [BulkOutcome] split this one mirrors.
 */
@StringRes
internal fun crossAccountSelectionFailedRes(outcome: CrossAccountOutcome): Int? = when (outcome) {
    CrossAccountOutcome.PARTLY_FAILED -> R.string.status_action_partly_failed
    CrossAccountOutcome.ALL_FAILED -> R.string.status_action_failed
    CrossAccountOutcome.ALL_MOVED, CrossAccountOutcome.SOME_COPIED_NOT_REMOVED -> null
}

/**
 * The translated name of a folder with a role, or null for a folder called by its own name — the
 * one rule behind `mailboxDisplayName` (composition) and the banners' `mailboxLabel` (ViewModel).
 */
@StringRes
internal fun mailboxRoleNameRes(role: String?): Int? = when (role) {
    "inbox" -> R.string.folder_inbox
    "archive" -> R.string.folder_archive
    "drafts" -> R.string.folder_drafts
    "sent" -> R.string.folder_sent
    "junk" -> R.string.folder_junk
    "trash" -> R.string.folder_trash
    "all" -> R.string.folder_all
    "flagged" -> R.string.folder_flagged
    "important" -> R.string.folder_important
    else -> null
}

/** [mailboxDisplayName] outside composition — how the ViewModel's banners name a folder. */
internal fun mailboxLabel(context: Context, role: String?, name: String): String =
    mailboxRoleNameRes(role)?.let(context::getString) ?: name
