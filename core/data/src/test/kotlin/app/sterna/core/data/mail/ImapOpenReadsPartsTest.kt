package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapOpenRead
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The open of an IMAP message reads its header, its BODYSTRUCTURE and its text sections, not its
 * whole source (#157), and the message it returns is the one the whole source gave. Run against a
 * real [ImapMailService] on a scripted socket, whose BODYSTRUCTURE and sections are cut out of each
 * source by [ServedMessage], a splitter written for this test from RFC 3501 and RFC 2046 and
 * sharing no code with the walk under test.
 */
class ImapOpenReadsPartsTest {

    // ---- 1. the equivalence, over a corpus ---------------------------------------------------------

    @Test fun `every message of the corpus opens by parts into the message its whole source gives`() {
        val failures = mutableListOf<String>()
        for ((name, source) in corpus()) {
            val served = ServedMessage(source)
            val (read, issued) = open(served)
            if (read.wholeSource) {
                failures += "$name: read whole, commands $issued"
                continue
            }
            if (issued.any { it.endsWith("(BODY.PEEK[])") }) failures += "$name: the whole source was fetched: $issued"
            if (name in MISCUT_BY_THE_WHOLE_SOURCE) continue
            val parts = emailOf(read.header, read)
            val whole = emailOf(source, ImapOpenRead(source, MimeParser.parseBody(source), source.length.toLong(), true))
            if (parts != whole.withTheDelimiterBreakOf(parts)) {
                failures += "$name:\n  parts $parts\n  whole $whole"
            }
            val declared = parts.attachments.map { it.partId to it.size }
            val octets = parts.attachments.map { it.partId to served.sections.getValue(it.partId!!).length.toLong() }
            if (declared != octets) failures += "$name: sizes $declared, the server counts $octets"
        }
        assertEquals("messages whose open by parts differs from their whole source", emptyList<String>(), failures)
    }

    /**
     * Where the two disagree, the whole source is the one that is wrong: `MimeParser` splits on the
     * first `--B5` it meets, inside the attached message's own `--B5I`, and lists a section 4 the
     * server does not have. The server knows both boundaries and takes the longest (Dovecot's
     * `boundary_find`), so the parts path keeps one attached message. Pinned so a fix of the
     * whole-source split shows up here, and the two messages rejoin the equality above.
     */
    @Test fun `an attached message whose boundary extends the outer one stays whole by parts`() {
        val sources = corpus().toMap()
        for (name in MISCUT_BY_THE_WHOLE_SOURCE) {
            val source = sources.getValue(name)
            val (read, _) = open(ServedMessage(source))
            assertEquals(name, listOf("2" to "message/rfc822"), read.body.attachments.map { it.section to it.type })
            assertEquals(name, listOf("2", "4"), MimeParser.parseBody(source).attachments.map { it.section })
        }
    }

    /** The corpus must hold what the walk could get wrong, or the equality above is cheap. */
    @Test fun `the corpus carries the shapes the open has to get right`() {
        val bodies = corpus().map { (_, source) -> MimeParser.parseBody(source) }
        val attachments = bodies.flatMap { it.attachments }
        assertTrue("an inline image", attachments.any { it.cid != null && it.type.startsWith("image/") })
        assertTrue("a named image with a content id", attachments.any { it.cid != null && it.name.endsWith(".png") })
        assertTrue("an attached message", attachments.any { it.type == "message/rfc822" })
        assertTrue("an invitation", attachments.any { it.name == "invite.ics" })
        assertTrue("a nested section", attachments.any { '.' in it.section })
        assertTrue("an alternative", bodies.any { it.html != null && it.text != null })
    }

    /**
     * Dovecot writes `("charset" "us-ascii")` into the BODYSTRUCTURE of a text that names no charset,
     * as RFC 3501's own example does; the whole source reads that text as UTF-8, and so must the parts.
     */
    @Test fun `a text that names no charset reads as UTF-8 by parts, as its whole source reads it`() {
        val served = ServedMessage(UNLABELLED_8BIT)
        assertTrue(served.structure, served.structure.contains("(\"charset\" \"us-ascii\")"))
        val (read, issued) = open(served)
        assertTrue("read by parts: $issued", !read.wholeSource)
        val whole = emailOf(UNLABELLED_8BIT, ImapOpenRead(UNLABELLED_8BIT, MimeParser.parseBody(UNLABELLED_8BIT), 0L, true))
        assertEquals(whole.withTheDelimiterBreakOf(emailOf(read.header, read)), emailOf(read.header, read))
        assertEquals("Été comme hiver.\r\n", read.body.text)
    }

    // ---- 2. the wire ------------------------------------------------------------------------------

    @Test fun `the open peeks - header and structure, then the two text sections, nothing else`() {
        val (read, issued) = open(ServedMessage(ALTERNATIVE_WITH_PDF))
        assertEquals(
            "a bare BODY[…] here sets \\Seen on the server, for every device, before the reader decides",
            listOf("UID FETCH 7 (BODYSTRUCTURE BODY.PEEK[HEADER])", "UID FETCH 7 (BODY.PEEK[1.2] BODY.PEEK[1.1])"),
            issued,
        )
        assertEquals("<p>Rich</p>\r\n", read.body.html)
        assertEquals("Plain\r\n", read.body.text)
    }

    @Test fun `a message with no text part costs one FETCH`() {
        val (read, issued) = open(ServedMessage(ONLY_A_PDF))
        assertEquals(listOf("UID FETCH 7 (BODYSTRUCTURE BODY.PEEK[HEADER])"), issued)
        assertEquals(listOf("1", "2"), read.body.attachments.map { it.section })
    }

    @Test fun `a signed message is read whole`() {
        val (read, issued) = open(ServedMessage(SIGNED))
        assertTrue("read whole: $issued", read.wholeSource)
        assertEquals(listOf("UID FETCH 7 (BODYSTRUCTURE BODY.PEEK[HEADER])", "UID FETCH 7 (BODY.PEEK[])"), issued)
        assertEquals(MimeParser.parseBody(SIGNED), read.body)
    }

    @Test fun `an S-MIME envelope is read whole`() {
        val (read, _) = open(ServedMessage(SMIME_ENVELOPE))
        assertTrue(read.wholeSource)
    }

    @Test fun `a structure that does not parse is read whole, never shown half`() {
        val served = ServedMessage(ALTERNATIVE_WITH_PDF, structureOverride = """("text")""")
        val (read, issued) = open(served)
        assertTrue("read whole: $issued", read.wholeSource)
        assertEquals(listOf("UID FETCH 7 (BODYSTRUCTURE BODY.PEEK[HEADER])", "UID FETCH 7 (BODY.PEEK[])"), issued)
        assertEquals(MimeParser.parseBody(ALTERNATIVE_WITH_PDF), read.body)
    }

    @Test fun `a structure fetch the server refuses with NO is read whole`() {
        val served = ServedMessage(ALTERNATIVE_WITH_PDF, refused = "UID FETCH 7 (BODYSTRUCTURE BODY.PEEK[HEADER])" to "NO")
        val (read, issued) = open(served)
        assertTrue("read whole: $issued", read.wholeSource)
        assertEquals(listOf("UID FETCH 7 (BODYSTRUCTURE BODY.PEEK[HEADER])", "UID FETCH 7 (BODY.PEEK[])"), issued)
        assertEquals(MimeParser.parseBody(ALTERNATIVE_WITH_PDF), read.body)
    }

    @Test fun `a section fetch the server refuses with BAD is read whole`() {
        val served = ServedMessage(ALTERNATIVE_WITH_PDF, refused = "UID FETCH 7 (BODY.PEEK[1.2] BODY.PEEK[1.1])" to "BAD")
        val (read, issued) = open(served)
        assertTrue("read whole: $issued", read.wholeSource)
        assertEquals("UID FETCH 7 (BODY.PEEK[])", issued.last())
        assertEquals(MimeParser.parseBody(ALTERNATIVE_WITH_PDF), read.body)
    }

    @Test fun `a section the server leaves out is not an empty one`() {
        val served = ServedMessage(ALTERNATIVE_WITH_PDF, dropSection = "1.2")
        val (read, issued) = open(served)
        assertTrue("read whole: $issued", read.wholeSource)
        assertEquals(MimeParser.parseBody(ALTERNATIVE_WITH_PDF), read.body)
    }

    @Test fun `text past the parse limit is refused before it is downloaded`() {
        val tooLong = MimeParser.MAX_BODY_CHARS + 1
        val served = ServedMessage(
            ALTERNATIVE_WITH_PDF,
            structureOverride = """("TEXT" "PLAIN" ("CHARSET" "utf-8") NIL NIL "7BIT" $tooLong 1 NIL NIL NIL NIL)""",
        )
        val (read, issued) = open(served)
        assertTrue(read.body.tooLarge)
        assertEquals(tooLong.toLong(), read.bodyChars)
        assertEquals(listOf("UID FETCH 7 (BODYSTRUCTURE BODY.PEEK[HEADER])"), issued)
    }

    // ---- 3. read as source text: the open's call --------------------------------------------------

    @Test fun `the open reads by parts on the foreground lane, and hands the header on untouched`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "openEmailImap").lines().map { it.trim() }
        assertEquals(
            listOf(
                "val read = unavailableOnTimeout(emailId) { imap.fetchForDisplay(credentials, mailboxId, uid, ImapLane.FOREGROUND) }",
                "val header = read.header",
            ),
            body.filter { it.startsWith("val read =") || it.startsWith("val header =") },
        )
    }

    // ---- harness -----------------------------------------------------------------------------------

    private fun emailOf(header: String, read: ImapOpenRead): Email =
        openedImapEmail(ENVELOPE, read.body, header, MimeParser.rawHeaders(header), setOf("mx.example.net"))

    /** The one difference allowed: the line break before a boundary belongs to the delimiter (RFC
     *  2046 §5.1.1). `MimeParser` keeps it in the part, the server's section and size do not. */
    private fun Email.withTheDelimiterBreakOf(parts: Email): Email = copy(
        bodyValues = bodyValues.mapValues { (key, value) ->
            val shown = parts.bodyValues[key]?.value
            if (shown != null && value.value == "$shown\r\n") value.copy(value = shown) else value
        },
        attachments = attachments.mapIndexed { i, part ->
            val size = parts.attachments.getOrNull(i)?.size
            if (size != null && part.size == size + 2) part.copy(size = size) else part
        },
    )

    private fun open(served: ServedMessage): Pair<ImapOpenRead, List<String>> =
        ScriptedImapServer { tag, line -> served.answer(tag, line) }.use { server ->
            val read = runBlocking {
                ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher())
                    .fetchForDisplay(credentials(server), "INBOX", 7L, ImapLane.FOREGROUND)
            }
            read to server.issued("UID FETCH")
        }

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@example.net",
        password = "secret",
        id = "acc",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    private fun corpus(): List<Pair<String, String>> =
        EML.map { name ->
            val bytes = javaClass.getResourceAsStream("/eml/$name")!!.use { it.readBytes() }
            name to crlf(String(bytes, Charsets.ISO_8859_1))
        } + listOf(
            "alternative-with-pdf" to ALTERNATIVE_WITH_PDF,
            "related-with-cid" to RELATED_WITH_CID,
            "attached-message" to ATTACHED_MESSAGE,
            "inline-invitation" to INLINE_INVITATION,
            "latin1-quoted-printable" to LATIN1_QP,
            "single-base64" to SINGLE_BASE64,
            "preamble-and-2231-name" to PREAMBLE_AND_2231,
            "headers-the-open-reads" to HEADERS_THE_OPEN_READS,
            "unlabelled-8bit" to UNLABELLED_8BIT,
            "named-image-with-cid" to NAMED_IMAGE_WITH_CID,
        )

    /**
     * A message as an IMAP server holds it: its header block (blank line included, RFC 3501
     * §6.4.5), a BODYSTRUCTURE in Dovecot's shape (types upper case, every extension written) and
     * each leaf's octets by section, without the line break that precedes the next delimiter.
     */
    private class ServedMessage(
        val source: String,
        structureOverride: String? = null,
        private val dropSection: String? = null,
        /** A command line, and the tagged status (`NO` or `BAD`) the server answers it with. */
        private val refused: Pair<String, String>? = null,
    ) {
        val sections = mutableMapOf<String, String>()
        val header: String = source.substring(0, source.indexOf("\r\n\r\n") + 4)
        val structure: String = structureOverride ?: structureOf(source, "", sections)

        fun answer(tag: String, line: String): String = when {
            refused != null && line == refused.first -> "$tag ${refused.second} [SERVERBUG] internal error\r\n"
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 1 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line == "UID FETCH 7 (BODYSTRUCTURE BODY.PEEK[HEADER])" ->
                "* 1 FETCH (UID 7 BODYSTRUCTURE $structure BODY[HEADER] ${literal(header)})\r\n$tag OK done\r\n"
            line == "UID FETCH 7 (BODY.PEEK[])" -> "* 1 FETCH (UID 7 BODY[] ${literal(source)})\r\n$tag OK done\r\n"
            line.startsWith("UID FETCH 7 (") -> {
                val items = PEEKED.findAll(line).map { it.groupValues[1] }.filter { it != dropSection }
                    .joinToString(" ") { "BODY[$it] ${literal(sections.getValue(it))}" }
                "* 1 FETCH (UID 7 $items)\r\n$tag OK done\r\n"
            }
            else -> "$tag OK done\r\n"
        }

        private fun literal(octets: String) = "{${octets.length}}\r\n$octets"

        companion object {
            val PEEKED = Regex("""BODY\.PEEK\[([0-9.]+)]""")

            fun structureOf(entity: String, section: String, sections: MutableMap<String, String>): String {
                val split = entity.indexOf("\r\n\r\n")
                val headerText = if (split < 0) entity else entity.substring(0, split)
                val body = if (split < 0) "" else entity.substring(split + 4)
                val fields = fieldsOf(headerText)
                val (type, params) = typeOf(fields["content-type"])
                val (major, minor) = type.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                if (major == "multipart") {
                    val boundary = params.first { it.first.equals("boundary", true) }.second
                    val children = partsOf(body, boundary).mapIndexed { i, part ->
                        structureOf(part, if (section.isEmpty()) "${i + 1}" else "$section.${i + 1}", sections)
                    }
                    return "(${children.joinToString("")} ${q(minor.uppercase())} ${list(params)} NIL NIL NIL)"
                }
                sections[section.ifEmpty { "1" }] = body
                val encoding = fields["content-transfer-encoding"]?.trim()?.uppercase()?.let(::q) ?: q("7BIT")
                val id = fields["content-id"]?.trim()?.let(::q) ?: "NIL"
                val basic = "${q(major.uppercase())} ${q(minor.uppercase())} ${list(params)} $id NIL $encoding ${body.length}"
                val lines = body.split("\r\n").size - 1
                val extension = "NIL ${dispositionOf(fields["content-disposition"])} NIL NIL"
                return when {
                    type == "message/rfc822" -> {
                        val inner = structureOf(body, "$section.inner", mutableMapOf())
                        "($basic (NIL NIL NIL NIL NIL NIL NIL NIL NIL NIL) $inner $lines $extension)"
                    }
                    major == "text" -> "($basic $lines $extension)"
                    else -> "($basic $extension)"
                }
            }

            /** The parts between delimiter lines, each without the CRLF that precedes the next one. */
            fun partsOf(body: String, boundary: String): List<String> {
                val delimiter = "--$boundary"
                val lines = body.split("\r\n")
                val parts = mutableListOf<String>()
                var current: MutableList<String>? = null
                for (line in lines) {
                    val bare = line.trimEnd(' ', '\t')
                    if (bare == delimiter || bare == "$delimiter--") {
                        current?.let { parts += it.joinToString("\r\n") }
                        if (bare.endsWith("--") && bare != delimiter) return parts
                        current = mutableListOf()
                    } else {
                        current?.add(line)
                    }
                }
                return parts
            }

            fun fieldsOf(headerText: String): Map<String, String> {
                val out = mutableMapOf<String, String>()
                var last: String? = null
                for (line in headerText.split("\r\n")) {
                    if (line.startsWith(" ") || line.startsWith("\t")) {
                        last?.let { out[it] = out[it] + " " + line.trim() }
                        continue
                    }
                    val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
                    last = line.substring(0, colon).trim().lowercase()
                    out[last] = line.substring(colon + 1).trim()
                }
                return out
            }

            fun typeOf(value: String?): Pair<String, List<Pair<String, String>>> {
                if (value == null) return "text/plain" to listOf("charset" to "us-ascii")
                val pieces = splitParameters(value)
                val type = pieces.first().trim().lowercase()
                val params = pieces.drop(1).mapNotNull(::parameter)
                val unlabelledText = type.startsWith("text/") && params.none { it.first.equals("charset", ignoreCase = true) }
                return type to if (unlabelledText) params + ("charset" to "us-ascii") else params
            }

            fun dispositionOf(value: String?): String {
                if (value == null) return "NIL"
                val pieces = splitParameters(value)
                return "(${q(pieces.first().trim().uppercase())} ${list(pieces.drop(1).mapNotNull(::parameter))})"
            }

            fun splitParameters(value: String): List<String> {
                val out = mutableListOf<String>()
                val piece = StringBuilder()
                var quoted = false
                for (c in value) {
                    when {
                        c == '"' -> { quoted = !quoted; piece.append(c) }
                        c == ';' && !quoted -> { out += piece.toString(); piece.clear() }
                        else -> piece.append(c)
                    }
                }
                out += piece.toString()
                return out
            }

            fun parameter(text: String): Pair<String, String>? {
                val eq = text.indexOf('=').takeIf { it > 0 } ?: return null
                return text.substring(0, eq).trim() to text.substring(eq + 1).trim().removeSurrounding("\"")
            }

            fun list(params: List<Pair<String, String>>): String =
                if (params.isEmpty()) "NIL" else params.joinToString(" ", "(", ")") { (k, v) -> "${q(k)} ${q(v)}" }

            fun q(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    private companion object {
        val ENVELOPE = Email(id = "acc:INBOX:7", accountId = "acc", mailboxId = "INBOX", subject = "x")

        val MISCUT_BY_THE_WHOLE_SOURCE = setOf("joint-avec-pj-05.eml", "attached-message")

        val EML = listOf(
            "export-8bit-03.eml", "invitation-illisible-08.eml", "joint-avec-pj-05.eml",
            "joint-html-04.eml", "joint-nomme-01.eml", "joint-sans-nom-02.eml",
            "type-cible-vide-09.eml", "type-declare-07.eml", "type-generique-06.eml",
        )

        fun crlf(text: String) = text.replace("\r\n", "\n").replace("\n", "\r\n")

        val ALTERNATIVE_WITH_PDF = crlf(
            """
            |From: Ann <ann@example.org>
            |Subject: Figures
            |MIME-Version: 1.0
            |Content-Type: multipart/mixed; boundary="OUT"
            |
            |--OUT
            |Content-Type: multipart/alternative; boundary="IN"
            |
            |--IN
            |Content-Type: text/plain; charset=utf-8
            |
            |Plain
            |
            |--IN
            |Content-Type: text/html; charset=utf-8
            |
            |<p>Rich</p>
            |
            |--IN--
            |
            |--OUT
            |Content-Type: application/pdf; name="figures.pdf"
            |Content-Transfer-Encoding: base64
            |Content-Disposition: attachment; filename="figures.pdf"
            |
            |JVBERi0xLjQKJcOkw7zDtsOfCjEgMCBvYmoKPDwvVHlwZS9DYXRhbG9nPj4KZW5kb2JqCg==
            |
            |--OUT--
            |
            """.trimMargin(),
        )

        val ONLY_A_PDF = crlf(
            """
            |From: Ann <ann@example.org>
            |Subject: Scan
            |Content-Type: multipart/mixed; boundary="S"
            |
            |--S
            |Content-Type: text/plain; name="readme.txt"
            |
            |a file, not a body
            |--S
            |Content-Type: application/pdf
            |Content-Disposition: attachment; filename="scan.pdf"
            |Content-Transfer-Encoding: base64
            |
            |JVBERi0xLjQK
            |--S--
            |
            """.trimMargin(),
        )

        val RELATED_WITH_CID = crlf(
            """
            |From: Ann <ann@example.org>
            |Subject: Logo
            |Content-Type: multipart/alternative; boundary="A"
            |
            |--A
            |Content-Type: text/plain; charset=utf-8
            |Content-Transfer-Encoding: quoted-printable
            |
            |Voir le logo =C3=A9l=C3=A9gant
            |--A
            |Content-Type: multipart/related; boundary="R"
            |
            |--R
            |Content-Type: text/html; charset=utf-8
            |Content-Transfer-Encoding: base64
            |
            |PHA+PGltZyBzcmM9ImNpZDpsb2dvQHgiPiDDqWzDqWdhbnQ8L3A+
            |--R
            |Content-Type: image/png
            |Content-Transfer-Encoding: base64
            |Content-ID: <logo@x>
            |Content-Disposition: inline
            |
            |iVBORw0KGgo=
            |--R--
            |--A--
            |
            """.trimMargin(),
        )

        val ATTACHED_MESSAGE = crlf(
            """
            |From: Ann <ann@example.org>
            |Subject: Fwd
            |Content-Type: multipart/mixed; boundary="M"
            |
            |--M
            |Content-Type: text/plain
            |
            |See below.
            |--M
            |Content-Type: message/rfc822
            |Content-Disposition: inline
            |
            |From: Bo <bo@example.org>
            |Subject: Inner
            |Content-Type: multipart/mixed; boundary="MI"
            |
            |--MI
            |Content-Type: text/plain
            |
            |inner text
            |--MI
            |Content-Type: application/zip; name="inner.zip"
            |Content-Transfer-Encoding: base64
            |
            |UEsDBA==
            |--MI--
            |--M--
            |
            """.trimMargin(),
        )

        val INLINE_INVITATION = crlf(
            """
            |From: Ann <ann@example.org>
            |Subject: Meeting
            |Content-Type: multipart/alternative; boundary="C"
            |
            |--C
            |Content-Type: text/plain; charset=utf-8
            |
            |Meeting on Thursday.
            |--C
            |Content-Type: text/html; charset=utf-8
            |
            |<p>Meeting on <b>Thursday</b>.</p>
            |--C
            |Content-Type: text/calendar; charset=utf-8; method=REQUEST
            |
            |BEGIN:VCALENDAR
            |METHOD:REQUEST
            |END:VCALENDAR
            |--C--
            |
            """.trimMargin(),
        )

        val LATIN1_QP = crlf(
            """
            |From: =?iso-8859-1?q?Ren=E9?= <rene@example.org>
            |Subject: =?iso-8859-1?q?R=E9union?=
            |Content-Type: text/html; charset="ISO-8859-1"
            |Content-Transfer-Encoding: Quoted-Printable
            |
            |<p>R=E9union d=E9plac=E9e =E0 jeudi, une ligne assez longue pour =
            |=EAtre coup=E9e.</p>
            |
            """.trimMargin(),
        )

        val SINGLE_BASE64 = crlf(
            """
            |From: Ann <ann@example.org>
            |Subject: b64
            |Content-Type: text/plain; charset=utf-8
            |Content-Transfer-Encoding: base64
            |
            |Qm9uam91ciDDoCB0b3VzCg==
            |
            """.trimMargin(),
        )

        val PREAMBLE_AND_2231 = crlf(
            """
            |From: Ann <ann@example.org>
            |Subject: Report
            |Content-Type: multipart/mixed;
            | boundary="P"
            |
            |This is a multi-part message in MIME format.
            |
            |--P
            |Content-Type: text/plain; charset=utf-8
            |
            |Report attached.
            |--P
            |Content-Type: application/pdf
            |Content-Disposition: attachment;
            | filename*=utf-8''%D0%9E%D1%82%D1%87%D1%91%D1%82.pdf
            |Content-Transfer-Encoding: base64
            |
            |JVBERi0xLjQK
            |--P--
            |epilogue
            |
            """.trimMargin(),
        )

        val HEADERS_THE_OPEN_READS = crlf(
            """
            |From: List <news@example.org>
            |Subject: Weekly
            |Message-ID: <weekly-1@example.org>
            |References: <a@example.org>
            | <b@example.org>
            |List-Unsubscribe: <https://example.org/u/1>, <mailto:u@example.org>
            |List-Unsubscribe-Post: List-Unsubscribe=One-Click
            |Disposition-Notification-To: news@example.org
            |X-Original-From: =?utf-8?q?Ren=C3=A9?= <rene@example.org>
            |X-Original-From: second@example.org
            |Authentication-Results: mx.example.net; dkim=fail header.d=example.org; spf=pass
            |Autocrypt: addr=news@example.org; keydata=AAAA
            |Content-Type: multipart/mixed; boundary="H"
            |
            |--H
            |Content-Type: text/plain
            |
            |This week.
            |--H
            |Content-Type: text/csv; name="figures.csv"
            |
            |a,b
            |--H--
            |
            """.trimMargin(),
        )

        /** UTF-8 octets, as the server sends them, under a type that names no charset. */
        val UNLABELLED_8BIT = String(
            crlf(
                """
                |From: Ann <ann@example.org>
                |Subject: Saisons
                |MIME-Version: 1.0
                |Content-Type: text/plain
                |Content-Transfer-Encoding: 8bit
                |
                |Été comme hiver.
                |
                """.trimMargin(),
            ).toByteArray(Charsets.UTF_8),
            Charsets.ISO_8859_1,
        )

        /** Outlook's and Apple Mail's shape: the image the HTML shows by `cid:` also carries a name. */
        val NAMED_IMAGE_WITH_CID = crlf(
            """
            |From: Ann <ann@example.org>
            |Subject: Signature
            |Content-Type: multipart/related; type="text/html"; boundary="N"
            |
            |--N
            |Content-Type: text/html; charset=utf-8
            |
            |<p>Ann<img src="cid:image001.png@01DB0000.00000000"></p>
            |--N
            |Content-Type: image/png; name="image001.png"
            |Content-Transfer-Encoding: base64
            |Content-ID: <image001.png@01DB0000.00000000>
            |Content-Disposition: inline; filename="image001.png"
            |
            |iVBORw0KGgo=
            |--N
            |Content-Type: image/jpeg; name="photo.jpg"
            |Content-Transfer-Encoding: base64
            |Content-ID: <photo@x>
            |Content-Disposition: attachment; filename="photo.jpg"
            |
            |/9j/4AAQ
            |--N--
            |
            """.trimMargin(),
        )

        val SIGNED = crlf(
            """
            |From: Ann <ann@example.org>
            |Subject: Signed
            |Content-Type: multipart/signed; protocol="application/pgp-signature"; micalg=pgp-sha256; boundary="G"
            |
            |--G
            |Content-Type: text/plain
            |
            |Signed text.
            |--G
            |Content-Type: application/pgp-signature; name="signature.asc"
            |
            |-----BEGIN PGP SIGNATURE-----
            |AAAA
            |-----END PGP SIGNATURE-----
            |--G--
            |
            """.trimMargin(),
        )

        val SMIME_ENVELOPE = crlf(
            """
            |From: Ann <ann@example.org>
            |Subject: Sealed
            |Content-Type: application/pkcs7-mime; smime-type=enveloped-data; name="smime.p7m"
            |Content-Transfer-Encoding: base64
            |
            |MIAGCSqGSIb3DQEHA6CAMIACAQA=
            |
            """.trimMargin(),
        )

        /** See the twin in [ReadUnderTheNumberingItWasTickedUnderTest]. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}
