package app.sterna.core.data.mail

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pre-built entity seam, decoupled from PGP.
 *
 * `OutgoingMessage.prebuiltEntity` is how a payload this app's MIME builder cannot express reaches
 * the wire byte for byte. It was born for PGP/MIME and the OUTBOX was wired to PGP with it: the
 * entity was written to disk only `if (entity != null && pgpMode != null && pgpMode != OFF)`. Hand
 * one over without a mode — which is exactly what an RFC 8098 receipt does — and the file was never
 * written, the row kept a body nobody composed, and the message went out as a shell. No error, no
 * log, nothing on screen.
 *
 * ⛔ The fix is NOT to pass a lying `PgpMode.SIGN`: an outbox row that calls itself signed lies to
 * `OutboxLogic.canEdit` and to the failure message as well. The condition is relaxed instead, and
 * both halves of it are pinned here by EXECUTION — [outboxPayload] and [prebuiltEntityAt] are pure,
 * so what the call site can only be read for, they answer for real.
 */
class PrebuiltEntityTest {

    private val entity = "Content-Type: multipart/report; report-type=disposition-notification;\r\n"

    // ---- what queueing writes -----------------------------------------------------------------

    /**
     * ⭐ THE DECOUPLING. An entity with NO PGP mode at all is stored, exactly as one with a mode is.
     *
     * Before this, the same call answered "write nothing" and the receipt left as an empty shell.
     */
    @Test fun `an entity with no pgp mode is written beside the row`() {
        assertEquals(
            OutboxPayload(entityFile = entity, redactBody = false),
            outboxPayload(entity, pgpMode = null),
        )
        assertEquals(
            "PgpMode.OFF is 'no PGP', not 'no entity'",
            OutboxPayload(entityFile = entity, redactBody = false),
            outboxPayload(entity, PgpMode.OFF),
        )
    }

    /** ⛔ And what the relaxation must not break: the two PGP shapes are unchanged. */
    @Test fun `the pgp paths keep the behaviour they had`() {
        assertEquals(
            "a signed message keeps its body in the row: it is public, and the Outbox shows it",
            OutboxPayload(entityFile = entity, redactBody = false),
            outboxPayload(entity, PgpMode.SIGN),
        )
        assertEquals(
            "an encrypted message must keep NO plaintext at rest",
            OutboxPayload(entityFile = entity, redactBody = true),
            outboxPayload(entity, PgpMode.ENCRYPT),
        )
    }

    /**
     * ⛔ Blanking the row still needs BOTH the mode and the entity. A row marked ENCRYPT whose
     * entity never arrived would otherwise be emptied of the only copy of its text — the ciphertext
     * is not there to replace it, so the message would simply be gone.
     */
    @Test fun `a mode without an entity blanks nothing and writes nothing`() {
        assertEquals(
            OutboxPayload(entityFile = null, redactBody = false),
            outboxPayload(prebuiltEntity = null, pgpMode = PgpMode.ENCRYPT),
        )
        assertEquals(
            OutboxPayload(entityFile = null, redactBody = false),
            outboxPayload(prebuiltEntity = null, pgpMode = null),
        )
    }

    // ---- what delivery reads back --------------------------------------------------------------

    /** A row with no entity file delivers normally: body, HTML and attachments from the row. */
    @Test fun `a row naming no file has no entity`() {
        assertNull(prebuiltEntityAt(null) { error("must not read anything") })
    }

    /** A row naming a file is delivered from that file, verbatim — whatever the PGP mode says. */
    @Test fun `a row naming a file is delivered from it, byte for byte`() {
        assertEquals(entity, prebuiltEntityAt("/outbox/7/prebuilt-entity.mime") { entity })
    }

    /**
     * ⛔ An unreadable file FAILS the delivery. It must never fall back to the row's own body: that
     * body is either blank (ciphertext) or the human-readable half of the payload (a receipt), and
     * sending it would deliver a mail nobody wrote and mark the item done. Failing keeps the row in
     * the Outbox with its error, where a person can see it.
     */
    @Test fun `a file that cannot be read fails the delivery instead of sending a shell`() {
        val thrown = runCatching { prebuiltEntityAt("/outbox/7/gone.mime") { null } }.exceptionOrNull()

        assertTrue("a missing payload must throw, not return null", thrown is IllegalStateException)
        assertEquals(
            "the message no longer mentions encryption: a receipt is neither encrypted nor signed",
            "The prepared message payload is missing.",
            thrown?.message,
        )
    }

    /** Read ONCE, above the protocol fork, so both branches see the same value. */
    @Test fun `the file is read exactly once`() {
        var reads = 0
        prebuiltEntityAt("/outbox/7/prebuilt-entity.mime") { reads++; entity }

        assertEquals(1, reads)
    }

    /** The file name changed with the field; old rows keep the absolute path they were given. */
    @Test fun `the entity file is no longer named after pgp`() {
        assertEquals("prebuilt-entity.mime", PREBUILT_ENTITY_FILE)
    }

    // ---- the call sites, read out of the shipped source: a LAST RESORT --------------------------

    /**
     * SOURCE LINT, NOT A BEHAVIOUR TEST — `MailRepository` needs Room, a `Context` and a socket, so
     * nothing in a JVM test reaches these three lines. Each is pinned WHOLE: a `contains` on a
     * fragment is blind to anything appended to the line, which is how a condition grows back.
     *
     * The three lines are the ones a partial decoupling leaves behind:
     *
     *  1. **staging** — re-add `&& pgpMode != null` and the receipt is dropped in silence again;
     *  2. **the JMAP route** — decide it on `item.pgpMode` instead of the entity and the receipt
     *     goes out through `Email/set`, where the server rebuilds it from fields and it arrives as
     *     an ordinary two-part mail no client reads as a receipt;
     *  3. **the IMAP body** — the entity has to replace the body there too.
     */
    @Test fun `both protocols route on the entity, never on the pgp mode`() {
        val source = repositorySource()
        val lines = source.lines().map { it.trim() }

        assertTrue(
            "staging must key on the entity alone. Nothing else may be added to the condition.",
            "if (payload.entityFile != null) {" in lines,
        )
        assertTrue(
            "the JMAP import-and-submit route must key on the entity, not on the PGP mode: " +
                "Email/set would re-encode the receipt and it would stop being one",
            "val sentEmailId = if (prebuiltEntity != null) {" in lines,
        )
        assertTrue(
            "the SMTP/IMAP message must carry the entity",
            "prebuiltEntity = prebuiltEntity," in lines,
        )
        assertTrue(
            "the entity must be read once, above the fork, so the two branches agree",
            "val prebuiltEntity = prebuiltEntityAt(item.pgpEntityPath) { path ->" in lines,
        )
    }

    /**
     * SOURCE LINT, on BOTH call sites. `OutboxLogic.canEdit` only refuses a row it is TOLD about:
     * a literal `false` for the third argument compiles, keeps `OutboxLogicTest` green, and puts
     * Edit back on a receipt — reopening it as its human half alone and sending that instead.
     *
     * ⚠ The screen is pinned as well as the repository, and they answer different failures. The
     * repository is the choke point (`takeOutboxForEdit` is where the row flips to EDITING, and it
     * refuses); the screen only draws the button — so a wrong argument THERE leaves a Modifier
     * button that does nothing when tapped, which is the dead action #70 set out to remove.
     */
    @Test fun `the edit guard is told whether the row can be rebuilt, at both call sites`() {
        assertTrue(
            "the guard at the state flip must ask about the row's own mode and entity path",
            "val unreplayable = OutboxLogic.isUnreplayablePrebuiltEntity(item.pgpMode, item.pgpEntityPath)"
                in repositorySource().lines().map { it.trim() },
        )
        assertTrue(
            "…and so must the guard behind the button",
            "OutboxLogic.isUnreplayablePrebuiltEntity(item.pgpMode, item.pgpEntityPath)"
                in locate("app/src/main/kotlin/app/sterna/ui/outbox/OutboxScreen.kt")
                    .readText().lines().map { it.trim() },
        )
        listOf(
            "if (!OutboxLogic.canEdit(item.pgpMode, item.state, unreplayable)) return null" to repositorySource(),
            "if (OutboxLogic.canEdit(item.pgpMode, item.state, unreplayable)) {" to
                locate("app/src/main/kotlin/app/sterna/ui/outbox/OutboxScreen.kt").readText(),
        ).forEach { (line, source) ->
            assertTrue(
                "the whole line '$line' must be there: a literal argument in its place is a guard " +
                    "that answers about nothing",
                line in source.lines().map { it.trim() },
            )
        }
    }

    /**
     * SOURCE LINT. `learnRecipients` guards the contacts list, and its default is `true` — drop the
     * argument at the one call site that needs `false` and nothing anywhere goes red.
     */
    @Test fun `the contacts list is taught through the one decision function`() {
        val lines = repositorySource().lines().map { it.trim() }

        assertTrue(
            "the addresses learned must come from recipientsToLearn, not straight from the row",
            "recipientsToLearn(recipients + ccTrimmed + bccTrimmed, learnRecipients)" in lines,
        )
        assertTrue(
            "nothing is learned when there is nothing to learn",
            "?.let { runCatching { rememberRecipients(it) } }" in lines,
        )
    }

    private fun repositorySource(): String =
        locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
