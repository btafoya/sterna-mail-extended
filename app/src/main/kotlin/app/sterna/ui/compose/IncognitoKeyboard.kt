package app.sterna.ui.compose

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Ask the keyboard not to learn from a message being written (#120).
 * `IME_FLAG_NO_PERSONALIZED_LEARNING` is the one documented way to say so, and it is a REQUEST: an IME
 * is free to ignore it. Unconditional, no setting: a guarantee you have to switch on is one you can
 * forget to switch on.
 *
 * An interceptor and not `KeyboardOptions`, which exposes no `imeOptions` flags at all:
 * [InterceptPlatformTextInput] is the only seam handing us the real `EditorInfo`, and it leaves the
 * fields' own options untouched (#83, #26). SCOPE: the composer only, never the application root.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun IncognitoKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(INCOGNITO_INTERCEPTOR, content)
}

/**
 * The interceptor itself, a top-level value rather than something built inside the composition: it
 * captures nothing, so every instance would be interchangeable, and a fresh one per recomposition
 * would restart the input session.
 *
 * `internal` rather than `private` so a JVM test can RUN it: what it hands to
 * [PlatformTextInputSession.startInputMethod] is the whole fix, and a source lint reads that line
 * without ever proving it.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal val INCOGNITO_INTERCEPTOR = object : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        val incognito = object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                // The delegate WRITES outAttributes (imeOptions, inputType, the action of the
                // field that asked). So it runs first and our bit is added after — reversing the
                // two lines would silently drop the flag.
                val connection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = incognitoImeOptions(outAttributes.imeOptions)
                return connection
            }
        }
        return nextHandler.startInputMethod(incognito)
    }
}

/**
 * Add the no-personalized-learning bit to whatever the field already asked for, out here so a JVM
 * test can execute it.
 *
 * `or`, never `=`: the value already carries the field's own action and Compose's flags, and an
 * assignment would wipe them, taking #83's Enter-makes-a-chip with it.
 *
 * Kotlin inlines Java `static final int` constants, so this loads no Android class at run time.
 */
internal fun incognitoImeOptions(imeOptions: Int): Int =
    imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
