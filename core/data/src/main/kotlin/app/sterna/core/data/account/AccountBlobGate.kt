package app.sterna.core.data.account

/**
 * Tells **"this device has no accounts"** apart from **"I could not read the account list"**, and
 * refuses every write to the stored blob while the second one is true.
 *
 * Why it exists. The whole account list is ONE JSON blob in prefs, and every mutator of
 * [AccountStore] is a read-modify-write over it. The read used to answer a decode failure with an
 * empty list, indistinguishable from a fresh install — so the next setter the user touched wrote
 * that empty list back over the real one and the accounts were gone for good (bench, 2026-08-06:
 * four accounts, then two, then "Add an account", with the `pw_<id>` keys of the vanished accounts
 * still in the file — a rewrite, not a removal). The cheapest trigger is an enum constant this
 * build does not know: kotlinx serialises enums BY NAME and `ignoreUnknownKeys` does not cover a
 * name, so one renamed member takes the whole list down at once.
 *
 * The rule, and only the rule, lives here — with no Android in sight, so it can be executed by a
 * unit test instead of being read off the source of a class that needs a `Context` and the
 * Keystore. [AccountStore] calls it; it does not restate it.
 *
 * Three cases, one place ([read]):
 *  - the key is absent (fresh install, or after "clear data") — a LEGITIMATE empty: it clears;
 *  - the blob decodes, even to an empty list — it clears;
 *  - the blob does not decode — it sets, and every write is refused until a later read succeeds.
 *
 * Decoding is all-or-nothing on purpose. Salvaging the records that happen to parse would look
 * kinder and would be worse: the write that followed would delete the unreadable one permanently.
 *
 * Why the ENCODER lives here too, and not in [AccountStore.saveAccounts]. The obvious shape —
 * a `writeGuarded { … }` the caller fills with its own `putString(json.encodeToString(list))` —
 * survives the most ordinary refactor there is: move that one line out of the lambda and the store
 * writes unconditionally again, with the gate still called and every source check still passing.
 * So the bytes to store are produced HERE and handed to the callback: outside it there is nothing
 * to write, and getting some back means re-encoding the list by hand, which is no longer a
 * refactor but a visible new statement.
 *
 * Nothing here is sticky beyond the truth of the moment: the flag is recomputed on EVERY read, and
 * [AccountStore.accounts] is called constantly, so a blob that becomes readable again (the build
 * that knows the name is installed) lifts the refusal on the very next call, with no restart and
 * nothing to repair.
 *
 * Threading: [AccountStore] serialises its mutators, but reads are deliberately lock-free, so the
 * flag is `@Volatile` — a read on another dispatcher must not park a stale value in a register.
 */
internal class AccountBlobGate(
    private val decode: (String) -> List<StoredAccount>,
    private val encode: (List<StoredAccount>) -> String,
) {

    @Volatile
    private var unreadable = false

    /** Whether the last [read] failed to decode the stored blob. Diagnostics only: the decision
     *  itself is [writeGuarded], so that no caller can re-derive it and get it subtly wrong. */
    val blobUnreadable: Boolean get() = unreadable

    /**
     * Decode the stored blob, and record whether it could be. Never throws: this runs from
     * [AccountStore]'s constructor, where an exception would kill process start-up, and its result
     * keeps the shape 62 call sites already handle — the accounts, or an empty list.
     */
    fun read(raw: String?): List<StoredAccount> {
        // An absent key is not a failure: there is simply nothing stored yet. One assignment for
        // all three cases, so "unreadable" can never be left describing an older read.
        val decoded = if (raw == null) Result.success(emptyList()) else runCatching { decode(raw) }
        unreadable = decoded.isFailure
        return decoded.getOrDefault(emptyList())
    }

    /**
     * Hand [write] the bytes that represent [list], so it can store them — unless the stored blob
     * failed to decode on the last read, in which case [list] is not even encoded and [write] never
     * runs. Returns whether the write was performed, so the caller can log the refusal and hold back
     * anything that must not happen without it (publishing the list to the UI, for one).
     *
     * It returns instead of throwing: a throw here would be fifteen new crash paths through the
     * UI for a condition the user can do nothing about. Refusing to write loses a setting change;
     * writing loses every account.
     */
    fun writeGuarded(list: List<StoredAccount>, write: (String) -> Unit): Boolean {
        if (unreadable) return false
        write(encode(list))
        return true
    }

    /** The stored file was legitimately emptied ([AccountStore.clear]), so there is no longer
     *  anything unreadable to protect. Without this, a reset from an unreadable state could never
     *  store an account again. */
    fun onStorageWiped() {
        unreadable = false
    }
}
