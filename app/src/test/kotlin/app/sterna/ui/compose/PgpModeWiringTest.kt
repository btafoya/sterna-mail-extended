package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. It reads `ComposeViewModel.kt` and `ComposeScreen.kt` as
 * text and compares whole trimmed lines. It proves nothing about what runs.
 */
class PgpModeWiringTest {

    @Test fun `the send site passes the arguments the pure function decided, and builds none itself`() {
        assertEquals(
            "buildPgpEntity no longer hands signAndEncrypt exactly what pgpEncryptArgs decided. " +
                "Two ways this breaks, both silent: pass a key where args.signKeyId is, and the " +
                "'encrypt without signing' mode signs anyway — the composer showed one thing and " +
                "the message says another. Rebuild the recipient array here instead of using " +
                "args.recipientKeyIds, and the sender's own key can be dropped from it: every " +
                "unsigned encrypted message in Sent becomes unreadable, on the sender's own " +
                "phone, definitively.",
            listOf(
                "val args = pgpEncryptArgs(mode, selfKeyId, recipientKeys.toList())",
                "when (val r = pgp.signAndEncrypt(payload.toByteArray(Charsets.UTF_8), " +
                    "args.signKeyId, args.recipientKeyIds, interactionResult)) {",
            ),
            block(VIEW_MODEL, "val args = pgpEncryptArgs(", 2),
        )
    }

    @Test fun `the send reads the mode once and hands that one value to the entity builder`() {
        // Choosing the branch and choosing the signer are separated by pgp.findKeys, a suspension
        // that round-trips to the provider, and the mode moves under it: deriveAutoMode drops an
        // untouched encrypt-by-default lock to OFF as soon as a recipient turns out to have no key.
        assertEquals(
            "the send no longer captures the mode once for the whole message.",
            listOf(
                "val mode = _pgpMode.value",
                "decryptedQuoteRefusal(decryptedQuote.held.value, mode)?.let { error(getApplication<Application>().getString(it)) }",
                "val pgpEntity = if (mode != PgpMode.OFF) {",
            ),
            block(VIEW_MODEL, "val mode = _pgpMode.value", 3),
        )
        assertEquals(
            "buildPgpEntity stopped taking the mode as a parameter — it is reading _pgpMode.value " +
                "again, after the provider round-trip, and the signature the message carries now " +
                "depends on which coroutine resumed first rather than on what the composer showed.",
            listOf(
                "private suspend fun buildPgpEntity(",
                "credentials: AccountCredentials,",
                "allRecipients: List<String>,",
                "textBody: String,",
                "htmlBody: String?,",
                "attachments: List<EmailBodyPart>,",
                "mode: PgpMode,",
                "protectedSubject: String?,",
                "interactionResult: Intent?,",
            ),
            block(VIEW_MODEL, "private suspend fun buildPgpEntity(", 9),
        )
    }

    @Test fun `the send hands the entity builder the subject the pure function decided`() {
        // protectedSubject(mode, subject) is executed by ProtectedSubjectTest, and
        // ProtectedHeadersEntityTest executes what buildBodyEntity does with its answer. Between
        assertEquals(
            "the send site no longer passes protectedSubject(mode, subject) to buildPgpEntity. " +
                "Whatever it passes now is invisible to every executing test here: this class " +
                "cannot be instantiated in a JVM test.",
            listOf(
                "buildPgpEntity(",
                "credentials, recipients + ccList + bccList,",
                "textBody, htmlBody, attachments, mode,",
                "protectedSubject(mode, subject), interactionResult,",
                "sentFromAddress(identity?.email, credentials.username), bccList,",
                ")",
            ),
            block(VIEW_MODEL, "buildPgpEntity(", 6),
        )
    }

    @Test fun `the entity builder is handed that subject, and not the default`() {
        // The joint has two lines, not one. The test above pins the first (send site to
        // buildPgpEntity); this pins the second, and it is the more dangerous of the two because
        assertEquals(
            "the PGP entity is no longer built with the subject the send decided. Passing " +
                "anything else here — or nothing, which is what the default argument makes easy " +
                "— leaves every encrypted message with no protected subject at all, silently.",
            listOf(
                "val inner = OutgoingMime.buildBodyEntity(",
                "OutgoingMessage(",
                "from = \"-\", to = listOf(\"-\"), subject = \"\",",
                "body = textBody, html = htmlBody,",
                "messageId = \"\$boundarySeed@pgp\", dateMillis = System.currentTimeMillis(),",
                "attachments = outAttachments,",
                "),",
                "protectedSubject = protectedSubject,",
                ")",
            ),
            block(VIEW_MODEL, "val inner = OutgoingMime.buildBodyEntity(", 9),
        )
    }

    @Test fun `the short tap is still the three-stop cycle, and cannot walk into the unsigned mode`() {
        // nextLockMode is pure and SmimeLockTest executes it, but nothing executes the line that
        // calls it: handed one availability for the other, an account with both would tap into S/MIME.
        assertEquals(
            "the lock's short tap no longer walks nextLockMode's cycle verbatim.",
            listOf("fun cyclePgpMode() = setPgpMode(nextLockMode(_pgpMode.value, openPgp = _pgpAvailable.value, smime = _smimeAvailable.value))"),
            block(VIEW_MODEL, "fun cyclePgpMode()", 1),
        )
    }

    @Test fun `an S-MIME mode is signed by the function the tests run, for the From the message goes out from`() {
        assertEquals(
            "buildPgpEntity no longer hands an S/MIME mode to buildSmimeEntity first: it would reach the " +
                "OpenPGP branches, which refuse it as unreachable, or worse, sign it with OpenPGP.",
            listOf(
                "if (mode.isSmime) return buildSmimeEntity(credentials, allRecipients, bccRecipients, textBody, htmlBody, " +
                    "attachments, mode, protectedSubject, fromAddress)",
            ),
            block(VIEW_MODEL, "if (mode.isSmime) return buildSmimeEntity(", 1),
        )
        val builder = block(VIEW_MODEL, "private suspend fun buildPgpEntity(", 16)
        val routed = builder.indexOfFirst { it.startsWith("if (mode.isSmime) return buildSmimeEntity(") }
        val demanded = builder.indexOfFirst { it.startsWith("val account = pgpAccount()") }
        assertTrue(
            "the S/MIME route is no longer taken before an OpenPGP account is demanded: an account with S/MIME " +
                "alone can no longer send (route at $routed, demand at $demanded)",
            routed >= 0 && demanded > routed,
        )
        assertEquals(
            "buildSmimeEntity no longer hands smimeEntity the mode captured, the From sent, the To and Cc apart " +
                "from the Bcc and the certificates read at the send, or no longer turns its refusal into the " +
                "composer's error. smimeEntity is what SmimeSigningTest and SmimeEncryptingTest execute; anything " +
                "else here is untested. Handed the Bcc among the recipients, an encrypted message names them to all.",
            listOf(
                "smimeEntity(",
                "payload, \"----sterna_smime_\$boundarySeed\", mode, alias, fromAddress, smimeKeys, smimeEngine, now,",
                "recipients = allRecipients - bccRecipients.toSet(), bcc = bccRecipients, kept = kept,",
                ")",
                "}",
                "} catch (refused: SmimeSendRefused) {",
                "error(getApplication<Application>().getString(smimeRefusalWording(refused.reason)))",
            ),
            block(VIEW_MODEL, "smimeEntity(", 7),
        )
        assertEquals(
            "the certificates an S/MIME message is encrypted to are no longer read at the send, for every recipient " +
                "the send was handed, as valid at the instant the message is signed",
            listOf(
                "val now = java.util.Date()",
                "val kept = if (mode.encrypts) smimeCertificates.certificatesFor(allRecipients, now.time) else emptyMap()",
            ),
            block(VIEW_MODEL, "val now = java.util.Date()", 2),
        )
        assertEquals(
            "the alias is read for another account than the one sending",
            List(2) { "val alias = store.account(credentials.id)?.smimeCertAlias.orEmpty()" },
            code(VIEW_MODEL).filter { "smimeCertAlias" in it },
        )
    }

    @Test fun `the lock offers S-MIME for the From the message would go out from`() {
        assertEquals(
            listOf(
                "val from = sentFromAddress(selectedIdentity()?.email, credentials.username)",
                "return withContext(Dispatchers.IO) { smimeOffered(alias, from, smimeKeys, java.util.Date()) }",
            ),
            block(VIEW_MODEL, "val from = sentFromAddress(", 2),
        )
        assertEquals(
            "the lock's verdict no longer weighs an S/MIME mode by the answer it is handed",
            "smimeOffered = smimeOffered,",
            block(VIEW_MODEL, "val outcome = lockRefresh(", 7).last(),
        )
        assertEquals(
            "OpenPGP's lock waits again for the keystore, up to its 20 s deadline, before it is shown; or an " +
                "S/MIME mode is settled before S/MIME has answered",
            listOf(
                "_pgpAvailable.value = available",
                "keyedRecipients = null",
                "if (!available) _pgpKeylessRecipients.value = emptyList()",
                "if (!_pgpMode.value.isSmime) settleLock(account, available, smimeOffered = emptyList())",
                "val smime = smimeOfferedNow()",
                "if (refresh != lockRefreshes) return@launch",
                "_smimeAvailable.value = smime",
                "if (_pgpMode.value.isSmime) settleLock(account, available, smimeOffered = smime)",
            ),
            block(VIEW_MODEL, "_pgpAvailable.value = available", 8),
        )
        assertTrue(
            "the keystore is read before OpenPGP's answer is published",
            block(VIEW_MODEL, "val available = account != null && pgp.isAvailable()", 3) ==
                listOf("val available = account != null && pgp.isAvailable()", "if (refresh != lockRefreshes) return@launch", "_pgpAvailable.value = available"),
        )
    }

    @Test fun `each mode is bound to the face it was given, and no other`() {
        // The complement of PgpModeIconFacesTest, which executes pgpModeIcon and measures that the
        // four faces it returns are four distinct drawings. That measurement is blind to which mode
        assertEquals(
            "pgpModeIcon changed. If two modes now draw the same icon, the composer shows the " +
                "same padlock for a signed and an unsigned message and there is no other " +
                "permanent sign of which one is in force; if the pairings were merely swapped, " +
                "the padlock lies about the message.",
            listOf(
                "internal fun pgpModeIcon(mode: PgpMode): ImageVector = when (mode) {",
                "PgpMode.OFF -> Icons.Filled.LockOpen",
                "PgpMode.SIGN -> Icons.Filled.Draw",
                "PgpMode.ENCRYPT -> Icons.Filled.Lock",
                "PgpMode.ENCRYPT_UNSIGNED -> Icons.Filled.VpnKey",
                "PgpMode.SMIME_SIGN -> Icons.Filled.VerifiedUser",
                "PgpMode.SMIME_ENCRYPT -> Icons.Filled.Security",
            ),
            block(TEXT, "internal fun pgpModeIcon(", 7),
        )
    }

    @Test fun `no key array is assembled by hand anywhere in the composer's view model`() {
        // The absence half. The line that was here, `(recipientKeys.toList() + signKeyId)
        // .distinct().toLongArray()`, is the one that must live in pgpEncryptArgs and nowhere
        // else, because it is the encrypt-to-self, and a JVM test can only run it there.
        assertEquals(
            "a key array is being built inside ComposeViewModel again. Whatever it computes is " +
                "invisible to every test in this repo: nothing here can instantiate this class.",
            emptyList<String>(),
            code(VIEW_MODEL).filter { it.contains(".distinct().toLongArray()") },
        )
    }

    @Test fun `recipient keys are looked up for every encrypting mode, not for one of them`() {
        assertEquals(
            "updateRecipientKeys stopped asking `does this mode encrypt`. Written as a comparison " +
                "with one mode, the unsigned mode looks up NO recipient key at all: the composer " +
                "shows no missing-key warning, the Send button stays enabled, and the send fails " +
                "at the provider (or encrypts to nobody but the sender).",
            listOf(
                "val care = if (smime) {",
                "_pgpMode.value.encrypts",
                "} else {",
                "_pgpAvailable.value &&",
                "(encryptsByDefault(account) || _pgpMode.value.encrypts)",
                "}",
            ),
            block(VIEW_MODEL, "val care = ", 6),
        )
        assertEquals(
            "an S/MIME encrypting mode no longer marks the recipients by the certificates kept for them, or an " +
                "OpenPGP answer for the same recipients is kept when the lock moves to S/MIME",
            listOf(
                "if (smime == keyedSmime && !recipientKeysStale(keyedRecipients, addresses)) return",
                "keyedRecipients = addresses",
                "keyedSmime = smime",
            ),
            block(VIEW_MODEL, "if (smime == keyedSmime", 3),
        )
        assertEquals(
            listOf(
                "smime -> runCatching {",
                "val sender = sentFromAddress(selectedIdentity()?.email, credentials()?.username.orEmpty())",
                "smimeCertificatesHeld(addresses, smimeCertificates.certificatesFor(addresses, System.currentTimeMillis()), sender)",
            ),
            block(VIEW_MODEL, "smime -> runCatching {", 3),
        )
        assertEquals(
            "the lock's drop no longer says what lockDropNotice decided, which SmimeLockTest executes",
            listOf("lockDropNotice(outcome.dropped)?.let { _notices.tryEmit(it) }"),
            code(VIEW_MODEL).filter { "lockDropNotice(" in it },
        )
    }

    @Test fun `the compose screen asks whether the mode encrypts, never which mode it is`() {
        // Two sites in that file read it: the per-recipient red flag (missingKeyFor) and the "the
        // subject is not encrypted" note. The Send gate goes through `sendKeysReady`.
        //
        // This is an absence over the whole file, so it says nothing about a site that stops
        // reading the mode at all. It is the cheapest guard that survives a lengthened line.
        assertEquals(
            "ComposeScreen compares against a single encrypting mode again.",
            emptyList<String>(),
            code(SCREEN).filter { it.contains("== PgpMode.ENCRYPT") },
        )
    }

    @Test fun `the bar draws the mode in force and each menu row draws its own`() {
        // pgpModeIcon moved out of ComposeScreen, and with it the only pin that read this file's
        // two call sites. What they PASS is the whole point and no other test can see it: write
        //
        // The bar reads `pgpMode` (what is in force), the menu row reads `entry` (the row's own
        // mode), in that order down the file. Two calls, no more: a third would be a face drawn
        // somewhere nothing here describes.
        assertEquals(
            "the composer's two pgpModeIcon call sites changed. If the bar stopped passing " +
                "pgpMode it shows a mode that is not in force; if a menu row stopped passing " +
                "entry, every row draws the same face and the menu says nothing.",
            listOf("pgpMode", "entry"),
            callArguments(File(root, SCREEN).readText(), "pgpModeIcon"),
        )
    }

    /** Code lines of [path], trimmed; comment lines and blanks dropped. */
    private fun code(path: String): List<String> = File(root, path).readLines()
        .map { it.trim() }
        .filter {
            it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*")
        }

    /**
     * [count] consecutive code lines of [path] from the single one starting with [prefix]. A prefix
     */
    private fun block(path: String, prefix: String, count: Int): List<String> {
        val lines = code(path)
        val starts = lines.indices.filter { lines[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines of $path start with `$prefix`»")
        return lines.subList(start, minOf(start + count, lines.size))
    }

    /** The argument text of every `name(...)` call in [text], in source order. */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')').trim() }
            .toList()

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, if (depth == 0) i - 1 else text.length)
    }

    private companion object {
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        const val TEXT = "app/src/main/kotlin/app/sterna/ui/compose/ComposeText.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }
    }
}
