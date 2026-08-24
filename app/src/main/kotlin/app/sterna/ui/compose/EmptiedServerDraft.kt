package app.sterna.ui.compose

/*
 * Emptying a draft this composer opened STRAIGHT OFF THE SERVER (#69 × #63) — as a function a JVM
 * test EXECUTES, for `EmptiedLocalDraft.kt`'s reason: `ComposeViewModel` is an `AndroidViewModel`,
 * nothing in this module can instantiate it, so a decision left inside it is a decision no test ever
 * runs.
 *
 * The defect it ends: an empty save took two routes and only ONE of them read the fidelity verdict.
 * A draft held for a server copy (`DraftOpenRoute.LOCAL`) went through `planForEmptiedLocalDraft`,
 * which keeps the copy this phone never read whole and says so; a draft opened from the server went
 * straight to `repo.discardDraft`, which reads nothing and destroys. And what "empty" means is
 * decided by `draftHasContent`, which counts neither an inline image nor a calendar part: a draft
 * written elsewhere, made of one inline image, is DRAWN empty here — the user saves without having
 * typed anything and the original is expunged on the server, in silence.
 */

/**
 * Is the draft on the server KEPT by this emptying? — the twin of [emptiedLocalDraftKeepsServerCopy],
 * one route away, and deliberately not the same function.
 *
 * ⛔ **It is not the same question, which is why it is not the same function.** The local route asks
 * it of a phone-held row that STANDS IN FOR a server draft, so it needs the replaced id too: with
 * nothing behind the row there is no copy to keep and nothing to say. Here the draft being emptied
 * IS the server draft — `saveDraft` has already established that it exists (`original != null`) and
 * that its id is one the server issued (`draftOpenRoute`) — so the id is not a term of the decision,
 * and adding it would be inventing a case that cannot happen. The rule the two share is the one that
 * matters, and it is the reason this exists: [bodyIsLossy] wins.
 *
 * ⭐ **[bodyIsLossy] beats the rule of #69, and only it does.** The verdict says what this composer
 * MANAGED to read of the draft: an HTML body flattened to text, inline images and calendar parts
 * never carried, a read-receipt request whose checkbox came back clear, an attachment not brought
 * down, or a read that failed outright (`reopenedDraftIsLossy`, and the three other places
 * `editingDraftLossy` is armed). The user empties what she was SHOWN; expunging the server copy on
 * the strength of that destroys, for ever, what she was never shown — and on this route she may have
 * been shown an empty screen for a draft that was never empty. The irreversible option loses
 * (arbitration rule 1), and the screen owes her the word
 * `R.string.compose_emptied_draft_server_copy_kept` — the same sentence the other route posts, for
 * the same fact.
 *
 * ⛔ **And no wider than that.** A draft this composer read WHOLE and the user emptied by hand is
 * still destroyed, exactly as before: that is the rule of #69 ("the original is deleted so no empty
 * shell lingers in Drafts, nor reappears on sync"), and turning "she emptied it" into "never destroy
 * anything" would leave an empty shell at the top of Drafts after every hand-emptied save.
 */
fun emptiedServerDraftIsKept(bodyIsLossy: Boolean): Boolean = bodyIsLossy

/**
 * Whose credentials expunge the SERVER draft this composer opened, once it has been emptied and
 * saved: the account it was READ FROM ([openedUnderAccountId]), or null when there is nobody to act
 * for.
 *
 * ⛔ **Never [composingAsAccountId].** The "From" picker offers the identities of EVERY account and
 * switching it resets nothing about the draft: its id, and the numbering that id was frozen under,
 * were both read off the account the composer was opened on. Handed the writing account's
 * credentials — as this route was — the expunge left for another server, and the screen closed as a
 * success either way: on JMAP `destroyAll` spares an id that server never issued, so the emptied
 * draft stays in the first account's Drafts and comes back on the next sync; on IMAP an id is a
 * NUMBER in a folder and the guard compares numbers, so two accounts whose Drafts share a
 * UIDVALIDITY let the expunge through in the WRONG mailbox — and even when the numbering differs
 * and the refusal falls, the look-up has already invalidated the other account's cached Drafts
 * bodies (`MailRepository`, `uidValidity.invalidate`), silently.
 *
 * ⭐ [composingAsAccountId] is a parameter so that the refusal is EXECUTED and not merely read out
 * of the source by a lint: a rule taking the frozen account alone could be re-broken at the call
 * site with no test able to see it. It is never consulted, and that is the whole decision.
 *
 * ⛔ **It is deliberately NOT [credentialsDestroyingReplacedServerDraft]**, though the two answer
 * the same shape. That one is asked of a LEASE — an account recorded when this phone took a local
 * row, null whenever no row is held, which is the ordinary case. This one is asked of a FREEZE
 * taken beside an id read off a server, and null here means the composer opened no server draft at
 * all. Sharing one function would make each call site's argument name a lie about the other, and
 * would cost both routes the rule that each resolves the account in exactly ONE place — the rule
 * that keeps a second look-up (i.e. a fallback) from being written.
 *
 * Null is the safe end, and both roads to it destroy nothing: no server draft was opened, or the
 * account it was opened from was removed from the phone during the edit. ⛔ There is NO fallback —
 * the fallback is the defect. The caller turns the null into the failure banner it already draws,
 * which leaves a draft that is still exactly a draft.
 */
fun <T : Any> credentialsDestroyingEmptiedServerDraft(
    openedUnderAccountId: String?,
    @Suppress("UNUSED_PARAMETER") composingAsAccountId: String?,
    lookup: (String) -> T?,
): T? = openedUnderAccountId?.let(lookup)
