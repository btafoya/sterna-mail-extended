package app.sterna.core.data.pgp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one predicate every consequence of encrypting hangs off ([encrypts]): no draft, body blanked
 * in the outbox row at rest, recipient keys looked up.
 *
 * Exhaustive over [PgpMode.entries] ON PURPOSE. A mode added tomorrow that encrypts must be added
 * here too, and until it is this test fails — which is the whole point: the previous encrypting
 * mode was compared by name at six call sites, and a new one beside it answered "not encrypted" at
 * every single one, in silence.
 */
class PgpModeEncryptsTest {

    @Test fun `every mode says whether it turns the body into ciphertext`() {
        assertEquals(
            "a PgpMode was added, removed or changed its answer. If it encrypts, `encrypts` must " +
                "say so: otherwise the composer offers to save its plaintext as a draft on the " +
                "server, the outbox row keeps the plaintext at rest, and no recipient key is " +
                "looked up.",
            mapOf(
                PgpMode.OFF to false,
                PgpMode.SIGN to false,
                PgpMode.ENCRYPT to true,
                PgpMode.ENCRYPT_UNSIGNED to true,
            ),
            PgpMode.entries.associateWith { it.encrypts },
        )
    }
}
