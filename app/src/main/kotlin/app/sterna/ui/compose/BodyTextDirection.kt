package app.sterna.ui.compose

import androidx.compose.ui.text.style.TextDirection

/**
 * The direction the composer body falls back to for a paragraph without a strong letter (a `-- `
 * signature line, a row of digits), taken from the first strong letter of the whole body (#275).
 *
 * `ContentOrLtr`/`ContentOrRtl`, never `Ltr`/`Rtl`: a paragraph with a strong letter of its own keeps
 * resolving from it, so a mixed body still lays each paragraph out in its own script. `Content` alone
 * would resolve a weak paragraph against the layout direction.
 *
 * Read with `Character.getDirectionality`, not `android.text.TextDirectionHeuristics`: the latter is
 * a stub in a JVM unit test and would leave this decision untestable there.
 */
fun bodyTextDirection(text: CharSequence): TextDirection {
    var i = 0
    while (i < text.length) {
        val cp = Character.codePointAt(text, i)
        when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return TextDirection.ContentOrLtr
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return TextDirection.ContentOrRtl
        }
        i += Character.charCount(cp)
    }
    return TextDirection.Content
}
