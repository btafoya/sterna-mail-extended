package app.sterna.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * What this file is worth. Every `suspend fun set…` of `SettingsRepository` is a one-line write into
 * the DataStore, and until this lint existed not one of those bodies was pinned: replacing the
 * argument by a constant, `it[KEY_UNREAD_TINT] = false`, left the whole suite green while the
 * setting became unsettable, the switch flips under the finger and springs back at the next
 * emission of the flow. A new function writing a setting without its own rule was just as quiet.
 *
 * What it is not. It is a source lint, not a behaviour test, and it cannot be one here:
 * `SettingsRepository` takes a `Context` and builds a real `DataStore`, there is no Robolectric and
 * no instrumented test in this module, so nothing in a JVM run can construct it or observe a write.
 * The same disclaimer, and the same instrument, as `PgpProviderStorageWiringTest` next to it.
 *
 * What it does not cover. It says nothing about the screen that calls a setter, nothing about the
 * flow that reads the value back, and nothing about the defaults, only the write itself. One
 * setter is pinned whole by the file that owns the rest of its wiring, and two carry no setting,
 * `FATES` records those and is what makes the file resist going stale.
 *
 * The form. Each table row is pinned as the whole three-line body under its own signature, so a
 * write is tied to the function it belongs to: two setters that swap bodies each keep writing their
 * own key, from the wrong function, and a selection by key alone would stay green. Comparison is on
 * whole trimmed lines, never `contains` on a body: a fragment check is blind to anything appended to
 * a line. Every expected list is non-empty, so a rule whose filter matches nothing fails instead of
 * passing over an empty set, and `block` returns a marker rather than an empty list for the same
 * reason.
 */
class SettingsWriteWiringTest {

    @Test fun `every plain setting writes the argument it was handed`() {
        PLAIN.forEach { assertWrite(it, "the argument, and nothing else") }
    }

    @Test fun `every enum setting writes the name of its own enum`() {
        ENUMS.forEach { assertWrite(it, "the name of the enum it was handed") }
    }

    @Test fun `the quiet hours bounds are pinned along with the write they clamp`() {
        // The clamp is part of the pinned line on purpose: 0..24*60-1 is what keeps a restored
        // backup, or a future picker, from storing a minute that no window can ever contain.
        // Widening or dropping it is then a deliberate edit here rather than a silent one.
        BOUNDED.forEach {
            assertWrite(it, "the clamped argument, bounds included, `minutes.coerceIn(0, 24 * 60 - 1)`")
        }
    }

    @Test fun `the image allowlist is written from three places and read from one`() {
        assertEquals(
            "these are the only lines naming KEY_IMAGE_ALLOWLIST outside its declaration: the flow " +
                "the reader consults, the read and the write of a single sender's answer, the " +
                "clearing, and the wholesale write used when a backup is imported. A line that " +
                "appears here without being one of them is a fourth way to decide whose remote " +
                "images load, which is a privacy answer the user gave sender by sender.",
            listOf(
                "val imageAllowlist: Flow<Set<String>> = dataStore.data.map { it[KEY_IMAGE_ALLOWLIST] ?: emptySet() }",
                "val current = prefs[KEY_IMAGE_ALLOWLIST] ?: emptySet()",
                "prefs[KEY_IMAGE_ALLOWLIST] = if (allowed) current + key else current - key",
                "dataStore.edit { it.remove(KEY_IMAGE_ALLOWLIST) }",
                "dataStore.edit { it[KEY_IMAGE_ALLOWLIST] = cleaned }",
            ),
            codeLines(SETTINGS_REPOSITORY)
                .filter { "KEY_IMAGE_ALLOWLIST" in it && !it.startsWith("private val") },
        )
    }

    @Test fun `setImageAllowed adds or removes one sender, and refuses a blank one`() {
        assertEquals(
            "`setImageAllowed` is pinned whole: it writes through `prefs` inside the edit block, " +
                "not through `it`, because it reads the current set first. Dropping the " +
                "`if (key.isEmpty()) return` guard stores an empty sender, which then matches the " +
                "messages whose From address failed to parse and loads their remote images. " +
                "Writing `current + key` on both branches makes 'never load images for this " +
                "sender' impossible to take back.",
            listOf(
                "suspend fun setImageAllowed(sender: String, allowed: Boolean) {",
                "val key = sender.trim().lowercase()",
                "if (key.isEmpty()) return",
                "dataStore.edit { prefs ->",
                "val current = prefs[KEY_IMAGE_ALLOWLIST] ?: emptySet()",
                "prefs[KEY_IMAGE_ALLOWLIST] = if (allowed) current + key else current - key",
                "}",
                "}",
            ),
            block("suspend fun setImageAllowed(", 8),
        )
    }

    @Test fun `setImageAllowlist normalises the senders before storing them`() {
        assertEquals(
            "`setImageAllowlist` replaces the whole list and has no caller outside this file, only " +
                "`restoreBackup`, the import path. Storing `senders` unchanged puts addresses with " +
                "their original case into the store, and the lookup, which compares lower-cased, " +
                "then never matches them: images stay blocked for senders the user had allowed " +
                "before the backup.",
            listOf(
                "suspend fun setImageAllowlist(senders: Set<String>) {",
                "val cleaned = senders.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()",
                "dataStore.edit { it[KEY_IMAGE_ALLOWLIST] = cleaned }",
                "}",
            ),
            block("suspend fun setImageAllowlist(", 4),
        )
    }

    @Test fun `clearImageAllowlist empties the list rather than rewriting it`() {
        assertEquals(
            "`clearImageAllowlist` is the one gesture that takes back every remote-image answer at " +
                "once. A body that writes a set back, whatever set, leaves senders allowed after " +
                "the user asked to forget them.",
            listOf(
                "suspend fun clearImageAllowlist() {",
                "dataStore.edit { it.remove(KEY_IMAGE_ALLOWLIST) }",
                "}",
            ),
            block("suspend fun clearImageAllowlist(", 3),
        )
    }

    /**
     * The guard. Every function of the repository that writes into the DataStore is listed below
     * with its fate, so a new one makes this list diverge and the author has to say, here, which of
     * the three it is. Membership is decided by what the body does, not by the name: a `toggle…`, an
     * `update…` or a second `clear…` is caught the same way `clearImageAllowlist` is. Adding one is
     * a deliberate edit here, which is the point.
     */
    @Test fun `every function that writes a setting has its fate recorded here`() {
        val fates = FATES.toMap()
        val found = writerSignatures()
        assertEquals(
            "the functions of SettingsRepository that write into the DataStore are not the ones " +
                "recorded in this file. One listed as 'no fate recorded here' is new: pin its body " +
                "in the table above (PLAIN, ENUMS or BOUNDED), or, if its wiring is pinned by " +
                "another file, or if it stores internal state with no switch and no saved line " +
                "behind it, say so in `FATES`. Leaving it out means its body can be replaced by a " +
                "constant, the setting flips on screen and springs back, and nothing anywhere " +
                "would notice.",
            FATES.map { "${it.first} -- ${it.second}" },
            found.map { "$it -- ${fates[it] ?: "no fate recorded here"}" },
        )
    }

    /** One table row: a setter's [signature] and the single [line] its body must write. */
    private data class Write(val signature: String, val line: String)

    /**
     * Pins a three-line body under its own signature. [stores] names, in the failure, what the body
     * is supposed to store, since that is the part a mutation replaces by a constant. A body emptied
     * to `{}` is two lines, so the block swallows whatever follows and the comparison still fails.
     */
    private fun assertWrite(write: Write, stores: String) {
        val name = write.signature.removePrefix("suspend fun ").substringBefore("(")
        assertEquals(
            "`$name` must store $stores, and it must be the function that does it:\n  " +
                "${write.line}\nA constant here, or the negation of the argument, or a write under " +
                "another key, makes the setting unsettable: it moves on screen and springs back as " +
                "soon as the flow re-emits, and the user cannot turn it on at all. Two settings " +
                "whose bodies were swapped each still write their own key, from the wrong function, " +
                "which is why the whole body is pinned under its signature rather than the write " +
                "line alone.",
            listOf(write.signature, write.line, "}"),
            block(write.signature.substringBefore("(") + "(", 3),
        )
    }

    /**
     * Signatures of the suspend functions whose body reaches `dataStore.edit`, in source order. The
     * body is delimited by brace depth from the signature line onwards, so a single-line
     * expression body counts as well. A writer declared `private` or `internal`, one without
     * `suspend`, or one whose signature is wrapped over two lines is not recognised, and would
     * arrive with nothing asking for its rule.
     */
    private fun writerSignatures(): List<String> {
        val code = codeLines(SETTINGS_REPOSITORY)
        return code.indices.filter { code[it].startsWith("suspend fun ") }.mapNotNull { start ->
            var depth = 0
            var i = start
            val body = mutableListOf<String>()
            do {
                depth += code[i].count { c -> c == '{' } - code[i].count { c -> c == '}' }
                body += code[i]
                i++
            } while (i < code.size && depth > 0)
            if (body.any { "dataStore.edit" in it }) code[start] else null
        }
    }

    /**
     * [count] consecutive code lines of `SettingsRepository.kt` from the single one starting with
     * [prefix], trimmed, comments and blanks dropped. A prefix matching anything other than exactly
     * one line returns a message, so an assertion can never pass by matching nothing.
     */
    private fun block(prefix: String, count: Int): List<String> {
        val code = codeLines(SETTINGS_REPOSITORY)
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(start, minOf(start + count, code.size))
    }

    private fun codeLines(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private companion object {
        const val REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        val SETTINGS_REPOSITORY: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, REPOSITORY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath}: this lint reads " +
                        "the shipped source as text and needs a working directory inside the checkout",
                )
            File(root, REPOSITORY_PATH)
        }

        /** Settings whose setter stores its argument as it stands. */
        val PLAIN = listOf(
            Write(
                "suspend fun setDynamicColor(enabled: Boolean) {",
                "dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }",
            ),
            Write(
                "suspend fun setUnreadTint(enabled: Boolean) {",
                "dataStore.edit { it[KEY_UNREAD_TINT] = enabled }",
            ),
            Write(
                "suspend fun setPureBlack(enabled: Boolean) {",
                "dataStore.edit { it[KEY_PURE_BLACK] = enabled }",
            ),
            Write(
                "suspend fun setListMonogram(enabled: Boolean) {",
                "dataStore.edit { it[KEY_LIST_MONOGRAM] = enabled }",
            ),
            Write(
                "suspend fun setConversationView(enabled: Boolean) {",
                "dataStore.edit { it[KEY_CONVERSATION_VIEW] = enabled }",
            ),
            Write(
                "suspend fun setMarkReadOnDelete(enabled: Boolean) {",
                "dataStore.edit { it[KEY_MARK_READ_ON_DELETE] = enabled }",
            ),
            Write(
                "suspend fun setMarkReadOnArchive(enabled: Boolean) {",
                "dataStore.edit { it[KEY_MARK_READ_ON_ARCHIVE] = enabled }",
            ),
            Write(
                "suspend fun setMarkReadOnMove(enabled: Boolean) {",
                "dataStore.edit { it[KEY_MARK_READ_ON_MOVE] = enabled }",
            ),
            Write(
                "suspend fun setUnarchiveOnReply(enabled: Boolean) {",
                "dataStore.edit { it[KEY_UNARCHIVE_ON_REPLY] = enabled }",
            ),
            Write(
                "suspend fun setSignatureOnReplies(enabled: Boolean) {",
                "dataStore.edit { it[KEY_SIGNATURE_ON_REPLIES] = enabled }",
            ),
            Write(
                "suspend fun setSignatureBelowQuote(enabled: Boolean) {",
                "dataStore.edit { it[KEY_SIGNATURE_BELOW_QUOTE] = enabled }",
            ),
            Write(
                "suspend fun setSignatureDelimiter(enabled: Boolean) {",
                "dataStore.edit { it[KEY_SIGNATURE_DELIMITER] = enabled }",
            ),
            Write(
                "suspend fun setReplyBar(enabled: Boolean) {",
                "dataStore.edit { it[KEY_REPLY_BAR] = enabled }",
            ),
            Write(
                "suspend fun setPlainText(enabled: Boolean) {",
                "dataStore.edit { it[KEY_PLAIN_TEXT] = enabled }",
            ),
            Write(
                "suspend fun setQuotedDatesUtc(enabled: Boolean) {",
                "dataStore.edit { it[KEY_QUOTED_DATES_UTC] = enabled }",
            ),
            Write(
                "suspend fun setContactSuggestions(enabled: Boolean) {",
                "dataStore.edit { it[KEY_CONTACT_SUGGESTIONS] = enabled }",
            ),
            Write(
                "suspend fun setStripTrackingParams(enabled: Boolean) {",
                "dataStore.edit { it[KEY_STRIP_TRACKING] = enabled }",
            ),
            Write(
                "suspend fun setConfirmLinks(enabled: Boolean) {",
                "dataStore.edit { it[KEY_CONFIRM_LINKS] = enabled }",
            ),
            Write(
                "suspend fun setAskReadReceipt(enabled: Boolean) {",
                "dataStore.edit { it[KEY_ASK_READ_RECEIPT] = enabled }",
            ),
            Write(
                "suspend fun setQuietHoursEnabled(enabled: Boolean) {",
                "dataStore.edit { it[KEY_QUIET_ENABLED] = enabled }",
            ),
            Write(
                "suspend fun setLastScheduleMinutes(minutes: Int) {",
                "dataStore.edit { it[KEY_LAST_SCHEDULE_MINUTES] = minutes }",
            ),
            Write(
                "suspend fun setSearchHistory(enabled: Boolean) {",
                "dataStore.edit { it[KEY_SEARCH_HISTORY_ENABLED] = enabled }",
            ),
        )

        /**
         * Settings stored as the name of an enum constant. The whole line pins which enum is
         * written as well: two of these, the swipe actions, take the same type and differ only by
         * the key they land under.
         */
        val ENUMS = listOf(
            Write(
                "suspend fun setThemeMode(mode: ThemeMode) {",
                "dataStore.edit { it[KEY_THEME_MODE] = mode.name }",
            ),
            Write(
                "suspend fun setListDensity(density: ListDensity) {",
                "dataStore.edit { it[KEY_LIST_DENSITY] = density.name }",
            ),
            Write(
                "suspend fun setReadingPaneMode(mode: ReadingPaneMode) {",
                "dataStore.edit { it[KEY_READING_PANE_MODE] = mode.name }",
            ),
            Write(
                "suspend fun setPreviewLines(value: PreviewLines) {",
                "dataStore.edit { it[KEY_PREVIEW_LINES] = value.name }",
            ),
            Write(
                "suspend fun setSwipeRightAction(action: SwipeAction) {",
                "dataStore.edit { it[KEY_SWIPE_RIGHT] = action.name }",
            ),
            Write(
                "suspend fun setSwipeLeftAction(action: SwipeAction) {",
                "dataStore.edit { it[KEY_SWIPE_LEFT] = action.name }",
            ),
            Write(
                "suspend fun setSortOrder(order: SortOrder) {",
                "dataStore.edit { it[KEY_SORT_ORDER] = order.name }",
            ),
            Write(
                "suspend fun setMessageTextSize(size: MessageTextSize) {",
                "dataStore.edit { it[KEY_MESSAGE_TEXT_SIZE] = size.name }",
            ),
            Write(
                "suspend fun setDeliveryMode(mode: DeliveryMode) {",
                "dataStore.edit { it[KEY_DELIVERY_MODE] = mode.name }",
            ),
            Write(
                "suspend fun setNotificationContent(mode: NotificationContent) {",
                "dataStore.edit { it[KEY_NOTIFICATION_CONTENT] = mode.name }",
            ),
        )

        /** The two minute-of-day settings, stored clamped to one day. */
        val BOUNDED = listOf(
            Write(
                "suspend fun setQuietHoursStart(minutes: Int) {",
                "dataStore.edit { it[KEY_QUIET_START] = minutes.coerceIn(0, 24 * 60 - 1) }",
            ),
            Write(
                "suspend fun setQuietHoursEnd(minutes: Int) {",
                "dataStore.edit { it[KEY_QUIET_END] = minutes.coerceIn(0, 24 * 60 - 1) }",
            ),
        )

        /**
         * Every function the repository declares that writes into the DataStore, in source order,
         * and what answers for it. Three fates only: pinned by the table in this file, pinned by
         * another file that owns the rest of that setting's wiring, or out of scope because no
         * switch and no saved line hangs off it, so "the setting becomes unsettable" names nothing.
         */
        val FATES = listOf(
            "suspend fun setThemeMode(mode: ThemeMode) {" to "ENUMS",
            "suspend fun setDynamicColor(enabled: Boolean) {" to "PLAIN",
            "suspend fun setListDensity(density: ListDensity) {" to "ENUMS",
            "suspend fun setReadingPaneMode(mode: ReadingPaneMode) {" to "ENUMS",
            "suspend fun setPreviewLines(value: PreviewLines) {" to "ENUMS",
            "suspend fun setUnreadTint(enabled: Boolean) {" to "PLAIN",
            "suspend fun setPureBlack(enabled: Boolean) {" to
                "PLAIN, the rest of its wiring pinned by PureBlackWiringTest",
            "suspend fun setListMonogram(enabled: Boolean) {" to
                "PLAIN, the rest of its wiring pinned by ListMonogramWiringTest",
            "suspend fun setSwipeRightAction(action: SwipeAction) {" to "ENUMS",
            "suspend fun setSwipeLeftAction(action: SwipeAction) {" to "ENUMS",
            "suspend fun setSortOrder(order: SortOrder) {" to "ENUMS",
            "suspend fun setConversationView(enabled: Boolean) {" to "PLAIN",
            "suspend fun setMarkReadOnDelete(enabled: Boolean) {" to "PLAIN",
            "suspend fun setMarkReadOnArchive(enabled: Boolean) {" to "PLAIN",
            "suspend fun setMarkReadOnMove(enabled: Boolean) {" to "PLAIN",
            "suspend fun setUnarchiveOnReply(enabled: Boolean) {" to "PLAIN",
            "suspend fun setSignatureOnReplies(enabled: Boolean) {" to "PLAIN",
            "suspend fun setSignatureBelowQuote(enabled: Boolean) {" to "PLAIN",
            "suspend fun setSignatureDelimiter(enabled: Boolean) {" to "PLAIN",
            "suspend fun setPgpProvider(packageName: String) {" to "pinned by PgpProviderStorageWiringTest",
            "suspend fun setReplyBar(enabled: Boolean) {" to "PLAIN",
            "suspend fun setPlainText(enabled: Boolean) {" to
                "PLAIN, the rest of its wiring pinned by PlainTextReadingWiringTest",
            "suspend fun setQuotedDatesUtc(enabled: Boolean) {" to "PLAIN",
            "suspend fun setMessageTextSize(size: MessageTextSize) {" to "ENUMS",
            "suspend fun setContactSuggestions(enabled: Boolean) {" to "PLAIN",
            "suspend fun setHasSeenWelcome(seen: Boolean) {" to
                "out of scope: first-launch state, no switch and no saved line",
            "suspend fun setHasPrimedContacts(primed: Boolean) {" to
                "out of scope: priming-shown state, no switch and no saved line",
            "suspend fun setStripTrackingParams(enabled: Boolean) {" to "PLAIN",
            "suspend fun setConfirmLinks(enabled: Boolean) {" to "PLAIN",
            "suspend fun setAskReadReceipt(enabled: Boolean) {" to "PLAIN",
            "suspend fun setImageAllowed(sender: String, allowed: Boolean) {" to
                "pinned whole in this file, off the table: it writes through `prefs`, not `it`",
            "suspend fun clearImageAllowlist() {" to
                "pinned whole in this file, off the table: it removes the key",
            "suspend fun setImageAllowlist(senders: Set<String>) {" to
                "pinned whole in this file, off the table: it normalises before writing",
            "suspend fun setDeliveryMode(mode: DeliveryMode) {" to "ENUMS",
            "suspend fun setNotificationContent(mode: NotificationContent) {" to "ENUMS",
            "suspend fun setQuietHoursEnabled(enabled: Boolean) {" to "PLAIN",
            "suspend fun setQuietHoursStart(minutes: Int) {" to "BOUNDED",
            "suspend fun setQuietHoursEnd(minutes: Int) {" to "BOUNDED",
            "suspend fun setLastScheduleMinutes(minutes: Int) {" to "PLAIN, local, left out of the backup",
            "suspend fun setSearchHistory(enabled: Boolean) {" to
                "PLAIN, the rest of its wiring pinned by SettingsBackupSearchHistoryTest and SearchHistoryWiringTest",
        )
    }
}
