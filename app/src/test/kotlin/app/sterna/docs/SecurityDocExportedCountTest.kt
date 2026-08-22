package app.sterna.docs

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * `SECURITY.md` says how many components this app exports. This test makes that sentence answer to
 * the manifest.
 *
 * WHY. The published inventory has drifted TWICE without a word: it was written when there really
 * were two exported components (`MainActivity`, `BootReceiver`), then the unread-counter widget
 * provider was added, then the recent-mail one — the manifest carries FOUR today while the
 * document still reads "Two components are exported". Nothing in the tree confronted the prose
 * with the declaration, so nobody saw either drift. A security document that under-reports its own
 * attack surface is worse than no document: a reader counts two and stops looking.
 *
 * ⛔ WHY THIS IS NOT A `contains`. The repo has been bitten three times by source lints that assert
 * a FRAGMENT belongs to a file: `"exported" in text` stays green when the line grows, is rewritten,
 * or moves, so it measures the presence of a word and not the truth of a claim. Here a NUMBER is
 * read from each side and the two numbers are compared. The manifest side counts the component tags
 * that actually carry `android:exported="true"`; the prose side reads the English number-word out of
 * the inventory sentence and converts it to an integer, refusing loudly on a word it does not know
 * rather than answering 0 (a silent 0 would turn "Eleven components are exported" into a green run).
 *
 * HOW IT IS BUILT, following [app.sterna.bench.NoPrivateHostInTreeTest]:
 *
 *  - The two decisions are PURE FUNCTIONS, [exportedComponents] and [announcedExportedCount],
 *    EXECUTED against arguments pinned in [theManifestDecisionIsExecutedOnAPinnedFragment] and
 *    [theProseDecisionIsExecutedOnAPinnedFragment]. A test that re-derived either rule to choose
 *    what to feed it would be a copy of the rule.
 *  - The pinned manifest fragment is a hand-written one whose count is known by reading it, and it
 *    is passed through [DeclarationSource.codeLines] like the real one — so the fragment can carry
 *    an `<!-- … exported="true" … -->` comment and prove the prose explaining a rule is not counted
 *    as the rule.
 *  - THE POSITIVE CASES are those fragments: the lint above compares the checkout against itself,
 *    so gutting a decision to `emptyList()` or `0` would leave it comparing two equally wrong
 *    numbers. The pinned fragments are what go red then — verified by mutation, both ways.
 *
 * ⭐ This test is red on purpose the day it lands: the manifest exports four, the document announces
 * two. Fixing the DOCUMENT is what turns it green — never this test, and never the manifest.
 */
class SecurityDocExportedCountTest {

    /** THE LINT: today's manifest against today's `SECURITY.md`. */
    @Test
    fun securityDocAnnouncesAsManyExportedComponentsAsTheManifestDeclares() {
        val manifest = DeclarationSource.file(MANIFEST_PATH)
        val doc = DeclarationSource.file(DOC_PATH)

        val declared = exportedComponents(codeOf(manifest))
        val announced = announcedExportedCount(doc.readText())

        if (declared.size != announced.number) {
            fail(
                "$DOC_PATH announces ${announced.number} exported component(s), " +
                    "$MANIFEST_PATH declares ${declared.size}.\n" +
                    "  declared (android:exported=\"true\"): ${declared.joinToString(", ")}\n" +
                    "  announced at $DOC_PATH:${announced.line}: ${announced.sentence}\n" +
                    "\nFix the DOCUMENT, not this test and not the manifest: " +
                    "${rewriteInstruction(declared.size)} and describe the " +
                    "${declared.size} components below it — the inventory is read by people " +
                    "deciding whether to audit further, and an under-count tells them to stop " +
                    "looking. If a component genuinely should not be exported, remove the " +
                    "attribute from $MANIFEST_PATH instead.",
            )
        }
    }

    /**
     * THE POSITIVE CASE, manifest half. The decision executed against a fragment written HERE,
     * whose answer is known by reading it rather than by running the code. Without this, emptying
     * the function body leaves the lint above comparing 0 to 0.
     */
    @Test
    fun theManifestDecisionIsExecutedOnAPinnedFragment() {
        val fragment = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="Pinned">
                    <!-- A comment that says android:exported="true" is prose, not a declaration:
                         this is exactly the sentence shape used above the boot receiver. -->
                    <activity
                        android:name=".MainActivity"
                        android:exported="true"
                        android:launchMode="singleTask">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                        </intent-filter>
                    </activity>
                    <receiver android:name=".push.BootReceiver" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.BOOT_COMPLETED" />
                        </intent-filter>
                    </receiver>
                    <receiver
                        android:name=".push.NotificationActionReceiver"
                        android:exported="false" />
                    <service android:name=".push.PushService" android:exported="false" />
                    <provider
                        android:name="androidx.core.content.FileProvider"
                        android:exported="false"
                        android:grantUriPermissions="true" />
                </application>
            </manifest>
        """.trimIndent()

        // Four claims: the attribute counts on its own line AND inline on the opening tag, `false`
        // is not `true`, a commented-out mention is not a declaration, and the ORDER of the names
        // is the order of the file (so the failure message reads like the manifest).
        assertEquals(
            listOf(".MainActivity", ".push.BootReceiver"),
            exportedComponents(codeOf(write("pinned-manifest", ".xml", fragment))),
        )
    }

    /**
     * THE POSITIVE CASE, prose half. Two decoys are planted on purpose: one BEFORE the section and
     * one AFTER it, both perfectly shaped inventory sentences with a different number. Widening the
     * search to the whole document therefore answers 9 or 7 instead of 3, and this goes red.
     */
    @Test
    fun theProseDecisionIsExecutedOnAPinnedFragment() {
        val prose = """
            ## Threat model

            - Nine components are exported, in an earlier section this reader must not read.

            ### Android platform surface

            - Three components are exported. The first is the launcher `MainActivity`, and it
              reads untrusted input from three kinds of entry point.
            - The second is `BootReceiver`, which restarts the push connection after a reboot.

            ## Coordinated disclosure

            - Seven components are exported, in a later section this reader must not read.
        """.trimIndent()

        val announced = announcedExportedCount(prose)
        assertEquals(3, announced.number)
        assertEquals(7, announced.line) // the sentence, not merely the number, is located
        assertEquals(
            "- Three components are exported. The first is the launcher `MainActivity`, and it",
            announced.sentence,
        )
    }

    /**
     * The section boundary, the other way round: an inventory sentence that lives only in a LATER
     * section must not answer for this one. The reader is expected to stop at the next heading and
     * say so, rather than quietly report a number from somewhere else in the document.
     */
    @Test
    fun theInventoryIsReadOnlyInsideItsOwnSection() {
        val prose = """
            ### Android platform surface

            - Nothing here announces a count.

            ## Coordinated disclosure

            - Four components are exported.
        """.trimIndent()

        val outcome = runCatching { announcedExportedCount(prose) }
        if (outcome.isSuccess) {
            fail(
                "a sentence from a LATER section answered for the platform-surface inventory " +
                    "(got ${outcome.getOrNull()}) — the reader no longer stops at the next heading",
            )
        }
    }

    /**
     * A `#` at column 0 inside a fenced code block is a shell comment, not a heading. Without the
     * fence guard the section ends at that line, the inventory sentence below it is never reached,
     * and the lint reddens over a doc edit that changed nothing it guards.
     */
    @Test
    fun aHashInsideAFencedBlockDoesNotEndTheSection() {
        val prose = """
            ### Android platform surface

            Count them yourself:

            ```bash
            # every exported component of the published app
            grep -c 'exported="true"' app/src/main/AndroidManifest.xml
            ```

            - Four components are exported. The first is the launcher `MainActivity`.
        """.trimIndent()

        assertEquals(4, announcedExportedCount(prose).number)
    }

    /** The word-to-integer half, executed word by word — including the loud refusal. */
    @Test
    fun everyNumberWordIsReadAsItsInteger() {
        val pinned = mapOf(
            "one" to 1, "Two" to 2, "three" to 3, "Four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "TEN" to 10,
            "eleven" to 11, "twelve" to 12,
        )
        val wrong = pinned.filter { (word, expected) -> runCatching { toInt(word) }.getOrNull() != expected }
        if (wrong.isNotEmpty()) {
            fail("the number-word reader no longer reads ${wrong.keys.joinToString(", ")} as pinned")
        }

        // ⛔ An unknown word must NOT quietly become 0: a 0 read from the prose would compare equal
        // to a manifest that exports nothing, and the day someone writes "Thirteen" the lint would
        // go green instead of asking to be taught the word.
        val unknown = runCatching { toInt("umpteen") }
        if (unknown.isSuccess) {
            fail("an unknown number-word returned ${unknown.getOrNull()} instead of failing loudly")
        }
        assertEquals(true, unknown.exceptionOrNull()?.message?.contains("umpteen") == true)
    }

    /** A carrier of the attribute on a tag this lint does not know about must stop the run. */
    @Test
    fun anExportedAttributeOnAnUnknownTagFailsLoudly() {
        val fragment = """
            <manifest>
                <application>
                    <not-a-component android:name=".Odd" android:exported="true" />
                </application>
            </manifest>
        """.trimIndent()
        val outcome = runCatching { exportedComponents(codeOf(write("odd-manifest", ".xml", fragment))) }
        if (outcome.isSuccess) {
            fail(
                "an android:exported=\"true\" outside a known component tag was silently ignored " +
                    "(got ${outcome.getOrNull()}) — that is a component missing from the count",
            )
        }
    }

    private fun codeOf(file: File): String = DeclarationSource.codeLines(file).joinToString("\n")

    private fun write(name: String, suffix: String, text: String): File =
        File.createTempFile(name, suffix).apply { deleteOnExit(); writeText(text) }

    private companion object {

        const val MANIFEST_PATH = "app/src/main/AndroidManifest.xml"
        const val DOC_PATH = "SECURITY.md"

        /** The heading whose bullet list carries the inventory sentence. */
        const val SECTION = "### Android platform surface"

        /** Opening tags of the things Android calls components — attributes live only in there. */
        val COMPONENT_TAG = Regex("<(activity|activity-alias|service|receiver|provider)\\b[^>]*>")

        val EXPORTED_TRUE = Regex("android:exported\\s*=\\s*\"true\"")

        val NAME_ATTR = Regex("android:name\\s*=\\s*\"([^\"]*)\"")

        /**
         * The inventory sentence: a bullet opening on an English number-word, "components are
         * exported". Anchored on the bullet so a number-word in the middle of a paragraph cannot
         * answer for the inventory.
         */
        val INVENTORY = Regex("^-\\s+([A-Za-z]+)\\s+components?\\s+are\\s+exported\\b")

        val WORDS = listOf(
            "one", "two", "three", "four", "five", "six",
            "seven", "eight", "nine", "ten", "eleven", "twelve",
        )

        /**
         * DECISION 1, pure. The components that really carry `android:exported="true"`, by name,
         * out of manifest text whose comments are already gone.
         *
         * It refuses to answer when the attribute appears on a tag it does not know: an unknown
         * carrier is a component missing from the count, which is precisely the drift this lint
         * exists to catch.
         */
        fun exportedComponents(manifestCode: String): List<String> {
            val carriers = COMPONENT_TAG.findAll(manifestCode)
                .filter { EXPORTED_TRUE.containsMatchIn(it.value) }
                .map { NAME_ATTR.find(it.value)?.groupValues?.get(1) ?: "(unnamed)" }
                .toList()
            val occurrences = EXPORTED_TRUE.findAll(manifestCode).count()
            check(occurrences == carriers.size) {
                "found $occurrences android:exported=\"true\" but only ${carriers.size} on a known " +
                    "component tag (${COMPONENT_TAG.pattern}) — teach this lint the new tag, or " +
                    "the inventory it guards will under-count"
            }
            return carriers
        }

        /** DECISION 2, pure: the number the document announces, and where it says it. */
        fun announcedExportedCount(docText: String): Announced {
            val lines = docText.lines()
            val start = lines.indexOfFirst { it.trim() == SECTION }
            check(start >= 0) {
                "no `$SECTION` heading in $DOC_PATH — the inventory sentence lives under it; if the " +
                    "section was renamed, rename SECTION here too"
            }
            // ⛔ Fenced code is skipped, not read for headings: a `# comment` at column 0 inside a
            // shell snippet is not the end of the section, and truncating there would redden the
            // suite over a purely cosmetic doc edit.
            var inFence = false
            val end = (start + 1 until lines.size).firstOrNull {
                val line = lines[it]
                if (line.trimStart().startsWith("```")) {
                    inFence = !inFence
                    false
                } else {
                    !inFence && line.startsWith("#")
                }
            } ?: lines.size
            for (i in start + 1 until end) {
                val match = INVENTORY.find(lines[i].trim()) ?: continue
                return Announced(toInt(match.groupValues[1]), lines[i].trim(), i + 1)
            }
            error(
                "no \"<number> components are exported\" sentence under `$SECTION` in $DOC_PATH — " +
                    "the published inventory is what this lint compares the manifest against, so a " +
                    "reworded section must reword this pattern (${INVENTORY.pattern}) with it",
            )
        }

        /** The English number-word as an integer. Loud on anything it has not been taught. */
        fun toInt(word: String): Int {
            val at = WORDS.indexOf(word.lowercase())
            require(at >= 0) {
                "\"$word\" is not a number-word this lint knows (${WORDS.first()}..${WORDS.last()}) " +
                    "— add it to WORDS rather than letting the inventory go unchecked"
            }
            return at + 1
        }

        /**
         * The inverse, for the failure message. ⛔ Never a DIGIT above [WORDS]: the sentence
         * pattern reads a word, so "13 components are exported" would satisfy the instruction and
         * leave the lint failing on a second, more confusing message.
         */
        fun rewriteInstruction(n: Int): String {
            val word = WORDS.getOrNull(n - 1)?.replaceFirstChar { it.uppercase() }
                ?: return "teach WORDS the English number-word for $n, then open that sentence " +
                    "with it (the pattern reads a word, never a digit)"
            return "rewrite that sentence to \"$word components are exported\""
        }
    }

    data class Announced(val number: Int, val sentence: String, val line: Int)
}
