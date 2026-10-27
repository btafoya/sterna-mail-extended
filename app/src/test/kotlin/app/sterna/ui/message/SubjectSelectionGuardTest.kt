package app.sterna.ui.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectSelectionGuardTest {

    @Test fun `a touch on the sender row after a long-press on the subject drops the selection`() {
        val guard = SubjectSelectionGuard()
        assertFalse("a touch on the subject itself is how a selection starts", guard.touched(onSubject = true))
        assertTrue("a touch outside the subject must drop it", guard.touched(onSubject = false))
        assertFalse("dropped once, nothing is left to drop", guard.touched(onSubject = false))
    }

    @Test fun `a touch outside the subject with no selection started drops nothing`() {
        assertFalse(SubjectSelectionGuard().touched(onSubject = false))
    }

    @Test fun `the header starting to fold drops the selection, once`() {
        val guard = SubjectSelectionGuard()
        assertFalse("the first placement only learns where the subject rests", guard.placed(120f))
        guard.touched(onSubject = true)
        assertFalse("the subject placed where it was has not moved", guard.placed(120f))
        assertTrue("the subject one pixel higher means the header is folding", guard.placed(119f))
        assertFalse("the rest of the fold must not rebuild the subject every frame", guard.placed(80f))
    }

    @Test fun `the header unfolding drops nothing`() {
        val guard = SubjectSelectionGuard()
        guard.placed(40f)
        guard.touched(onSubject = true)
        assertFalse(guard.placed(120f))
        assertTrue("still armed after moving down, the next fold drops it", guard.placed(100f))
    }

    @Test fun `a fold with no selection started drops nothing, and does not keep a later one from dropping`() {
        val guard = SubjectSelectionGuard()
        guard.placed(120f)
        assertFalse(guard.placed(60f))
        guard.touched(onSubject = true)
        assertTrue(guard.placed(20f))
    }
}
