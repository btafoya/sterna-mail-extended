package app.sterna.core.data.mail

/**
 * What a caller of the two destructive halves of a move between accounts (#189) has to say about
 * the IMAP numbering its message id belongs to — and it is a TYPE because `Long?` could not say it.
 *
 * THE DEFECT THIS EXISTS TO CLOSE. One `Long?` carried two incompatible intentions down to
 * `MailRepository`: "I froze nothing, fall back on the folder's record" (every swipe, every
 * notification action, the conversation delete, the reader's single move, an ordinary read, a
 * `.eml` export) and "I froze the numbering of the tick, and the row carried NONE". Both spelled
 * `null`, both answered by `expectedUidValidity ?: recorded` — the folder's own record, which the
 * first iteration of a cross-account loop that meets a renumbering has already realigned. The
 * record was then compared with itself, agreed, and the octets of whatever holds that uid TODAY
 * were copied onto account B while the live message of account A went to the bin. That path has
 * no Undo.
 *
 * So the two intentions are two shapes, and the ONE that could not be said before —
 * [Frozen] carrying `null` — is now sayable and refusable ([numberingToOppose]).
 *
 * CONFINED between `InboxViewModel` and `MailRepository`, deliberately. Below the refusal the
 * value travels as `Long?` exactly as it always did: `ImapMailService.onMailbox` keeps its
 * `expectedUidValidity ?: recorded` fallback, and it keeps it because NONE of its ~30 call sites
 * changes — not because a "frozen but absent" could never reach it.
 *
 * IT CAN REACH IT, and the KDoc used to claim otherwise. `MailRepository.rawSource` resolves the
 * `Email` with `cachedEmail(…) ?: openEmail(…)` BEFORE the refusal, and on a row evicted from the
 * cache — one of the very producers of a `Frozen(null)` — `openEmailImap` FETCHes with no stamp at
 * all, so `onMailbox` selects under `null ?: recorded` on the way in. What this volet guarantees is
 * narrower and is the thing that matters: NOTHING IS WRITTEN ON THE TARGET ACCOUNT before the
 * refusal. It is not "refused before one octet is read".
 *
 * Not a boolean beside the number. A flag costs the same and is forgotten in silence at one
 * call site, and one forgotten site here is a message destroyed.
 */
sealed interface FrozenNumbering {

    /** No tick froze anything: fall back on the folder's record, which is what every ordinary
     *  gesture has always done and must keep doing. Turning THIS into a refusal would fail
     *  100 % of IMAP swipe-deletes for the plain reason that it would throw on every one of them.
     *  (A separate fact, about the OTHER half: `mayDestroyUnderStatedNumbering(null, 42)` is false,
     *  which is why the `?: recorded` fallback downstream has to stay — it is not what makes a
     *  refusal here fatal.) */
    data object NothingFrozen : FrozenNumbering

    /** A tick froze the numbering of the row; [value] is null when the row carried none — a JMAP
     *  row (no numbering by construction), a row cached before the column existed, a row evicted
     *  between the tick and the gesture, or a key ticked in the window before the suspending read
     *  of its stamp came back. On the destructive path there is then nothing to oppose, and
     *  nothing to oppose is a refusal, not a fallback.
     *
     *  And `0` or a negative is "nothing" too, by [UidValidity.stated]'s rule: `UidValidityStore
     *  .record` refuses to write a value at or below 0 and `stated` normalises it to null, so a
     *  number that low is not a numbering anywhere in this app. Handed to the wire it would be the
     *  WEAKEST of the three states — `onMailbox` has no fallback for it and `select` compares it
     *  with nothing — which on the one path with no Undo is the shape that must never pass. Not
     *  reachable today (`ImapMessage.toEntity` stores null or a positive); refused because
     *  "irreversible first" outranks "reachable today". */
    data class Frozen(val value: Long?) : FrozenNumbering
}

/**
 * What the wire is given for a [FrozenNumbering] — the only decision, and it is EXECUTED by
 * [FrozenNumberingRefusalTest] rather than read out of a body.
 */
sealed interface NumberingToOppose {

    /** Nothing can be confirmed: raise `ImapNumberingUnconfirmed` and touch nothing. */
    data object Refuse : NumberingToOppose

    /** [stamp] goes on the SELECT the command is about to run under; `null` means "fall back on
     *  the folder's record", i.e. the behaviour that shipped for a caller that froze nothing. */
    data class Select(val stamp: Long?) : NumberingToOppose
}

/**
 * Three inputs, three outputs, no protocol and no I/O:
 *
 *  - [FrozenNumbering.NothingFrozen] → `Select(null)`, the fallback every ordinary gesture needs;
 *  - `Frozen(x)` with x a real numbering → `Select(x)`, the stamp of the tick opposed on the wire;
 *  - `Frozen(null)` → [NumberingToOppose.Refuse], the case that used to be spelled `null` and
 *    was therefore indistinguishable from the first — and `Frozen(0)` or a negative with it, on
 *    [UidValidity.stated]'s rule, which is read here rather than restated.
 *
 * It says nothing about JMAP, and does not have to: the two call sites that CONSUME it sit
 * inside `if (credentials.protocol == MailProtocol.IMAP)`.
 *
 * There is a THIRD site that reads a [FrozenNumbering] and it is NOT one of them:
 * [rawSourceFromCache], called above the protocol test. It takes the protocol as an argument for
 * exactly that reason — on JMAP every ticked row carries `Frozen(null)` (`numberingStampsOfRows`
 * answers an empty map there), and skipping the cache on that would make every cross-account move
 * re-download a whole blob for a guard that cannot fire on that protocol.
 *
 * The upstream half of the guard (`UidValidity.destroyableUnderTheNumberingItWasTickedUnder`,
 * `numberingStampsOfRows`) IS protocol-agnostic, and THAT is where refusing on an absent stamp
 * would break 100 % of JMAP — never here.
 */
fun numberingToOppose(frozen: FrozenNumbering): NumberingToOppose = when (frozen) {
    FrozenNumbering.NothingFrozen -> NumberingToOppose.Select(null)
    is FrozenNumbering.Frozen -> UidValidity.stated(frozen.value)?.let { NumberingToOppose.Select(it) } ?: NumberingToOppose.Refuse
}
