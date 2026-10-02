package app.sterna.pgp

import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKey
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.data.pgp.SmimeVerified
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.MimeParser
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import app.sterna.ui.compose.smimeOffered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * When the account's certificate may sign for a From address ([smimeSigning]), and what leaves the composer in an
 * S/MIME mode ([smimeEntity]): a signed entity, or a refusal and nothing signed.
 */
class SmimeSigningTest {

    private val authority = SmimeTestAuthority()
    private val aliceKeys = authority.rsa()
    private val alice = authority.leaf("CN=Alice", aliceKeys, address = "alice@example.test")
    private val now = Date()

    private fun key(certificate: X509Certificate = alice) =
        SmimeKey(KeystoreOnlyKey(aliceKeys.private), listOf(certificate, authority.intermediate, authority.root))

    private class Keystore(private val answer: () -> SmimeKey?) : SmimeKeySource {
        val asked = mutableListOf<String>()

        override fun keyFor(alias: String): SmimeKey? {
            asked += alias
            return answer()
        }
    }

    /** Bouncy Castle's engine, counting the signatures it is asked for. */
    private class CountingEngine(private val inner: BouncyCastleSmimeEngine) : SmimeEngine by inner {
        var signed = 0

        override fun sign(signedBytes: ByteArray, key: SmimeKey): ByteArray {
            signed++
            return inner.sign(signedBytes, key)
        }
    }

    private val bc = BouncyCastleSmimeEngine(trustAnchors = { setOf(authority.root) })
    private val engine = CountingEngine(bc)

    private val payload = "Content-Type: text/plain; charset=utf-8\r\n\r\nHello."

    private fun refusal(signing: SmimeSigning) = (signing as? SmimeSigning.Refused)?.reason

    @Test fun `the certificate signs for an address it is issued for, compared without case`() {
        assertTrue(smimeSigning("alice", "alice@example.test", Keystore { key() }, now) is SmimeSigning.Ready)
        assertTrue(smimeSigning("alice", " Alice@Example.TEST ", Keystore { key() }, now) is SmimeSigning.Ready)
    }

    @Test fun `a From the certificate is not issued for is not offered`() {
        assertEquals(
            "an alias identity of the same account signs with a certificate for another address, and the reader is " +
                "told the sender does not match",
            SmimeRefusal.OTHER_ADDRESS,
            refusal(smimeSigning("alice", "alias@example.test", Keystore { key() }, now)),
        )
    }

    @Test fun `the DN's address counts only without a subject alternative name`() {
        val dnOnly = authority.leaf("CN=Grace, E=grace@example.test", aliceKeys)
        assertTrue(smimeSigning("grace", "grace@example.test", Keystore { key(dnOnly) }, now) is SmimeSigning.Ready)
        val both = authority.leaf("CN=Frank, E=frank.dn@example.test", aliceKeys, address = "frank@example.test")
        assertTrue(smimeSigning("frank", "frank@example.test", Keystore { key(both) }, now) is SmimeSigning.Ready)
        assertEquals(SmimeRefusal.OTHER_ADDRESS, refusal(smimeSigning("frank", "frank.dn@example.test", Keystore { key(both) }, now)))
    }

    @Test fun `an expired certificate, or one not valid yet, is not offered`() {
        val expired = authority.leaf(
            "CN=Alice", aliceKeys, address = "alice@example.test",
            notBefore = Date(now.time - 400 * SmimeTestAuthority.DAY), notAfter = Date(now.time - SmimeTestAuthority.DAY),
        )
        assertEquals(SmimeRefusal.NOT_VALID_NOW, refusal(smimeSigning("alice", "alice@example.test", Keystore { key(expired) }, now)))
        val early = authority.leaf(
            "CN=Alice", aliceKeys, address = "alice@example.test",
            notBefore = Date(now.time + SmimeTestAuthority.DAY), notAfter = Date(now.time + 400 * SmimeTestAuthority.DAY),
        )
        assertEquals(SmimeRefusal.NOT_VALID_NOW, refusal(smimeSigning("alice", "alice@example.test", Keystore { key(early) }, now)))
    }

    @Test fun `a certificate that may not sign mail is not offered`() {
        val encipherOnly = authority.leaf("CN=Alice", aliceKeys, address = "alice@example.test", usage = KeyUsage.keyEncipherment)
        assertEquals(
            "a certificate for encryption only signs, and the recipient reads a signature its certificate disowns",
            SmimeRefusal.NOT_FOR_SIGNING,
            refusal(smimeSigning("alice", "alice@example.test", Keystore { key(encipherOnly) }, now)),
        )
        val tls = authority.leaf("CN=Alice", aliceKeys, address = "alice@example.test", purpose = KeyPurposeId.id_kp_serverAuth)
        assertEquals(SmimeRefusal.NOT_FOR_SIGNING, refusal(smimeSigning("alice", "alice@example.test", Keystore { key(tls) }, now)))
        val nonRepudiation = authority.leaf("CN=Alice", aliceKeys, address = "alice@example.test", usage = KeyUsage.nonRepudiation)
        assertTrue(smimeSigning("alice", "alice@example.test", Keystore { key(nonRepudiation) }, now) is SmimeSigning.Ready)
    }

    @Test fun `no alias asks no keystore, and a keystore with no key or no answer refuses`() {
        val untouched = Keystore { key() }
        assertEquals(SmimeRefusal.NO_CERTIFICATE, refusal(smimeSigning("", "alice@example.test", untouched, now)))
        assertEquals(emptyList<String>(), untouched.asked)
        assertEquals(SmimeRefusal.NO_CERTIFICATE, refusal(smimeSigning("alice", "alice@example.test", Keystore { null }, now)))
        val silent = Keystore { throw KeystoreUnanswered(InterruptedException()) }
        assertEquals(SmimeRefusal.KEYSTORE_UNANSWERED, refusal(smimeSigning("alice", "alice@example.test", silent, now)))
    }

    @Test fun `the lock offers S-MIME exactly when it could sign, and a keystore that fails offers nothing`() {
        assertEquals(listOf(PgpMode.SMIME_SIGN, PgpMode.SMIME_ENCRYPT), smimeOffered("alice", "alice@example.test", Keystore { key() }, now))
        assertEquals(emptyList<PgpMode>(), smimeOffered("alice", "alias@example.test", Keystore { key() }, now))
        val untouched = Keystore { key() }
        assertEquals(emptyList<PgpMode>(), smimeOffered("", "alice@example.test", untouched, now))
        assertEquals(emptyList<String>(), untouched.asked)
        assertEquals(emptyList<PgpMode>(), smimeOffered("alice", "alice@example.test", Keystore { throw IllegalStateException("no service") }, now))
    }

    @Test fun `a certificate that may only sign is not offered to encrypt`() {
        val signOnly = authority.leaf("CN=Alice", aliceKeys, address = "alice@example.test", usage = KeyUsage.digitalSignature)
        assertEquals(
            "encrypting is offered with a certificate no envelope may be addressed to, and the Sent copy cannot be opened",
            listOf(PgpMode.SMIME_SIGN),
            smimeOffered("alice", "alice@example.test", Keystore { key(signOnly) }, now),
        )
    }

    @Test fun `a signed entity leaves, and the reader confirms it from the From address`() {
        val entity = withKeystoreProvider {
            smimeEntity(payload, "----sterna_smime_t", PgpMode.SMIME_SIGN, "alice", "alice@example.test", Keystore { key() }, engine, now, NO_ONE, NO_ONE, NONE_KEPT)
        }
        val envelope = MimeParser.detectCrypto("MIME-Version: 1.0\r\n$entity")!!
        assertEquals(CryptoKind.SMIME_SIGNED, envelope.kind)
        assertEquals("the payload goes out as it was signed", payload, envelope.signedEntityRaw)
        val verified: SmimeVerified = bc.verifyDetached(payload.toByteArray(Charsets.UTF_8), envelope.signatureDer!!, "alice@example.test")
        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
    }

    @Test fun `a key absent at send refuses the message and signs nothing`() {
        assertRefused(SmimeRefusal.NO_CERTIFICATE) {
            smimeEntity(payload, "----sterna_smime_t", PgpMode.SMIME_SIGN, "alice", "alice@example.test", Keystore { null }, engine, now, NO_ONE, NO_ONE, NONE_KEPT)
        }
        assertRefused(SmimeRefusal.KEYSTORE_UNANSWERED) {
            smimeEntity(
                payload, "----sterna_smime_t", PgpMode.SMIME_SIGN, "alice", "alice@example.test",
                Keystore { throw KeystoreUnanswered(InterruptedException()) }, engine, now, NO_ONE, NO_ONE, NONE_KEPT,
            )
        }
        assertRefused(SmimeRefusal.OTHER_ADDRESS) {
            smimeEntity(payload, "----sterna_smime_t", PgpMode.SMIME_SIGN, "alice", "alias@example.test", Keystore { key() }, engine, now, NO_ONE, NO_ONE, NONE_KEPT)
        }
        assertEquals("the engine was asked to sign", 0, engine.signed)
    }

    private companion object {
        val NO_ONE = emptyList<String>()
        val NONE_KEPT = emptyMap<String, ByteArray>()
    }

    private fun assertRefused(reason: SmimeRefusal, send: () -> String) {
        try {
            val sent = send()
            fail("the message left, as <${sent.take(80)}>, instead of being refused for $reason")
        } catch (refused: SmimeSendRefused) {
            assertEquals(reason, refused.reason)
        }
    }
}
