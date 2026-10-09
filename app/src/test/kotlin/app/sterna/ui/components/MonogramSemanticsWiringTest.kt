package app.sterna.ui.components

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so no semantics tree is built
 * and no screen reader was listened to. That a clear at the tail of a chain silences the
 * descendants and spares what the caller's `modifier` set is the library's, read and not run.
 */
class MonogramSemanticsWiringTest {

    @Test fun `the badge ends its chain by clearing its semantics`() {
        val body = DeclarationSource.functionBody(
            DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/components/Monogram.kt"),
            "fun Monogram(",
        )
        val at = body.indexOf("modifier = modifier")
        check(at >= 0) { "Monogram no longer builds its chain from the caller's `modifier`" }
        assertEquals(
            "the initial is read aloud before the name it stands for (\"A, Alex\") unless the " +
                "badge clears its semantics, and the clear must come last: anywhere earlier it " +
                "also erases what a caller set through `modifier`, the Select label of a row's head",
            listOf(
                "modifier = modifier",
                ".size(MonogramSize)",
                ".clip(CircleShape)",
                ".background(background)",
                ".clearAndSetSemantics {},",
            ),
            body.subList(at, body.indexOf("contentAlignment = Alignment.Center,")),
        )
    }
}
