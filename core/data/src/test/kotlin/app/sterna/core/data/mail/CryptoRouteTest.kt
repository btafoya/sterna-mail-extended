package app.sterna.core.data.mail

import app.sterna.core.imap.CryptoKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Executes the choice `decryptMessage` makes once it has read the message; `SmimeRouteCallSiteTest` pins that it asks. */
class CryptoRouteTest {

    private val pgpKinds = listOf(CryptoKind.PGP_ENCRYPTED, CryptoKind.PGP_SIGNED, CryptoKind.PGP_INLINE)
    private val smimeKinds = listOf(CryptoKind.SMIME_SIGNED, CryptoKind.SMIME_OPAQUE_SIGNED)

    private fun route(detected: CryptoKind?, locked: CryptoKind?, pgp: String?, smime: String?) =
        cryptoRoute(detected, locked, pgp, smime)

    @Test fun `S-MIME with no S-MIME engine shows the message as it is, whatever OpenPGP has`() {
        smimeKinds.forEach { kind ->
            listOf(null, "openkeychain").forEach { pgp ->
                assertEquals("$kind, pgp=$pgp", CryptoRoute.Unverifiable, route(kind, kind, pgp, null))
            }
        }
    }

    @Test fun `S-MIME goes to the S-MIME engine, never to OpenPGP`() {
        smimeKinds.forEach { kind ->
            listOf(null, "openkeychain").forEach { pgp ->
                assertEquals("$kind, pgp=$pgp", CryptoRoute.Smime("smime"), route(kind, kind, pgp, "smime"))
            }
        }
    }

    @Test fun `a message locked as S-MIME whose source holds no signature to check is shown as it is`() {
        listOf(null, "openkeychain").forEach { pgp ->
            assertEquals("pgp=$pgp", CryptoRoute.Unverifiable, route(null, CryptoKind.SMIME_SIGNED, pgp, null))
        }
    }

    @Test fun `OpenPGP without an OpenPGP app is still not available`() {
        pgpKinds.forEach { kind ->
            listOf(null, "smime").forEach { smime ->
                assertEquals("$kind, smime=$smime", CryptoRoute.OpenPgpMissing, route(kind, kind, null, smime))
            }
            assertEquals("$kind, nothing detected", CryptoRoute.OpenPgpMissing, route(null, kind, null, null))
        }
    }

    @Test fun `OpenPGP goes to the OpenPGP engine, and a lock with nothing found stays an error`() {
        pgpKinds.forEach { kind ->
            assertEquals(kind.name, CryptoRoute.OpenPgp("openkeychain"), route(kind, kind, "openkeychain", "smime"))
            assertEquals(kind.name, CryptoRoute.NothingFound, route(null, kind, "openkeychain", "smime"))
        }
    }

    @Test fun `an S-MIME engine does not verify a message whose source holds no S-MIME signature`() {
        assertEquals(CryptoRoute.Unverifiable, route(null, CryptoKind.SMIME_SIGNED, "openkeychain", "smime"))
    }

    @Test fun `what the source holds outranks what the message was locked as`() {
        assertEquals(
            "OpenPGP in the source, locked as S/MIME",
            CryptoRoute.OpenPgp("openkeychain"),
            route(CryptoKind.PGP_SIGNED, CryptoKind.SMIME_SIGNED, "openkeychain", "smime"),
        )
        assertEquals(
            "S/MIME in the source, locked as OpenPGP",
            CryptoRoute.Smime("smime"),
            route(CryptoKind.SMIME_SIGNED, CryptoKind.PGP_ENCRYPTED, "openkeychain", "smime"),
        )
    }

    @Test fun `only an OpenPGP lock with no OpenPGP app is refused before any fetch`() {
        (pgpKinds + null).forEach { kind ->
            assertEquals("$kind, no app", true, notAvailableBeforeFetch(kind, null))
            assertEquals("$kind, app", false, notAvailableBeforeFetch(kind, "openkeychain"))
        }
        smimeKinds.forEach { kind ->
            assertEquals("$kind, no app", false, notAvailableBeforeFetch(kind, null))
        }
    }
}
