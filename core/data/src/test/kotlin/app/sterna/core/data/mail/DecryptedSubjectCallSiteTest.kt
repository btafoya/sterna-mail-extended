package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [ProtectedSubjectTest] executes the decision; this pins WHAT IT IS CALLED WITH, in the one place
 * that matters — the `CryptoKind.PGP_ENCRYPTED` branch of `MailRepository.buildDecrypted`
 * (issue #128).
 *
 * A pure function says nothing about its arguments. `decryptedSubject(subject, raw)` — the outer
 * message source instead of the decrypted entity — compiles, keeps every other test green, and
 * puts the COVER subject back on screen while looking fixed. So does moving the call to the
 * `PGP_INLINE` branch, where the plaintext is not a MIME entity at all and a body line reading
 * "Subject: …" would become the title.
 *
 * Read out of the shipped source because `MailRepository` cannot be built on a JVM bench: its
 * `AccountStore` opens shared preferences in its constructor, and there is no Robolectric here
 * (same reason as [SearchNeverSyncedAccountTest]).
 *
 * The assertion is on the WHOLE trimmed line, by equality. Never `contains`: a `contains` is blind
 * to every mutation that LENGTHENS the line, which has been found three times on this repo.
 *
 * ⭐ And it screens EVERY code line of the branch that mentions a subject, not just the assignment
 * that opens with `subject =`. Narrowed to the assignment, a LATER write in the same branch —
 * `DecryptedEntry(MessageBody(display.copy(subject = email.subject), inline, crypto), entity)` —
 * puts the cover subject back and leaves this file green. Comment lines are dropped so the
 * neighbouring prose, which says "subject" several times, cannot satisfy or break the rule.
 */
class DecryptedSubjectCallSiteTest {

    @Test fun `the decrypted branch takes its subject from the decrypted entity`() {
        assertEquals(
            "the PGP_ENCRYPTED branch must assign the subject from the DECRYPTED ENTITY, " +
                "on exactly one line and with exactly these arguments, and no other code line " +
                "of the branch may touch a subject at all. Lines found:",
            listOf("subject = decryptedSubject(subject, entity),"),
            encryptedBranch().lines().map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { it.contains("subject", ignoreCase = true) },
        )
    }

    /**
     * The body of `buildDecrypted` up to the `PGP_INLINE` branch that follows the encrypted one.
     * Both markers are asserted, so a rename fails here loudly instead of handing back an empty
     * slice on which the assertion above would fail for the wrong reason.
     */
    private fun encryptedBranch(): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("private fun buildDecrypted(")
        check(start >= 0) { "MailRepository no longer declares buildDecrypted(...)" }
        val end = source.indexOf("CryptoKind.PGP_INLINE -> {", start)
        check(end > start) { "buildDecrypted no longer has a CryptoKind.PGP_INLINE branch after the encrypted one" }
        return source.substring(start, end)
    }

    /** [relative] resolved from the test's working directory, walking up — as [DaoQuerySource] does. */
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
