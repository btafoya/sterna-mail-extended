package app.sterna.pgp

import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the keystore's two lookups make of an alias ([smimeKeyOf]): a key, nothing usable (the user is asked to
 * choose, the alias kept), or no answer (an error to retry, the alias kept); and the deadline a lookup gets.
 * `KeyChain` itself runs on a device only.
 */
class KeyChainSmimeKeysTest {

    private val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/smime/reader-cert.pem")!!) as X509Certificate

    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private

    @Test fun `a key and its chain are handed over as the keystore gave them`() {
        val found = smimeKeyOf(privateKey = { key }, chain = { arrayOf(certificate) })

        assertSame(key, found?.privateKey)
        assertEquals(listOf(certificate), found?.chain)
    }

    @Test fun `a key the keystore will not hand over, or one with no certificate, is nothing usable`() {
        assertNull(smimeKeyOf(privateKey = { null }, chain = { arrayOf(certificate) }))
        assertNull(smimeKeyOf(privateKey = { key }, chain = { null }))
        assertNull(smimeKeyOf(privateKey = { key }, chain = { emptyArray() }))
    }

    @Test fun `a keystore that throws, even an Error, gives no answer rather than nothing`() {
        listOf<() -> Nothing>(
            { throw java.security.GeneralSecurityException("no grant") },
            { throw AssertionError("could not bind to KeyChainService") },
            { throw InterruptedException() },
        ).forEach { failing ->
            val thrown = runCatching { smimeKeyOf(privateKey = failing, chain = { arrayOf(certificate) }) }.exceptionOrNull()
            assertTrue("$thrown", thrown is KeystoreUnanswered)
            Thread.interrupted()
        }
    }

    @Test(timeout = 10_000)
    fun `a keystore that never answers is given up on, and interrupted`() {
        var interrupted = false
        val thrown = runCatching {
            withinDeadline(200) {
                try {
                    Thread.sleep(60_000)
                } catch (stop: InterruptedException) {
                    interrupted = true
                    throw stop
                }
            }
        }.exceptionOrNull()

        assertTrue("$thrown", thrown is KeystoreUnanswered)
        assertTrue("the lookup's thread is interrupted", interrupted)
    }

    @Test fun `a lookup that answers in time is returned`() {
        assertEquals("answer", withinDeadline(5_000) { "answer" })
    }
}
