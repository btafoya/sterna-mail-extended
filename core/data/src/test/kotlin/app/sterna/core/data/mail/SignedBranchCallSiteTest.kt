package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [SignedEntityDisplayTest] executes [displayOfEntity]; `decryptMessage` needs an OpenPGP engine no JVM
 * test provides, so this pins that the signed branch of `buildDecrypted` calls it on the signed entity
 * and keeps that entity for `pgpAttachmentBytes`, which is where a "pgp:" attachment is read from.
 */
class SignedBranchCallSiteTest {

    @Test fun `the signed branch shows and keeps the signed entity`() {
        assertEquals(
            "the PGP_SIGNED branch of buildDecrypted must consist of exactly these code lines. Lines found:",
            listOf(
                "val entity = envelope.signedEntityRaw!!",
                "val shown = displayOfEntity(email, entity)",
                "DecryptedEntry(MessageBody(shown.email, shown.inlineImages, crypto), entity)",
            ),
            between("CryptoKind.PGP_SIGNED -> {", "private fun pgpAttachmentBytes(").lines()
                .drop(1)
                .map { it.trim() }
                .filterNot { it.isEmpty() || it == "}" || it.startsWith("//") }
                .filterNot { it.startsWith("*") || it.startsWith("/*") },
        )
    }

    private fun between(startMarker: String, endMarker: String): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf(startMarker)
        check(start >= 0) { "MailRepository no longer contains $startMarker" }
        check(source.indexOf(startMarker, start + 1) < 0) { "MailRepository contains $startMarker twice" }
        val end = source.indexOf(endMarker, start)
        check(end > start) { "MailRepository has no $endMarker after $startMarker" }
        return source.substring(start, end)
    }

    /** [relative] resolved from the test's working directory, walking up, as [DaoQuerySource] does. */
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
