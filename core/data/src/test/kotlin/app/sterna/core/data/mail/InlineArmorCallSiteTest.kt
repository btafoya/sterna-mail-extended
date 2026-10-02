package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `InlineArmorOctetsTest` (core/imap) executes `inlineVerifyBytes` and `inlineDisplayText`;
 * `decryptMessage` needs a fetched message and an OpenPGP engine no JVM test provides, so this pins
 * that the repository's two inline sites call them, with the envelope and the engine's plaintext,
 * and that the other kinds keep their own bytes.
 * It does not cover what the engine does with those bytes.
 */
class InlineArmorCallSiteTest {

    @Test fun `every kind hands the verifier its own bytes and inline goes through inlineVerifyBytes`() {
        assertEquals(
            "decryptMessage must hand decryptVerify these arguments, one kind per branch, in this " +
                "order. Lines found:",
            listOf(
                "CryptoKind.PGP_ENCRYPTED -> pgp.decryptVerify(",
                "envelope.encryptedArmor!!.toByteArray(Charsets.UTF_8),",
                "CryptoKind.PGP_INLINE -> pgp.decryptVerify(",
                "inlineVerifyBytes(envelope),",
                "CryptoKind.PGP_SIGNED -> pgp.decryptVerify(",
                "canonicalizeCrlf(envelope.signedEntityRaw!!).toByteArray(Charsets.ISO_8859_1),",
                "detachedSignature = envelope.signatureArmor!!.toByteArray(Charsets.UTF_8),",
            ),
            codeLines(between("suspend fun decryptMessage(", "private suspend fun smimeVerify("))
                .filter { line ->
                    listOf("decryptVerify", "toByteArray", "inlineVerifyBytes", "Armor", "plaintext")
                        .any { line.contains(it) }
                },
        )
    }

    @Test fun `the inline branch shows the plaintext through inlineDisplayText`() {
        assertEquals(
            "the PGP_INLINE branch of buildDecrypted must read the engine's plaintext on exactly " +
                "this line. Lines found:",
            listOf("val text = inlineDisplayText(envelope, decrypted.plaintext)"),
            codeLines(between("CryptoKind.PGP_INLINE -> {", "CryptoKind.PGP_SIGNED, CryptoKind.SMIME_SIGNED -> {"))
                .filter { line ->
                    listOf("plaintext", "toString(", "Charsets", "inlineDisplayText", "val text")
                        .any { line.contains(it) }
                },
        )
    }

    private fun codeLines(block: String): List<String> =
        block.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** The source from [startMarker] (included) to the first [endMarker] after it (excluded). */
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
