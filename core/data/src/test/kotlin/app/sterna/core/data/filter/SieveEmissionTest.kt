package app.sterna.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this app emits as Sieve, held still, because every script already sitting on a server was
 * written by it, and reading one now compares its Sieve to what this codec would write today.
 */
class SieveEmissionTest {

    // ---- the golden witness: the codec, executed ----

    /** No rule at all: the marker, and nothing to run. */
    @Test fun `an empty rule list emits the marker alone`() {
        assertEquals(
            script("# STERNA-RULES-V1: []", ""),
            SieveCodec.generate(emptyList()),
        )
    }

    /** No action anywhere, so no `require` at all; `:is` on a header test; a disabled rule kept
     *  in the metadata and not emitted. */
    @Test fun `a rule with no action emits no require`() {
        val rules = listOf(
            FilterRule(name = "plain", field = RuleField.SUBJECT, match = RuleMatch.IS, value = "Hi"),
            FilterRule(
                name = "off", enabled = false, field = RuleField.FROM, match = RuleMatch.CONTAINS,
                value = "x@y.com", moveTo = "Work",
            ),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"plain","enabled":true,"field":"SUBJECT","match":"IS",""" +
                    """"value":"Hi","moveTo":null,"markRead":false,"flag":false},""" +
                    """{"name":"off","enabled":false,"field":"FROM","match":"CONTAINS",""" +
                    """"value":"x@y.com","moveTo":"Work","markRead":false,"flag":false}]""",
                "",
                """if header :is "subject" "Hi" {""",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    /** `fileinto` alone, on the two address fields that are not `cc`, both match tags. */
    @Test fun `moving mail emits the fileinto require only`() {
        val rules = listOf(
            FilterRule(
                name = "boss", field = RuleField.FROM, match = RuleMatch.CONTAINS,
                value = "boss@example.com", moveTo = "Work",
            ),
            FilterRule(
                name = "list", field = RuleField.TO, match = RuleMatch.IS,
                value = "list@example.com", moveTo = "Lists/In",
            ),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"boss","enabled":true,"field":"FROM","match":"CONTAINS",""" +
                    """"value":"boss@example.com","moveTo":"Work","markRead":false,"flag":false},""" +
                    """{"name":"list","enabled":true,"field":"TO","match":"IS",""" +
                    """"value":"list@example.com","moveTo":"Lists/In","markRead":false,"flag":false}]""",
                """require ["fileinto"];""",
                "",
                """if address :contains "from" "boss@example.com" {""",
                """    fileinto "Work";""",
                "}",
                """if address :is "to" "list@example.com" {""",
                """    fileinto "Lists/In";""",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    /** Flags alone, on `cc`: `imap4flags` only, and Seen before Flagged. */
    @Test fun `flagging mail emits the imap4flags require only`() {
        val rules = listOf(
            FilterRule(
                name = "cc", field = RuleField.CC, match = RuleMatch.CONTAINS,
                value = "team@example.com", markRead = true, flag = true,
            ),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"cc","enabled":true,"field":"CC","match":"CONTAINS",""" +
                    """"value":"team@example.com","moveTo":null,"markRead":true,"flag":true}]""",
                """require ["imap4flags"];""",
                "",
                """if address :contains "cc" "team@example.com" {""",
                """    addflag "\\Seen";""",
                """    addflag "\\Flagged";""",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    /**
     * Both requires at once, the flags emitted before the `fileinto` so the filed copy carries
     */
    @Test fun `moving and flagging emits both requires, escaped`() {
        val rules = listOf(
            FilterRule(
                name = "odd", field = RuleField.SUBJECT, match = RuleMatch.CONTAINS,
                value = """a"b\c""", moveTo = """Odd"Folder\X""", markRead = true,
            ),
            FilterRule(name = "blank", value = "   ", flag = true),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"odd","enabled":true,"field":"SUBJECT","match":"CONTAINS",""" +
                    """"value":"a\"b\\c","moveTo":"Odd\"Folder\\X","markRead":true,"flag":false},""" +
                    """{"name":"blank","enabled":true,"field":"FROM","match":"CONTAINS",""" +
                    """"value":"   ","moveTo":null,"markRead":false,"flag":true}]""",
                """require ["fileinto", "imap4flags"];""",
                "",
                """if header :contains "subject" "a\"b\\c" {""",
                """    addflag "\\Seen";""",
                """    fileinto "Odd\"Folder\\X";""",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    /** A flag-only rule that is not marked read: the second `addflag` branch, alone. */
    @Test fun `flagging without marking read emits the Flagged addflag alone`() {
        val rules = listOf(
            FilterRule(name = "star", field = RuleField.FROM, match = RuleMatch.IS, value = "a@b.c", flag = true),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"star","enabled":true,"field":"FROM","match":"IS",""" +
                    """"value":"a@b.c","moveTo":null,"markRead":false,"flag":true}]""",
                """require ["imap4flags"];""",
                "",
                """if address :is "from" "a@b.c" {""",
                """    addflag "\\Flagged";""",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    private val boss = FilterRule(
        name = "boss", field = RuleField.FROM, match = RuleMatch.IS, value = "boss@example.com", moveTo = "Work",
    )
    private val bossMarker =
        """# STERNA-RULES-V1: [{"name":"boss","enabled":true,"field":"FROM","match":"IS",""" +
            """"value":"boss@example.com","moveTo":"Work","markRead":false,"flag":false}]"""
    private val bossSieve = arrayOf("""if address :is "from" "boss@example.com" {""", """    fileinto "Work";""", "}")

    private val away = SieveVacation(
        isEnabled = true, subject = "Away", textBody = "Back soon", addresses = listOf("me@example.com"),
    )

    @Test fun `an undated auto-reply emits the bare vacation command after the rules`() {
        val vacation = SieveVacation(
            isEnabled = true, subject = """Away "now"""", textBody = "Back on Monday.\nUrgent: a\\b",
            addresses = listOf("me@example.com", "alias@example.com"),
        )
        assertEquals(
            script(
                bossMarker,
                """# STERNA-VACATION-V1: {"isEnabled":true,"fromDate":null,"toDate":null,""" +
                    """"subject":"Away \"now\"","textBody":"Back on Monday.\nUrgent: a\\b",""" +
                    """"addresses":["me@example.com","alias@example.com"],"trashFolder":null}""",
                """require ["fileinto", "vacation"];""",
                "",
                *bossSieve,
                """vacation :subject "Away \"now\"" :addresses ["me@example.com", "alias@example.com"] "Back on Monday.""",
                """Urgent: a\\b";""",
            ),
            SieveCodec.generate(listOf(boss), vacation),
        )
    }

    @Test fun `an auto-reply with a start day tests currentdate ge that day`() {
        assertEquals(
            script(
                "# STERNA-RULES-V1: []",
                """# STERNA-VACATION-V1: {"isEnabled":true,"fromDate":"2026-09-14T00:00:00Z","toDate":null,""" +
                    """"subject":"Away","textBody":"Back soon","addresses":["me@example.com"],"trashFolder":null}""",
                """require ["vacation", "date", "relational"];""",
                "",
                """if currentdate :zone "+0000" :value "ge" "date" "2026-09-14" {""",
                """    vacation :subject "Away" :addresses ["me@example.com"] "Back soon";""",
                "}",
            ),
            SieveCodec.generate(emptyList(), away.copy(fromDate = "2026-09-14T00:00:00Z")),
        )
    }

    @Test fun `an auto-reply with an end day tests currentdate le that day`() {
        assertEquals(
            script(
                "# STERNA-RULES-V1: []",
                """# STERNA-VACATION-V1: {"isEnabled":true,"fromDate":null,"toDate":"2026-09-20T23:59:59Z",""" +
                    """"subject":"Away","textBody":"Back soon","addresses":["me@example.com"],"trashFolder":null}""",
                """require ["vacation", "date", "relational"];""",
                "",
                """if currentdate :zone "+0000" :value "le" "date" "2026-09-20" {""",
                """    vacation :subject "Away" :addresses ["me@example.com"] "Back soon";""",
                "}",
            ),
            SieveCodec.generate(emptyList(), away.copy(toDate = "2026-09-20T23:59:59Z")),
        )
    }

    @Test fun `an auto-reply with both days tests allof ge and le`() {
        val star = FilterRule(name = "star", field = RuleField.FROM, match = RuleMatch.IS, value = "a@b.c", flag = true)
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"star","enabled":true,"field":"FROM","match":"IS",""" +
                    """"value":"a@b.c","moveTo":null,"markRead":false,"flag":true}]""",
                """# STERNA-VACATION-V1: {"isEnabled":true,"fromDate":"2026-09-14T00:00:00Z",""" +
                    """"toDate":"2026-09-20T23:59:59Z","subject":"Away","textBody":"Back soon",""" +
                    """"addresses":["me@example.com"],"trashFolder":null}""",
                """require ["imap4flags", "vacation", "date", "relational"];""",
                "",
                """if address :is "from" "a@b.c" {""",
                """    addflag "\\Flagged";""",
                "}",
                """if allof (currentdate :zone "+0000" :value "ge" "date" "2026-09-14", """ +
                    """currentdate :zone "+0000" :value "le" "date" "2026-09-20") {""",
                """    vacation :subject "Away" :addresses ["me@example.com"] "Back soon";""",
                "}",
            ),
            SieveCodec.generate(
                listOf(star),
                away.copy(fromDate = "2026-09-14T00:00:00Z", toDate = "2026-09-20T23:59:59Z"),
            ),
        )
    }

    @Test fun `a disabled auto-reply emits its marker and no vacation block`() {
        assertEquals(
            script(
                bossMarker,
                """# STERNA-VACATION-V1: {"isEnabled":false,"fromDate":"2026-09-14T00:00:00Z",""" +
                    """"toDate":"2026-09-20T23:59:59Z","subject":"Away","textBody":"Back soon",""" +
                    """"addresses":["me@example.com"],"trashFolder":null}""",
                """require ["fileinto"];""",
                "",
                *bossSieve,
            ),
            SieveCodec.generate(
                listOf(boss),
                away.copy(isEnabled = false, fromDate = "2026-09-14T00:00:00Z", toDate = "2026-09-20T23:59:59Z"),
            ),
        )
    }

    @Test fun `an auto-reply with no subject and no address emits the reason alone`() {
        for (subject in listOf(null, "   ")) {
            val subjectJson = if (subject == null) "null" else "\"$subject\""
            assertEquals(
                script(
                    "# STERNA-RULES-V1: []",
                    """# STERNA-VACATION-V1: {"isEnabled":true,"fromDate":null,"toDate":null,""" +
                        """"subject":$subjectJson,"textBody":null,"addresses":[],"trashFolder":null}""",
                    """require ["vacation"];""",
                    "",
                    """vacation "";""",
                ),
                SieveCodec.generate(emptyList(), SieveVacation(isEnabled = true, subject = subject)),
            )
        }
    }

    @Test fun `no auto-reply emits exactly the V1 script`() {
        assertEquals(
            script(bossMarker, """require ["fileinto"];""", "", *bossSieve),
            SieveCodec.generate(listOf(boss), vacation = null),
        )
    }

    private val spam = blockRule("spam@example.com", "Trash")
    private val spamMarker =
        """# STERNA-RULES-V1: [{"name":"spam@example.com","enabled":true,"field":"FROM","match":"IS",""" +
            """"value":"spam@example.com","moveTo":"Trash","markRead":true,"flag":false}]"""
    private val spamSieve = arrayOf(
        """if address :is "from" "spam@example.com" {""",
        """    addflag "\\Seen";""",
        """    fileinto "Trash";""",
        "}",
    )

    @Test fun `a blocked sender is left out of an undated auto-reply`() {
        assertEquals(
            script(
                spamMarker,
                """# STERNA-VACATION-V1: {"isEnabled":true,"fromDate":null,"toDate":null,""" +
                    """"subject":"Away","textBody":"Back soon","addresses":["me@example.com"],"trashFolder":"Trash"}""",
                """require ["fileinto", "imap4flags", "vacation"];""",
                "",
                *spamSieve,
                """if not anyof (address :is "from" "spam@example.com") {""",
                """    vacation :subject "Away" :addresses ["me@example.com"] "Back soon";""",
                "}",
            ),
            SieveCodec.generate(listOf(spam), away.copy(trashFolder = "Trash")),
        )
    }

    @Test fun `rules into the Trash are left out of a dated auto-reply, beside both days`() {
        val promo = FilterRule(name = "promo", field = RuleField.SUBJECT, match = RuleMatch.CONTAINS, value = "Promo", moveTo = "Trash")
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"spam@example.com","enabled":true,"field":"FROM","match":"IS",""" +
                    """"value":"spam@example.com","moveTo":"Trash","markRead":true,"flag":false},""" +
                    """{"name":"boss","enabled":true,"field":"FROM","match":"IS",""" +
                    """"value":"boss@example.com","moveTo":"Work","markRead":false,"flag":false},""" +
                    """{"name":"promo","enabled":true,"field":"SUBJECT","match":"CONTAINS",""" +
                    """"value":"Promo","moveTo":"Trash","markRead":false,"flag":false}]""",
                """# STERNA-VACATION-V1: {"isEnabled":true,"fromDate":"2026-09-14T00:00:00Z",""" +
                    """"toDate":"2026-09-20T23:59:59Z","subject":"Away","textBody":"Back soon",""" +
                    """"addresses":["me@example.com"],"trashFolder":"Trash"}""",
                """require ["fileinto", "imap4flags", "vacation", "date", "relational"];""",
                "",
                *spamSieve,
                *bossSieve,
                """if header :contains "subject" "Promo" {""",
                """    fileinto "Trash";""",
                "}",
                """if allof (not anyof (address :is "from" "spam@example.com", header :contains "subject" "Promo"), """ +
                    """currentdate :zone "+0000" :value "ge" "date" "2026-09-14", """ +
                    """currentdate :zone "+0000" :value "le" "date" "2026-09-20") {""",
                """    vacation :subject "Away" :addresses ["me@example.com"] "Back soon";""",
                "}",
            ),
            SieveCodec.generate(
                listOf(spam, boss, promo),
                away.copy(fromDate = "2026-09-14T00:00:00Z", toDate = "2026-09-20T23:59:59Z", trashFolder = "Trash"),
            ),
        )
    }

    @Test fun `a blocked sender is left out of an auto-reply with one day`() {
        val script = SieveCodec.generate(listOf(spam), away.copy(toDate = "2026-09-20T23:59:59Z", trashFolder = "Trash"))
        assertEquals(
            """if allof (not anyof (address :is "from" "spam@example.com"), """ +
                """currentdate :zone "+0000" :value "le" "date" "2026-09-20") {""",
            script.lines()[script.lines().indexOf("}") + 1],
        )
    }

    @Test fun `nothing is left out when no active rule files into the Trash the marker names`() {
        val bare = """vacation :subject "Away" :addresses ["me@example.com"] "Back soon";"""
        val cases = listOf(
            "a rule into another folder, marked read" to SieveCodec.generate(
                listOf(blockRule("spam@example.com", "Junk")), away.copy(trashFolder = "Trash"),
            ),
            "a disabled block rule" to SieveCodec.generate(
                listOf(spam.copy(enabled = false)), away.copy(trashFolder = "Trash"),
            ),
            "a blank block rule" to SieveCodec.generate(
                listOf(spam.copy(value = " ")), away.copy(trashFolder = "Trash"),
            ),
            "an unknown Trash" to SieveCodec.generate(listOf(spam), away.copy(trashFolder = null)),
        )
        for ((what, script) in cases) {
            assertEquals(what, bare, script.trimEnd('\n').lines().last())
            assertFalse("$what: $script", "anyof" in script)
        }
    }

    @Test fun `the blocked sender's message does not pass the auto-reply's condition`() {
        val lines = SieveCodec.generate(listOf(spam), away.copy(trashFolder = "Trash")).lines()
        val fileinto = lines.indexOf("""    fileinto "Trash";""")
        val blockTest = lines.take(fileinto).last { it.startsWith("if ") }.removePrefix("if ").removeSuffix(" {")
        val vacation = lines.indexOfFirst { it.trimStart().startsWith("vacation ") }
        assertEquals("if not anyof ($blockTest) {", lines[vacation - 1])
    }

    @Test fun `a Chinese subject and text are kept as UTF-8 in the marker and the block`() {
        val script = SieveCodec.generate(emptyList(), away.copy(subject = "休假中", textBody = "我下周一回来。"))
        val bytes = script.toByteArray(Charsets.UTF_8)
        val subject = bytesOf(0xE4, 0xBC, 0x91, 0xE5, 0x81, 0x87, 0xE4, 0xB8, 0xAD)
        val text = bytesOf(
            0xE6, 0x88, 0x91, 0xE4, 0xB8, 0x8B, 0xE5, 0x91, 0xA8, 0xE4, 0xB8, 0x80, 0xE5, 0x9B, 0x9E, 0xE6, 0x9D,
            0xA5, 0xE3, 0x80, 0x82,
        )
        val ascii = { s: String -> s.toByteArray(Charsets.US_ASCII) }
        assertTrue(
            "the marker JSON must carry the subject as UTF-8",
            bytes.containsBytes(ascii("\"subject\":\"") + subject + ascii("\",\"textBody\":\"") + text + ascii("\"")),
        )
        assertTrue(
            "the vacation command must carry both as UTF-8",
            bytes.containsBytes(ascii("vacation :subject \"") + subject + ascii("\" :addresses [\"me@example.com\"] \"") + text + ascii("\";")),
        )
        assertFalse("no JSON unicode escape: ${script.lines()[1]}", script.contains("\\u"))
    }

    private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    private fun script(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    // ---- the source lint: a last resort on top of the golden, for the branches it does not run ----

    /**
     * Whole lines, never substrings: a `contains` is blind to every mutation that makes a line
     */
    @Test fun `the Sieve this codec emits is pinned to the marker version`() {
        val source = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/filter/SieveCodec.kt")
        assertEquals(
            V2_RULE,
            listOf(
                """fun generate(rules: List<FilterRule>, vacation: SieveVacation? = null): String {""",
                """val sb = StringBuilder()""",
                """sb.append(MARKER).append(' ').append(json.encodeToString(serializer, rules)).append('\n')""",
                """if (vacation != null) {""",
                """sb.append(VACATION_MARKER).append(' ').append(json.encodeToString(vacationSerializer, vacation)).append('\n')""",
                """}""",
                """val active = rules.filter { it.enabled && it.value.isNotBlank() }""",
                """val reply = vacation?.takeIf { it.isEnabled }""",
                """val requires = buildList {""",
                """if (active.any { it.moveTo != null }) add("fileinto")""",
                """if (active.any { it.markRead || it.flag }) add("imap4flags")""",
                """if (reply != null) add("vacation")""",
                """if (reply != null && (reply.fromDate != null || reply.toDate != null)) addAll(listOf("date", "relational"))""",
                """}""",
                """if (requires.isNotEmpty()) {""",
                """sb.append("require [").append(requires.joinToString(", ") { "\"${'$'}it\"" }).append("];\n")""",
                """}""",
                """sb.append('\n')""",
                """for (rule in active) sb.append(ruleToSieve(rule)).append('\n')""",
                """if (reply != null) sb.append(vacationToSieve(reply, active)).append('\n')""",
                """return sb.toString()""",
                """}""",
            ),
            SourceText.codeLines(
                SourceText.functionSource(source, "fun generate(rules: List<FilterRule>, vacation: SieveVacation? = null): String {"),
            ),
        )
        assertEquals(
            V2_RULE,
            listOf(
                """private fun ruleToSieve(rule: FilterRule): String {""",
                """val body = StringBuilder()""",
                """if (rule.markRead) body.append("    addflag \"\\\\Seen\";\n")""",
                """if (rule.flag) body.append("    addflag \"\\\\Flagged\";\n")""",
                """if (rule.moveTo != null) body.append("    fileinto \"${'$'}{escape(rule.moveTo)}\";\n")""",
                """return "if ${'$'}{ruleTest(rule)} {\n${'$'}body}" """.trimEnd(),
                """}""",
                """private fun ruleTest(rule: FilterRule): String {""",
                """val matchTag = if (rule.match == RuleMatch.IS) ":is" else ":contains" """.trimEnd(),
                """val value = escape(rule.value)""",
                """return when (rule.field) {""",
                """RuleField.SUBJECT -> "header ${'$'}matchTag \"subject\" \"${'$'}value\"" """.trimEnd(),
                """RuleField.FROM -> "address ${'$'}matchTag \"from\" \"${'$'}value\"" """.trimEnd(),
                """RuleField.TO -> "address ${'$'}matchTag \"to\" \"${'$'}value\"" """.trimEnd(),
                """RuleField.CC -> "address ${'$'}matchTag \"cc\" \"${'$'}value\"" """.trimEnd(),
                """}""",
                """}""",
            ),
            SourceText.codeLines(SourceText.functionSource(source, "private fun ruleToSieve(rule: FilterRule): String {")),
        )
        // `escape` is emitted Sieve too, and it was the hole: it is the last member of the
        // object, so the slice that ends at the next KDoc found nothing and it stayed unpinned.
        assertEquals(
            V2_RULE,
            listOf(
                """private fun escape(value: String): String =""",
                """value.replace("\\", "\\\\").replace("\"", "\\\"")""",
            ),
            SourceText.codeLines(SourceText.functionSource(source, "private fun escape(value: String): String =")),
        )
    }

    /**
     * The golden for [SieveCodec] escaping, and the answer to "what about a value with a newline
     */
    @Test fun `a control character in a value is emitted as-is, not escaped`() {
        val rules = listOf(
            FilterRule(name = "odd", field = RuleField.SUBJECT, match = RuleMatch.IS, value = "a\nb\tc"),
        )
        assertEquals(
            script(
                """# STERNA-RULES-V1: [{"name":"odd","enabled":true,"field":"SUBJECT","match":"IS",""" +
                    """"value":"a\nb\tc","moveTo":null,"markRead":false,"flag":false}]""",
                "",
                """if header :is "subject" "a""",
                "b\tc\" {",
                "}",
            ),
            SieveCodec.generate(rules),
        )
    }

    private companion object {
        const val V2_RULE =
            "The Sieve this codec emits has changed. Reading a script now compares the Sieve on " +
                "the server to the Sieve we would write, so every script written by an earlier " +
                "version will be reported as containing hand-written Sieve — on the whole " +
                "installed base, silently, until each user confirms an overwrite.\n" +
                "So: bump MARKER to `# STERNA-RULES-V2:` in SieveCodec, then update the golden " +
                "witnesses above (they show the emitted Sieve in full) and these pinned lines.\n" +
                "If the change emits no different Sieve — a rename, a comment — just update the " +
                "pinned lines."
    }
}
