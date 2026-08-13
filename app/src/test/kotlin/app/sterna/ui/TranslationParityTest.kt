package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RESOURCE LINT, NOT A BEHAVIOUR TEST. It reads the string resources as text and checks the one
 * rule the build cannot: a string the app shows exists in EVERY language it ships, or in none.
 *
 * Android has no complaint to make about a half-translated string — it silently falls back to
 * English — so a new label added to `values/` alone reaches eight of nine users as a stray English
 * word, and nothing says so. That has happened here before, which is why it is a test and not a
 * habit. Nine languages is the real cost of one new label, and this is where that cost is stated.
 */
class TranslationParityTest {

    @Test
    fun `every string the app ships exists in every language`() {
        val base = keysOf(File(res, "values/strings.xml"))
        assertTrue("no strings read from values/strings.xml — wrong working directory?", base.size > 100)
        val missing = translations()
            .associate { it.parentFile.name to (base - keysOf(it)) }
            .filterValues { it.isNotEmpty() }
        assertEquals("strings missing from a translation", emptyMap<String, Set<String>>(), missing)
    }

    /** The other direction: a key left behind in a translation after the default set dropped it. */
    @Test
    fun `no language carries a string the default set no longer has`() {
        val base = keysOf(File(res, "values/strings.xml"))
        val orphans = translations()
            .associate { it.parentFile.name to (keysOf(it) - base) }
            .filterValues { it.isNotEmpty() }
        assertEquals("strings left in a translation", emptyMap<String, Set<String>>(), orphans)
    }

    /**
     * The third way a translated string goes wrong, and the only one that CRASHES: a format
     * argument that does not survive the translation. `getString(id, x)` on a text whose `%1$s`
     * was dropped renders without it — the sentence loses the very thing it was naming, which for
     * "Sterna will send an unsubscribe request to %1$s." means a confirmation that no longer says
     * to whom — and an extra or renumbered specifier throws `IllegalFormatException` outright, in
     * one language, on one screen, where nothing in the build had anything to say about it.
     */
    @Test
    fun `every format argument survives every translation`() {
        val base = placeholdersOf(File(res, "values/strings.xml"))
        val broken = translations().flatMap { file ->
            val translated = placeholdersOf(file)
            base.mapNotNull { (key, args) ->
                val theirs = translated[key] ?: return@mapNotNull null
                if (theirs == args) null else "${file.parentFile.name}/$key: $args vs $theirs"
            }
        }
        assertEquals("format arguments lost or added in a translation", emptyList<String>(), broken)
    }

    /**
     * The rules above compare against whatever `values-*` happen to exist, so they say nothing
     * about a language that disappears — and nothing about the label added last. Both are pinned
     * here: the nine directories the app ships, by name, and the black-background switch (#117),
     * which is the setting that would otherwise reach eight of nine users as two English lines in
     * the middle of a translated Appearance screen.
     */
    @Test
    fun `the nine languages all label the black-background switch`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        assertEquals(
            "the app ships nine languages; a directory that vanishes takes its own parity rule " +
                "with it and nothing else notices",
            listOf(
                "values", "values-de", "values-es", "values-fr", "values-it",
                "values-nl", "values-pl", "values-pt", "values-ru",
            ),
            files.map { it.parentFile.name },
        )
        val expected = setOf("settings_pure_black_title", "settings_pure_black_subtitle")
        val missing = files.associate { it.parentFile.name to (expected - keysOf(it)) }
            .filterValues { it.isNotEmpty() }
        assertEquals("the OLED switch is unlabelled in", emptyMap<String, Set<String>>(), missing)
    }

    /**
     * The read-receipt switch (#148), by name in the nine languages — the general parity rule
     * above compares against `values/`, so it would go green on a switch never translated at all.
     *
     * It is a privacy switch: left in English, eight of nine readers meet a stray sentence about
     * something being sent to a stranger, in the middle of a translated Privacy screen.
     */
    @Test
    fun `the nine languages all label the read-receipt switch`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        val missing = files.associate { it.parentFile.name to (READ_RECEIPT_ENGLISH.keys - keysOf(it)) }
            .filterValues { it.isNotEmpty() }
        assertEquals("the read-receipt switch is unlabelled in", emptyMap<String, Set<String>>(), missing)
    }

    /**
     * ⛔ WHAT THAT SWITCH PROMISES, PINNED WHOLE: it makes the app **ask**, never send.
     *
     * There is no third position and there must never be one. A label saying "Send read receipts"
     * describes a setting this app does not have — turning it on raises a question on a message,
     * one message at a time, and nothing leaves without a tap. Getting that wording wrong is worse
     * than having no setting: the reader would believe her phone answers strangers on its own.
     *
     * Whole-value equality on purpose: `contains` is blind to anything a reword appends.
     *
     * Not checked here, and stated as debt: that the eight translations say "ask" rather than
     * "send". Their keys are held above; their meaning is read by nobody.
     */
    @Test
    fun `the read-receipt switch says it asks, and never that it sends`() {
        val actual = valuesOf(File(res, "values/strings.xml")).filterKeys { it in READ_RECEIPT_ENGLISH }
        assertEquals(
            "the read-receipt switch's English text changed. It owes exactly this: the app ASKS " +
                "before sending, the request is ignored while the switch is off, and nothing ever " +
                "leaves without the reader saying so. It must never promise to send.",
            READ_RECEIPT_ENGLISH,
            actual,
        )
    }

    /**
     * The strip that puts the question on the message (#148), by name in the nine languages.
     *
     * Same reason as the switch, one degree worse: this is the row that offers to tell a stranger
     * something, and it appears in the middle of a message the reader is reading. Left in English,
     * eight of nine readers are asked in a language they did not choose to make a decision they
     * cannot undo.
     */
    @Test
    fun `the nine languages all carry the read-receipt strip`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        val missing = files.associate { it.parentFile.name to (READ_RECEIPT_STRIP_ENGLISH.keys - keysOf(it)) }
            .filterValues { it.isNotEmpty() }
        assertEquals("the read-receipt strip is untranslated in", emptyMap<String, Set<String>>(), missing)
    }

    /**
     * ⛔ WHAT THE STRIP MAY SAY, PINNED WHOLE — and the three things it must never say.
     *
     * 1. **Never "read".** An MDN reports that a message was DISPLAYED on a device. Nobody knows
     *    whether it was read, least of all this app, and "%1$s will know you read this" is a claim
     *    made on the reader's behalf to someone who cannot check it.
     * 2. **Never "sent".** A receipt has one road, the durable outbox, and nothing here watches it
     *    leave — "in the outbox" is the furthest the app can honestly go (`ReadReceiptState` has no
     *    `Sent`, deliberately).
     * 3. **Never automatic, silent or transparent.** The whole feature is one gesture on one
     *    message; wording that suggests otherwise describes a setting this app refused to build.
     *
     * Whole-value equality, because `contains` is blind to anything a reword appends.
     *
     * Not checked here, and stated as debt: that the eight translations obey the same three rules.
     * Their keys are held above and their format arguments by the rule at the top of this file;
     * their meaning is read by nobody.
     */
    @Test
    fun `the strip asks, names who would be told, and never claims the message was read`() {
        val actual = valuesOf(File(res, "values/strings.xml"))
            .filterKeys { it in READ_RECEIPT_STRIP_ENGLISH }
        // The screen runs FIRST, on what the file actually says: a reword that broke the promise
        // should be told what it promised, not merely that the pin below no longer matches.
        val forbidden = actual.values.flatMap { value ->
            FORBIDDEN_OF_A_RECEIPT.filter { it in value.lowercase() }.map { "'$it' in \"$value\"" }
        }
        assertEquals(
            "the strip must not promise that a message was READ (an MDN reports a display), that " +
                "a receipt was SENT (it goes to an outbox and nothing here watches it leave), or " +
                "that any of it happens on its own. Found:",
            emptyList<String>(),
            forbidden,
        )
        assertEquals(
            "the read-receipt strip's English text changed. It owes exactly this: it names WHO " +
                "asked, it says the message was DISPLAYED (never read), and the furthest it goes " +
                "is the outbox — there is no 'sent'.",
            READ_RECEIPT_STRIP_ENGLISH,
            actual,
        )
    }

    /**
     * The startup screen shown when the stored account list will not decode. It is the one screen
     * a user meets while her accounts are invisible, so a language that falls back to English there
     * leaves her reading a stranger's alphabet about her own mail having gone missing.
     *
     * Named keys rather than the general parity rule above: that rule compares whatever is in
     * `values/`, so it would go green on a screen whose strings were never added at all.
     */
    @Test
    fun `the nine languages all carry the unreadable-accounts screen`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        val missing = files.associate { it.parentFile.name to (UNREADABLE_ENGLISH.keys - keysOf(it)) }
            .filterValues { it.isNotEmpty() }
        assertEquals("the unreadable-accounts screen is unwritten in", emptyMap<String, Set<String>>(), missing)
    }

    /**
     * WHAT THAT SCREEN PROMISES, pinned whole, because getting it wrong is worse than not having
     * the screen. The accounts are still on the device, untouched — the whole point of the write
     * refusal behind this — so the text must not say, or suggest, that anything was deleted.
     *
     * And the last sentence is deliberately "MAY bring them back", not "brings them back". The gate
     * raises its flag on ANY decode failure, not only on the enum name a newer build would know: a
     * truncated blob or a damaged prefs file lands here too, and no update fixes those. Promising
     * the recovery unconditionally would be the displayed state lying about the real one.
     *
     * Whole-value equality on purpose: `contains` is blind to anything a reword APPENDS, and this
     * text is a promise made to someone who has just been told her mail is missing.
     *
     * Not checked here, and stated as debt: that the eight translations say the same three things.
     * Their KEYS are held by the rule above and their FALLBACK to English by
     * [no language leaves the account switch in English]'s sibling rules, but nothing reads their
     * meaning.
     */
    @Test
    fun `the unreadable-accounts screen says nothing was deleted, and asks for nothing`() {
        val actual = valuesOf(File(res, "values/strings.xml")).filterKeys { it in UNREADABLE_ENGLISH }
        assertEquals(
            "the text of the unreadable-accounts screen changed. It owes three statements and no " +
                "fourth: the accounts could not be read, NOTHING was deleted, and an update " +
                "brings them back. It must never say they are gone, never promise a fix without " +
                "one, and never offer an action — the only action that would 'unblock' it is " +
                "wiping the accounts it exists to protect.",
            UNREADABLE_ENGLISH,
            actual,
        )
    }

    /**
     * PARITY OF KEYS IS NOT PARITY OF MEANING, AND THIS SWITCH IS WHERE THAT BITES.
     *
     * `notificationsEnabled` reads like a display setting and is not one: five of the six places
     * that consult it go and FETCH mail (`PushController`, `PushService`, `MailFetchWorker`,
     * `PushFetchWorker`, `BootReceiver`/`BootRestart`), and only one decides whether to notify
     * (`FetchAndNotify`). A label that promises "Show notifications for new mail" therefore lies
     * about what turning it off costs. The English text is pinned here, whole, so that the honest
     * wording cannot be quietly walked back to the display-only one it replaced.
     *
     * The SECTION heading is the exception, and it is pinned to "Notifications" on purpose: a
     * heading is not a displayed state, it names where the setting lives, and it can say
     * "Notifications" without promising anything as long as the switch inside it says "Sync new
     * mail". It was widened to "Sync and notifications" once and had to come back: the account
     * screen already has a "Sync" section, and one heading being the prefix of the other left the
     * reader unable to tell which one governed what.
     *
     * Whole-value equality on purpose: `contains` is blind to anything a mutation APPENDS, and that
     * blindness has cost this repo three defects already.
     */
    @Test
    fun `the account switch says it governs the fetch, not only the notification`() {
        val actual = valuesOf(File(res, "values/strings.xml")).filterKeys { it in ENGLISH }
        assertEquals(
            "the per-account switch gates background fetching, not just the notification; its " +
                "English label has to say so",
            ENGLISH,
            actual,
        )
    }

    /**
     * The failure the key-parity rules above cannot see: a `values-*` that carries the key and the
     * ENGLISH text under it. For a label being reworded that is the likely accident — copy the new
     * English into the eight files, translate seven of them — and it ships as a screen where one
     * language silently reverts to the old, wrong promise.
     *
     * One pair reads exactly as the English does and is legitimate, so it is written down as the
     * value it must have, not as a key the rule skips (see [TRANSLATED]). The difference matters:
     * an exemption would let this language carry WHATEVER the English says, which is the accident
     * itself; a pinned value only lets it carry that one word. So the rule below is absolute again
     * — `emptyMap()` — and a language named in [TRANSLATED] is held to its own text, English or
     * not.
     *
     * Not checked here: a key present in [ENGLISH] and absent from a translation, which is the
     * first rule's job.
     */
    @Test
    fun `no language leaves the account switch in English`() {
        val english = valuesOf(File(res, "values/strings.xml"))
        val copied = translations().associate { file ->
            val locale = file.parentFile.name
            val theirs = valuesOf(file)
            val pinned = TRANSLATED[locale].orEmpty()
            locale to ENGLISH.keys.mapNotNull { key ->
                val text = theirs[key] ?: return@mapNotNull null
                val expected = pinned[key]
                when {
                    expected != null && text != expected ->
                        "$key: this language is pinned to <$expected> and the file says <$text>"
                    expected == null && text == english[key] ->
                        "$key: still the English text, untranslated — <$text>"
                    else -> null
                }
            }
        }.filterValues { it.isNotEmpty() }
        assertEquals("the English text left untranslated in", emptyMap<String, List<String>>(), copied)
    }

    /**
     * TWO labels of this switch make TWO statements each, and a translation that keeps only the
     * first is wrong in a way no parity rule notices.
     *
     * The subtitle: (a) the switch drives the background fetch, and (b) with it off the mail still
     * arrives, on opening the app or pulling to refresh. Drop (b) and the setting reads as "off
     * means no mail", which it is not. The unwatched note: (a) turning "Push for all accounts" on
     * is what fetches this account's mail in the background, and (b) it is also what notifies. Drop
     * (a) and the note is back to promising a display setting, which is the defect this rule exists
     * for — a reader who turns the option off to get fewer notifications stops the fetch instead.
     *
     * (b) cannot be checked by meaning here, so it is checked by shape: each label has to be at
     * least two sentences long, each one long enough to be one (see [sentencesOf] — a dot count
     * alone would take "z. B." for a second sentence), plus a floor on the whole text.
     */
    @Test
    fun `every language keeps both halves of the account switch explanation`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        val truncated = files.flatMap { file ->
            val values = valuesOf(file)
            TWO_HALVES.mapNotNull { key ->
                val text = values[key].orEmpty()
                val sentences = sentencesOf(text)
                when {
                    sentences.size < 2 ->
                        "${file.parentFile.name}/$key: ${sentences.size} sentence(s), so it cannot " +
                            "make both of the statements this label owes — <$text>"
                    text.length < 60 ->
                        "${file.parentFile.name}/$key: ${text.length} characters is too short to " +
                            "say both — <$text>"
                    else -> null
                }
            }
        }
        assertEquals("the account switch explanation says only half of it in", emptyList<String>(), truncated)
    }

    /**
     * THE SHAPE RULE ABOVE IS AN HONEST PROXY FOR THE SUBTITLE AND AN EMPTY ONE FOR THE NOTE, AND
     * THAT WAS MEASURED, NOT GUESSED.
     *
     * The subtitle's two statements ARE its two sentences, so counting them says something. The
     * note's first sentence is a bare observation ("This account is not being watched.") that
     * carries NEITHER half; both live inside the second one. So the wrong text — the pre-#140 note
     * that spoke only of notifications — clears two sentences and 95 to 109 characters in all nine
     * languages. Shape added nothing for that key, and only English was really held, by the
     * whole-value equality of [ENGLISH], which reads `values/strings.xml` and nothing else.
     *
     * What this rule adds is a REQUIREMENT OF PRESENCE on the eight translations, where nothing
     * distinguished the good text from the bad one: each language has to name half (a), the
     * background fetch, in its own words (see [BACKGROUND_FETCH]), in BOTH labels.
     *
     * `contains` here is a FLOOR, not a pin: it is blind to anything a rewrite appends, and the
     * pinning job belongs to [ENGLISH]. The falsification this rule is answerable to is the return
     * of a translation to its text from before the fix — put the `values-fr` note of `cda6648c`
     * back and this test, and only this test, has to go red.
     *
     * Not checked on purpose: the ORDER of the two halves, and the wording of half (b). Both are
     * left as stated debt rather than pretended coverage.
     */
    @Test
    fun `every language says the switch fetches in the background, not only that it notifies`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        val silent = files.flatMap { file ->
            val locale = file.parentFile.name
            val values = valuesOf(file)
            val marker = BACKGROUND_FETCH[locale] ?: return@flatMap listOf(
                "$locale: no background-fetch marker is written for this language, so its copy of " +
                    "${TWO_HALVES.joinToString(" and ")} is unchecked — add one to BACKGROUND_FETCH",
            )
            TWO_HALVES.mapNotNull { key ->
                val text = values[key].orEmpty()
                if (marker in text) {
                    null
                } else {
                    "$locale/$key: expected the words for the background fetch, <$marker>, and the " +
                        "label does not have them, so it promises only the notification — <$text>"
                }
            }
        }
        assertEquals("the background fetch goes unsaid in", emptyList<String>(), silent)
    }

    /**
     * The sentences of a label: split on a full stop / question mark / exclamation mark that ends a
     * word, keeping only the parts that look like a sentence — 20 characters or more, AND opening
     * on a capital letter.
     *
     * Both conditions were needed. Counting dots alone takes "z. B." for a sentence break; the
     * length floor alone does not save it, because the tail it leaves ("auf dem Sperrbildschirm",
     * 23 characters) clears the floor on its own — that mutation went green here before the capital
     * was required, and what kills it is that the tail of an abbreviation resumes in lower case.
     *
     * The price is a translation whose second sentence starts on a lower-case word (a "de Vries",
     * an "e-mail"): the eight languages here do not, and a rewrite that wants to must widen this
     * rule on purpose rather than by accident.
     */
    private fun sentencesOf(text: String): List<String> = text.split(SENTENCE_END)
        .map { it.trim() }
        .filter { it.length >= 20 && it.firstOrNull(Char::isLetter)?.isUpperCase() == true }

    /** Each `<string>`'s text exactly as the file carries it, escapes included. */
    private fun valuesOf(file: File): Map<String, String> = STRING
        .findAll(file.readText())
        .associate { it.groupValues[1] to it.groupValues[2] }

    /** Every `%s` / `%1$s` / `%d` … a string carries, as a set (order is the translator's to choose). */
    private fun placeholdersOf(file: File): Map<String, Set<String>> = STRING
        .findAll(file.readText())
        .associate { it.groupValues[1] to PLACEHOLDER.findAll(it.groupValues[2]).map { m -> m.value }.toSet() }

    private fun translations(): List<File> = (res.listFiles() ?: emptyArray<File>())
        .filter { it.isDirectory && it.name.startsWith("values-") }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private fun keysOf(file: File): Set<String> = NAME
        .findAll(file.readText())
        .map { it.groupValues[1] }
        .toSet()

    private companion object {
        /** The name of a `<string>` or `<plurals>`, which is what has to match across languages. */
        val NAME = Regex("<(?:string|plurals)\\s+name=\"([^\"]+)\"")

        /** A `<string>` with its text, for the format-argument check. */
        val STRING = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        /** A Java format specifier as Android uses them: `%s`, `%d`, `%1${'$'}s`, `%2${'$'}d`. */
        val PLACEHOLDER = Regex("%(?:\\d+\\$)?[sd]")

        /** End of a sentence: the punctuation, and then a space or the end of the label. */
        val SENTENCE_END = Regex("[.!?…](?=\\s|${'$'})")

        const val SUBTITLE = "settings_account_notifications_subtitle"

        /** The note shown under the switch when the account is not among the watched ones. */
        const val UNWATCHED_NOTE = "settings_account_notifications_unwatched_note"

        /**
         * The labels that owe the reader TWO statements, not one. Both are about the same flag, so
         * both are checked by the same shape rule.
         */
        val TWO_HALVES = listOf(SUBTITLE, UNWATCHED_NOTE)

        /**
         * The words each language uses for half (a) — the fetch that happens in the background —
         * as they stand in BOTH labels of [TWO_HALVES] today. Checked against the resources when
         * written: every one of the eighteen values carries its language's marker verbatim.
         *
         * A fragment, not a sentence, and deliberately the shortest one that cannot be said by
         * accident while talking about notifications alone ("w tle", "фоновом режиме"). Rewording
         * a label around a different phrase is allowed and means editing this map ON PURPOSE — the
         * one thing that must not happen is the phrase disappearing while nobody notices.
         */
        val BACKGROUND_FETCH = mapOf(
            "values" to "in the background",
            "values-de" to "im Hintergrund",
            "values-es" to "en segundo plano",
            "values-fr" to "arrière-plan",
            "values-it" to "in secondo piano",
            "values-nl" to "de achtergrond",
            "values-pl" to "w tle",
            "values-pt" to "em segundo plano",
            "values-ru" to "фоновом режиме",
        )

        /**
         * The three labels of the read-receipt switch, in English, whole.
         *
         * ⚠ The heading is hyphenated on purpose. `ReadReceiptMailTest` screens
         * `values/strings.xml` for the MDN's OWN fixed English — "Read receipt", the subject that
         * leaves the device — so that nobody "tidies" it into a translatable resource where the
         * nine languages would claim it. A section titled "Read receipts" collides with that guard
         * as a plain substring; "Read-receipt requests" is also the truer name, since what the
         * section governs is what happens to a request somebody else made.
         */
        /**
         * The six labels of the read-receipt strip, in English, whole.
         *
         * ⚠ None of these is what LEAVES the device: the mail itself is fixed English built by
         * `readReceiptPreview`, and `ReadReceiptMailTest` screens this very file to keep it out of
         * the resources — a correspondent's client does not speak the reader's language. Only the
         * interface is translated, which is what these six are.
         */
        val READ_RECEIPT_STRIP_ENGLISH = mapOf(
            "message_read_receipt_ask" to "%1\$s asked to be told this message was displayed",
            "message_read_receipt_send" to "Send",
            // The same button, wearing what became of the gesture. ⛔ "Queued", never "Sent": the
            // outbox is the furthest this app can honestly claim to have got.
            "message_read_receipt_send_sending" to "Sending…",
            "message_read_receipt_send_queued" to "Queued",
            "message_read_receipt_decline" to "Do not answer this request",
            "message_read_receipt_sending" to "Putting an answer in the outbox…",
            "message_read_receipt_queued" to "In the outbox: %1\$s",
            "message_read_receipt_failed" to
                "Nothing was put in the outbox, so nothing will be sent.",
        )

        /**
         * What a receipt's own strip may never claim, in lower case.
         *
         * Three families, and each has a victim. Saying the message was READ tells a stranger
         * something nobody knows — an MDN reports a display, and the reader may well have shut the
         * message without a glance. Saying it was SENT names a delivery this app never watched:
         * the receipt is in an outbox that retries, offline, for as long as it takes. Saying it
         * happens AUTOMATICALLY describes the "always" position `SECURITY.md` says this app does
         * not have.
         */
        val FORBIDDEN_OF_A_RECEIPT = listOf(
            "you read", "was read", "has read", "read your", "read it", "read the message",
            "was sent", "has been sent", "receipt sent", "already sent",
            "automatic", "silent", "transparent", "in the background",
        )

        val READ_RECEIPT_ENGLISH = mapOf(
            "settings_read_receipt_section" to "Read-receipt requests",
            "settings_read_receipt_title" to "Ask before sending a read receipt",
            "settings_read_receipt_subtitle" to "Some senders ask to be told when their message " +
                "is displayed. Off, the request is ignored. On, the message you open offers to " +
                "answer it, and nothing leaves unless you say so.",
        )

        /** The two labels of the unreadable-accounts startup screen, in English, whole. */
        val UNREADABLE_ENGLISH = mapOf(
            "accounts_unreadable_title" to "Your accounts could not be read",
            "accounts_unreadable_body" to "Sterna Mail could not read the accounts saved on this " +
                "device. Nothing has been deleted: they are still there, and nothing will be " +
                "written over them. Updating Sterna Mail to the latest version may bring them back.",
        )

        /** The four labels of the per-account switch, in English, whole. */
        val ENGLISH = mapOf(
            "settings_account_notifications_section" to "Notifications",
            "settings_account_notifications_title" to "Sync new mail",
            SUBTITLE to "Fetches this account\\'s new mail in the background and notifies you. " +
                "When off, its mail arrives only when you open the app or pull to refresh.",
            UNWATCHED_NOTE to "This account is not being watched. Turn on “Push for all accounts” " +
                "to fetch its new mail in the background and be notified about it.",
        )

        /**
         * The translations that read exactly as the English does and are right anyway, written as
         * the value each one owes: French for "Notifications" is "Notifications".
         *
         * What is pinned is that word, not the fact of being equal to English. Rewording the
         * heading therefore means editing TWO maps — this one and [ENGLISH] — and forgetting this
         * one is the failure the rule catches, because French would then carry the new English
         * text instead of "Notifications".
         *
         * What is NOT pinned, and cannot be from here: that this string is the one the section
         * heading actually shows. This file reads XML as text; it never touches a call site. A
         * rename or a heading wired to another key leaves this map green and says nothing.
         */
        val TRANSLATED = mapOf(
            "values-fr" to mapOf("settings_account_notifications_section" to "Notifications"),
        )

        /** Repo root, found by walking up from the module's working directory. */
        val res: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/res") }
                .firstOrNull { File(it, "values/strings.xml").isFile }
                ?: error(
                    "cannot locate app/src/main/res from ${File("").absolutePath} — this test reads " +
                        "the resources as text and needs a working directory inside the checkout",
                )
        }
    }
}
