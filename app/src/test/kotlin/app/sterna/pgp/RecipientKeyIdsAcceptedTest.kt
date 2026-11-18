package app.sterna.pgp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientKeyIdsAcceptedTest {

    @Test fun `fewer keys than addresses are accepted when the user picked them`() {
        assertTrue(
            "a set picked in the provider's key selector, one key standing for two addresses, is " +
                "refused: the send fails on missing keys right after the user chose them",
            recipientKeyIdsAccepted(longArrayOf(0x11L), addressCount = 2, picked = true),
        )
    }

    @Test fun `fewer keys than addresses are refused when nobody picked them`() {
        assertFalse(
            "a short key list the provider resolved on its own is accepted: an address it found " +
                "no key for receives a message it cannot read",
            recipientKeyIdsAccepted(longArrayOf(0x11L), addressCount = 2, picked = false),
        )
    }

    @Test fun `an empty list is refused, picked or not`() {
        for (picked in listOf(true, false)) for (count in listOf(0, 1, 2)) {
            assertFalse(
                "an empty key list is accepted (picked=$picked, $count addresses): the message is " +
                    "encrypted to the sender's own key alone",
                recipientKeyIdsAccepted(LongArray(0), addressCount = count, picked = picked),
            )
        }
    }

    @Test fun `no list is refused`() {
        for (picked in listOf(true, false)) {
            assertFalse(recipientKeyIdsAccepted(null, addressCount = 1, picked = picked))
        }
    }

    @Test fun `as many keys as addresses or more are accepted without a pick`() {
        assertTrue(recipientKeyIdsAccepted(longArrayOf(0x11L, 0x22L), addressCount = 2, picked = false))
        assertTrue(recipientKeyIdsAccepted(longArrayOf(0x11L, 0x22L, 0x33L), addressCount = 2, picked = false))
        assertTrue(recipientKeyIdsAccepted(longArrayOf(0x11L, 0x22L), addressCount = 2, picked = true))
    }

    @Test fun `the engine hands the rule the key ids, the address count and whether the selector answered`() {
        // Source lint, the complement of the cases above: they run the rule, this pins what
        // `findKeys` feeds it. OpenKeychainPgpEngine cannot run in a JVM test.
        val code = java.io.File(root, ENGINE).readLines().map { it.trim() }
        assertTrue(
            "findKeys no longer reads the pick from the interaction result it was handed",
            "val picked = interactionResult?.hasExtra(OpenPgpApi.EXTRA_KEY_IDS_SELECTED) == true" in code,
        )
        assertTrue(
            "findKeys no longer decides through recipientKeyIdsAccepted with its own ids, address count and pick",
            "if (ids != null && recipientKeyIdsAccepted(ids, emails.size, picked)) PgpResult.Success(ids)" in code,
        )
    }

    private companion object {
        const val ENGINE = "app/src/main/kotlin/app/sterna/pgp/OpenKeychainPgpEngine.kt"
        val root: java.io.File by lazy {
            generateSequence(java.io.File("").absoluteFile) { it.parentFile }
                .firstOrNull { java.io.File(it, ENGINE).isFile }
                ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        }
    }
}
