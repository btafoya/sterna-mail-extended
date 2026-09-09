package app.sterna.core.data.mail

import android.content.ContextWrapper
import app.sterna.core.data.account.AccountStore
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the JVM unit-test boundary of this module actually sits, measured rather than inferred.
 * The module declares no testOptions, no returnDefaultValues and no Robolectric, so every
 * `android.*` member on the unit-test classpath comes from the stub android.jar.
 *
 * The rule the stub jar follows, and it is not the one the seams were scoped against: a stub
 * constructor runs and yields an object, while every stub method throws "not mocked" when called.
 * So a field of an `android.*` type does not by itself stop a class being built on the JVM; what
 * stops it is a method call, whether in the constructor or on the path under test.
 */
class JvmBoundaryProbeTest {

    private fun notMocked(body: () -> Unit): RuntimeException {
        val failure = assertThrows(RuntimeException::class.java, body)
        assertTrue(failure.message, failure.message.orEmpty().contains("not mocked"))
        return failure
    }

    /** Constructing one is fine, so the two decrypted-body caches do not block instantiation. */
    @Test
    fun anAndroidLruCacheIsConstructibleButUnusable() {
        val cache = android.util.LruCache<String, String>(4)
        notMocked { cache.put("k", "v") }
    }

    /** Every logging call on a path under test throws, so a seam is needed to traverse one. */
    @Test
    fun androidLogThrowsOnEveryLevelThisModuleUses() {
        notMocked { android.util.Log.i("t", "m") }
        notMocked { android.util.Log.w("t", "m") }
        notMocked { android.util.Log.w("t", "m", RuntimeException("x")) }
    }

    /**
     * [MailRepository] takes this constructor's default clock, `SystemClock::elapsedRealtime`.
     * Creating the reference is not calling it, so the default costs nothing here.
     */
    @Test
    fun ghostSweepScheduleIsConstructibleWithItsDefaultClock() {
        GhostSweepSchedule()
    }

    /**
     * A Context object exists on the stub classpath, and it is still useless: [AccountStore] reads
     * preferences through it while being built, which is what a seam has to route around.
     */
    @Test
    fun accountStoreCannotBeBuiltFromAStubContext() {
        val context = ContextWrapper(null)
        notMocked { AccountStore(context) }
    }
}
