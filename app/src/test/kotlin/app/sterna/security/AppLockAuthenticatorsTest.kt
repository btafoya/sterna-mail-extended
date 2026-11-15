package app.sterna.security

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLockAuthenticatorsTest {

    /**
     * androidx.biometric 1.1.0 answers `BIOMETRIC_ERROR_UNSUPPORTED` to `BIOMETRIC_STRONG or
     * DEVICE_CREDENTIAL` on API 28 and 29, whatever lock the device has set up. Below API 30 the
     * prompt never asks for a strong biometric anyway: `promptUnlock` only calls
     * `setDeviceCredentialAllowed`.
     */
    @Test fun `the checked combination is one androidx biometric accepts on every API level`() {
        for (sdk in LEVELS) {
            val authenticators = appLockCheckAuthenticators(sdk)
            assertTrue(
                "API $sdk: canAuthenticate is asked 0x${Integer.toHexString(authenticators)}, a " +
                    "combination androidx.biometric 1.1.0 rejects as BIOMETRIC_ERROR_UNSUPPORTED, " +
                    "so the app lock cannot be turned on",
                isSupportedCombination(authenticators, sdk),
            )
        }
    }

    @Test fun `strong biometric from API 30, weak biometric below`() {
        for (sdk in LEVELS) {
            val expected = if (sdk >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
            assertEquals("API $sdk", expected, appLockCheckAuthenticators(sdk))
        }
    }

    @Test fun `canAuthenticate asks for the combination of the running API level`() {
        val lines = codeLines()
        assertTrue(
            "LockScreen.kt: `canAuthenticate` must call `BiometricManager.canAuthenticate` with " +
                "`appLockCheckAuthenticators(Build.VERSION.SDK_INT)`, as a whole line",
            "BiometricManager.from(context).canAuthenticate(appLockCheckAuthenticators(Build.VERSION.SDK_INT)) == " +
                "BiometricManager.BIOMETRIC_SUCCESS" in lines,
        )
    }

    /**
     * The check and the prompt must switch at the same API level. A prompt that takes the
     * `setAllowedAuthenticators` path one level earlier than the check leaves Android 10 with a
     * check that accepts `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` and a prompt whose `build()` throws
     * on `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`: no other test here executes `promptUnlock`.
     */
    @Test fun `the prompt switches path at the same API level as the check`() {
        val lines = codeLines()
        val check = lines.indexOf("internal fun appLockCheckAuthenticators(sdkInt: Int): Int =")
        assertTrue("LockScreen.kt no longer declares `appLockCheckAuthenticators` on a whole line", check >= 0)
        assertEquals(
            "`appLockCheckAuthenticators` must switch to `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` at " +
                "`Build.VERSION_CODES.R`, the level at which `promptUnlock` starts passing it to " +
                "`setAllowedAuthenticators`. Anywhere else, the app lock can be turned on under " +
                "Android 10 and then never unlocked, or refused where the prompt would have worked.",
            "if (sdkInt >= Build.VERSION_CODES.R) AUTHENTICATORS else BIOMETRIC_WEAK or DEVICE_CREDENTIAL",
            lines.getOrNull(check + 1),
        )
        val branch = "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {"
        val prompt = lines.indexOf(branch)
        assertTrue(
            "`promptUnlock` must choose its path on the whole line `$branch`, the same threshold " +
                "as `appLockCheckAuthenticators`. Under Android 10 a lower threshold builds the " +
                "prompt with `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`, " +
                "which throws at `build()`: the app lock can be turned on and never unlocked.",
            prompt >= 0,
        )
        assertEquals(
            "LockScreen.kt has more than one `$branch`: this rule can no longer tell which one " +
                "is the prompt's.",
            prompt,
            lines.lastIndexOf(branch),
        )
        assertEquals(
            "the `$branch` of `promptUnlock` must be followed by " +
                "`builder.setAllowedAuthenticators(AUTHENTICATORS)`: otherwise the threshold " +
                "pinned here no longer decides which authenticators the prompt is built with.",
            "builder.setAllowedAuthenticators(AUTHENTICATORS)",
            lines.getOrNull(prompt + 1),
        )
    }

    /** The file's lines, trimmed, without comments or blanks. */
    private fun codeLines(): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, LOCK_SCREEN).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
        return File(root, LOCK_SCREEN).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private companion object {
        const val LOCK_SCREEN = "app/src/main/kotlin/app/sterna/security/LockScreen.kt"
        val LEVELS = listOf(26, 27, 28, 29, 30, 34, 36)

        /** `AuthenticatorUtils.isSupportedCombination` of androidx.biometric 1.1.0, with `SDK_INT` as a parameter. */
        fun isSupportedCombination(authenticators: Int, sdkInt: Int): Boolean = when (authenticators) {
            BIOMETRIC_STRONG, BIOMETRIC_WEAK, BIOMETRIC_WEAK or DEVICE_CREDENTIAL -> true
            DEVICE_CREDENTIAL -> sdkInt >= 30
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL -> sdkInt < 28 || sdkInt > 29
            else -> false
        }
    }
}
