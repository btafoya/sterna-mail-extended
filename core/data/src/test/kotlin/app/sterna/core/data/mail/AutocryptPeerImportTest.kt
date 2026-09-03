package app.sterna.core.data.mail

import android.content.Intent
import app.sterna.core.data.pgp.PgpDecrypted
import app.sterna.core.data.pgp.PgpEngine
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receiving half of Autocrypt, EXECUTED: [autocryptPeerImportOf] decides, [importAutocryptPeer]
 * hands the decision to the provider, and both run here on a JVM.
 *
 * ⛔ Nothing in this file recomputes the rule to choose what it feeds in. Every case is a message's
 * header list as one of the two readers really produces it — `MimeParser.rawHeaders` (continuations
 * already joined with a space) or JMAP's `headers` property (the raw value, CRLF+WSP and all) — and
 * what is asserted is the decision that comes out, arguments included.
 */
class AutocryptPeerImportTest {

    private val received = "2026-09-02T10:15:00Z"
    private val receivedMillis = 1_788_344_100_000L
    private val keyBytes: ByteArray = Base64.getDecoder().decode(AutocryptTestKey.RSA_4096)

    private fun header(value: String) = listOf("From" to "Alice <alice@example.test>", "Autocrypt" to value)

    private fun decide(headers: List<Pair<String, String>>, from: String? = "alice@example.test") =
        autocryptPeerImportOf(headers, from, received)

    @Test fun `a well-formed header yields the peer, its key bytes and the message's date`() {
        val decided = decide(header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}"))
            ?: error("the header was refused")
        assertEquals("alice@example.test", decided.peerId)
        assertArrayEquals(keyBytes, decided.keyData)
        assertEquals(receivedMillis, decided.effectiveDateMillis)
    }

    @Test fun `a keydata folded the way IMAP unfolds it gives the same bytes`() {
        // What `MimeParser.rawHeaders` hands over: every continuation line joined with ONE space,
        // so the base64 comes back with spaces INSIDE it.
        val folded = AutocryptTestKey.RSA_4096.chunked(76).joinToString(" ")
        assertTrue("the fixture must actually be broken up", folded.contains(' '))
        val decided = decide(header("addr=alice@example.test; keydata=$folded")) ?: error("refused")
        assertArrayEquals(keyBytes, decided.keyData)
    }

    @Test fun `a keydata folded the way JMAP returns it gives the same bytes`() {
        // RFC 8621's `headers` property answers the raw field value: the CRLF + WSP of the sender's
        // folding are still in it.
        val folded = AutocryptTestKey.RSA_4096.chunked(76).joinToString("\r\n ")
        val decided = decide(header("addr=alice@example.test;\r\n keydata=$folded")) ?: error("refused")
        assertArrayEquals(keyBytes, decided.keyData)
        assertEquals("alice@example.test", decided.peerId)
    }

    @Test fun `two Autocrypt headers make the message worth nothing`() {
        // ⭐ THE guard. A second header slipped in by anyone on the path would otherwise file THEIR
        // key under the correspondent's address, and everything written to that address afterwards
        // is encrypted to them.
        val two = listOf(
            "From" to "Alice <alice@example.test>",
            "Autocrypt" to "addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}",
            "Autocrypt" to "addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}",
        )
        assertNull(autocryptPeerImportOf(two, "alice@example.test", received))
    }

    @Test fun `an addr that is not the From is refused, and a different case is not`() {
        assertNull(decide(header("addr=mallory@example.test; keydata=${AutocryptTestKey.RSA_4096}")))
        val cased = autocryptPeerImportOf(
            header("addr=Alice@Example.Test; keydata=${AutocryptTestKey.RSA_4096}"),
            " ALICE@example.test ",
            received,
        ) ?: error("a case difference must not lose the key")
        assertEquals("alice@example.test", cased.peerId)
    }

    @Test fun `an unknown critical attribute drops the message, an underscored one does not`() {
        assertNull(decide(header("addr=alice@example.test; foo=bar; keydata=${AutocryptTestKey.RSA_4096}")))
        val tolerated = decide(header("addr=alice@example.test; _foo=bar; keydata=${AutocryptTestKey.RSA_4096}"))
            ?: error("a non-critical attribute must be ignored, not fatal")
        assertArrayEquals(keyBytes, tolerated.keyData)
    }

    @Test fun `prefer-encrypt is read and dropped`() {
        val mutual = decide(header("addr=alice@example.test; prefer-encrypt=mutual; keydata=${AutocryptTestKey.RSA_4096}"))
            ?: error("prefer-encrypt is a known attribute; it must not drop the message")
        assertArrayEquals(keyBytes, mutual.keyData)
    }

    @Test fun `keydata that cannot be decoded is refused, without throwing`() {
        // 1 549 characters: one short of a whole base64 group, which is what a cut header looks like.
        assertNull(decide(header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096.dropLast(3)}")))
        assertNull(decide(header("addr=alice@example.test; keydata=%%%%")))
        assertNull(decide(header("addr=alice@example.test; keydata=")))
        assertNull(decide(header("addr=alice@example.test")))
    }

    @Test fun `a message with no Autocrypt header decides nothing`() {
        assertNull(autocryptPeerImportOf(listOf("From" to "Alice <alice@example.test>"), "alice@example.test", received))
    }

    @Test fun `a date that cannot be read is refused`() {
        // ⛔ Arbitration (rule 1, the irreversible first): the provider files a peer's key AGAINST a
        // date, and a made-up "now" on an old message can make a stale key win over the current one
        // inside OpenKeychain — where this app cannot undo it. No date, no import; the message opens
        // exactly as before.
        val h = header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}")
        assertNull(autocryptPeerImportOf(h, "alice@example.test", null))
        assertNull(autocryptPeerImportOf(h, "alice@example.test", "last tuesday"))
    }

    @Test fun `a From nobody stated is refused`() {
        val h = header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}")
        assertNull(autocryptPeerImportOf(h, null, received))
        assertNull(autocryptPeerImportOf(h, "  ", received))
    }

    @Test fun `the field name is matched without case, and a duplicate attribute is fatal`() {
        assertTrue(
            decide(listOf("From" to "a", "autocrypt" to "addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}")) != null,
        )
        assertNull(
            decide(
                header(
                    "addr=alice@example.test; addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}",
                ),
            ),
        )
    }

    // ── What the provider is actually told ──────────────────────────────────────────────────────

    @Test fun `the provider is given the peer id, the key bytes and the date, and nothing else`() {
        val engine = RecordingEngine()
        val done = runBlocking {
            importAutocryptPeer(
                engine,
                header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}"),
                "Alice@example.test",
                received,
            )
        }
        assertTrue(done)
        assertEquals(1, engine.updates.size)
        assertEquals("alice@example.test", engine.updates[0].first)
        assertArrayEquals(keyBytes, engine.updates[0].second)
        assertEquals(receivedMillis, engine.updates[0].third)
    }

    @Test fun `a message with no header never reaches the provider`() {
        val engine = RecordingEngine()
        val done = runBlocking {
            importAutocryptPeer(engine, listOf("From" to "Alice <alice@example.test>"), "alice@example.test", received)
        }
        assertTrue(!done)
        assertEquals(emptyList<Any>(), engine.updates)
    }

    @Test fun `two headers never reach the provider either`() {
        val engine = RecordingEngine()
        val two = listOf(
            "Autocrypt" to "addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}",
            "Autocrypt" to "addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}",
        )
        runBlocking { importAutocryptPeer(engine, two, "alice@example.test", received) }
        assertEquals(emptyList<Any>(), engine.updates)
    }

    @Test fun `no engine at all is not a failure`() {
        val done = runBlocking {
            importAutocryptPeer(
                null,
                header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}"),
                "alice@example.test",
                received,
            )
        }
        assertTrue(!done)
    }

    @Test fun `a provider that refuses is not a failure of the open either`() {
        for (answer in listOf(PgpResult.NotAvailable, PgpResult.Error("nope"))) {
            val engine = RecordingEngine(answer)
            val done = runBlocking {
                importAutocryptPeer(
                    engine,
                    header("addr=alice@example.test; keydata=${AutocryptTestKey.RSA_4096}"),
                    "alice@example.test",
                    received,
                )
            }
            assertTrue(!done)
            assertEquals(1, engine.updates.size)
        }
    }

    /** A [PgpEngine] that records the one call this feature makes and answers [answer] to it. */
    private class RecordingEngine(private val answer: PgpResult<Unit> = PgpResult.Success(Unit)) : PgpEngine {
        val updates = mutableListOf<Triple<String, ByteArray, Long>>()

        override suspend fun updateAutocryptPeer(
            peerId: String,
            keyData: ByteArray,
            effectiveDateMillis: Long,
        ): PgpResult<Unit> {
            updates += Triple(peerId, keyData, effectiveDateMillis)
            return answer
        }

        override suspend fun isAvailable(): Boolean = true
        override suspend fun getSignKeyId(userIdHint: String?, interactionResult: Intent?): PgpResult<Long> =
            unused()

        override suspend fun getPublicKey(
            keyId: Long,
            ownAddress: String,
            interactionResult: Intent?,
        ): PgpResult<ByteArray> = unused()
        override suspend fun findKeys(emails: List<String>, interactionResult: Intent?): PgpResult<LongArray> =
            unused()

        override suspend fun findKeysEach(emails: List<String>): Map<String, Boolean> = unused()
        override suspend fun detachedSign(
            data: ByteArray,
            signKeyId: Long,
            interactionResult: Intent?,
        ): PgpResult<PgpSignature> = unused()

        override suspend fun signAndEncrypt(
            data: ByteArray,
            signKeyId: Long?,
            recipientKeyIds: LongArray,
            interactionResult: Intent?,
        ): PgpResult<ByteArray> = unused()

        override suspend fun decryptVerify(
            data: ByteArray,
            senderAddress: String?,
            detachedSignature: ByteArray?,
            interactionResult: Intent?,
        ): PgpResult<PgpDecrypted> = unused()

        private fun <T> unused(): T = error("this feature must call nothing but updateAutocryptPeer")
    }
}
