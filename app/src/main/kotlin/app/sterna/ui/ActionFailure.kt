package app.sterna.ui

import app.sterna.R
import app.sterna.core.data.mail.ImapNumberingUnconfirmed

/**
 * What the reader is told when one of ITS OWN actions on the open message did not go through —
 * "report spam", "not spam", "mark unread" (#99).
 *
 * ⛔ NOT the archive, delete and move the reader's bar also offers: those are `InboxViewModel`'s
 * (`SternaApp.kt:514`, `:522`, `:532` hand them to `delete`/`archive`/`moveTo`), the screen is
 * popped, and their word is said on the list by `actionFailureMessage` — see the note at the foot
 * of this comment. The three gestures below are the ones that leave the reader where it is, which
 * is exactly why the surface question arises for them and not for the others.
 *
 * ⭐ WHY THIS IS A FUNCTION AND NOT AN `if` INSIDE THE ViewModel. `MessageViewModel` is an
 * `AndroidViewModel` that reaches for the application container, so it cannot be instantiated in a
 * JVM test (see `ReaderStateResetOnLoadTest` for the same finding) — a decision left inside it is a
 * decision no test can RUN, and a decision nothing runs is one a mutation walks through. The
 * function below takes the string lookup as an argument for exactly that reason: it holds the whole
 * answer, resource id included, and a test executes it.
 *
 * ⛔ THE BRANCH IS ON THE TYPE, never on the text. `ImapNumberingUnconfirmed`'s message names a
 * folder and a numbering, in English, and speaks of a "destroy" for what the user asked to be an
 * archive — matching on `"UIDVALIDITY" in message` would tie the screen to a sentence nobody
 * promised to keep.
 *
 * ⛔ And ONLY that type. `ImapUidValidityChanged` is a different refusal with a different message
 * and is deliberately not caught here; every other failure — a dead network above all — keeps
 * exactly the TEXT it showed before this file existed, because that text is what a bug report is
 * made of. What the reader changes for those is the SURFACE, not the words.
 *
 * ⛔ THE LIST HAS ITS OWN ANSWER AND IT IS NOT THIS ONE. `InboxViewModel`'s swipe and Undo arms go
 * through `actionFailureMessage`, which returns a resource for EVERY failure and never the
 * exception's text at all — a stricter rule than this one, arrived at separately for the offline
 * swipe (`ActionFailureWiringTest`). A refusal is not a transport failure, so it lands on the
 * generic sentence there; `MoveRefusalIsNotOfflineTest` is what holds that, and it is the list's
 * half of this defect. Do not grow a second list-side decision here to sit beside it.
 */
internal fun readerActionFailureText(t: Throwable, string: (Int) -> String): String = when (t) {
    is ImapNumberingUnconfirmed -> string(R.string.status_action_failed)
    else -> t.message ?: t.javaClass.simpleName
}
