package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision behind a notification banner's Delete button, EXECUTED — not re-derived. Every
 * expectation below is a literal, so inverting an arm of the shipped rule turns these red.
 *
 * The harm: Delete on a banner was the only one of the three callers of `MailRepository.delete()`
 * that never asked whether the delete would move anything. Pressed on a banner left over from a
 * message the user had already deleted in the app, it dropped the cached row and its search-index
 * row while the server kept the message — it vanished from the screen until the next folder sync.
 * On an account with no Trash it threw, but only AFTER unlisting the message from a pending
 * Empty-trash order and (with markReadOnDelete on) marking it read.
 */
class BannerDeleteActTest {

    // --- The two ends where the button must do nothing at all, banner included ---

    @Test fun `a message already in Trash is not deleted again`() {
        assertEquals(BannerDeleteAct.DoNothing, bannerDeleteAct(cachedRole = "trash", hasCachedTrash = true))
    }

    @Test fun `an account whose synced folders name no Trash is left alone`() {
        // The role is known, so the folder list HAS been synced: no Trash here means no Trash.
        assertEquals(BannerDeleteAct.DoNothing, bannerDeleteAct(cachedRole = "inbox", hasCachedTrash = false))
    }

    // --- Everything else moves, which is the harmless direction ---

    @Test fun `an ordinary inbox message on an account with a Trash is moved`() {
        assertEquals(BannerDeleteAct.MoveToTrash, bannerDeleteAct(cachedRole = "inbox", hasCachedTrash = true))
    }

    @Test fun `an unknown folder is not a Trash, and does not refuse a delete either`() {
        // No cached row, or a folder the cache knows no role for. Refusing here would break an
        // ordinary Delete on an account whose folder list has not been cached yet.
        assertEquals(BannerDeleteAct.MoveToTrash, bannerDeleteAct(cachedRole = null, hasCachedTrash = true))
        assertEquals(BannerDeleteAct.MoveToTrash, bannerDeleteAct(cachedRole = null, hasCachedTrash = false))
    }

    @Test fun `a folder that lost its claim to the Trash role is not the Trash`() {
        // `~trash` is how the cache stores a folder whose claim another folder won
        // (UNELECTED_ROLE_MARK): a delete really does move OUT of it, so it takes the normal path.
        assertEquals(BannerDeleteAct.MoveToTrash, bannerDeleteAct(cachedRole = "~trash", hasCachedTrash = true))
    }

    @Test fun `a Trash row on an account whose Trash is not cached still moves nothing`() {
        // Both reasons at once — already at destination AND no elected Trash in the cache. Either
        // arm answers DoNothing, so this pins the ANSWER, not an order between them.
        assertEquals(BannerDeleteAct.DoNothing, bannerDeleteAct(cachedRole = "trash", hasCachedTrash = false))
    }
}
