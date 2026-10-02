package app.sterna.pgp

import android.app.Activity
import android.content.Context
import android.security.KeyChain
import android.security.keystore.KeyProperties
import androidx.core.content.ContextCompat
import app.sterna.core.data.pgp.SmimeKey
import app.sterna.core.data.pgp.SmimeKeySource
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/**
 * The S/MIME keys of Android's keystore, by the alias its certificate chooser gave. The private key is handed
 * over as `KeyChain` gives it: it never leaves the keystore, whose own provider runs the decryption.
 */
class KeyChainSmimeKeys(private val context: Context) : SmimeKeySource {
    override fun keyFor(alias: String): SmimeKey? = withinDeadline(KEYCHAIN_DEADLINE_MS) {
        smimeKeyOf(
            privateKey = { KeyChain.getPrivateKey(context, alias) },
            chain = { KeyChain.getCertificateChain(context, alias) },
        )
    }

    private companion object {
        /** `KeyChain` waits for its service without a limit of its own. */
        const val KEYCHAIN_DEADLINE_MS = 20_000L
    }
}

/**
 * Android's certificate chooser, offering the RSA keys only, the ones the engine can open an envelope with.
 * [onAnswer] runs on the main thread, with the alias chosen, or null when the user backed out: the system
 * answers on a thread of its own.
 */
fun chooseSmimeCertificate(activity: Activity, preselected: String?, onAnswer: (String?) -> Unit) {
    val main = ContextCompat.getMainExecutor(activity)
    KeyChain.choosePrivateKeyAlias(
        activity,
        { alias -> main.execute { onAnswer(alias) } },
        arrayOf(KeyProperties.KEY_ALGORITHM_RSA),
        null,
        null,
        preselected,
    )
}

/**
 * What [SmimeKeySource] answers from the keystore's two lookups. A null from either is "nothing usable under
 * this alias now": `KeyChain` gives null both for a removed certificate and for one whose access was never
 * granted or was withdrawn, and a choice is what fixes both. Anything thrown, `AssertionError` included (what
 * `KeyChain` throws when its service cannot be bound), is no answer at all and becomes [KeystoreUnanswered].
 */
internal fun smimeKeyOf(privateKey: () -> PrivateKey?, chain: () -> Array<X509Certificate>?): SmimeKey? {
    val key = answered(privateKey) ?: return null
    val certificates = answered(chain)?.toList().orEmpty()
    if (certificates.isEmpty()) return null
    return SmimeKey(key, certificates)
}

/** [block], run on another thread and interrupted after [millis]: a keystore that never answers is a failure. */
internal fun <T> withinDeadline(millis: Long, block: () -> T): T = try {
    runBlocking { withTimeout(millis) { runInterruptible(Dispatchers.IO) { block() } } }
} catch (late: TimeoutCancellationException) {
    throw KeystoreUnanswered(late)
}

class KeystoreUnanswered(cause: Throwable) :
    Exception("The Android keystore did not answer: ${cause.javaClass.simpleName}", cause)

private fun <T> answered(lookup: () -> T): T = try {
    lookup()
} catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
    throw KeystoreUnanswered(interrupted)
} catch (failure: Exception) {
    throw KeystoreUnanswered(failure)
} catch (unbound: AssertionError) {
    throw KeystoreUnanswered(unbound)
}
