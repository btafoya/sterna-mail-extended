package app.sterna.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.ui.components.EmptyArt
import app.sterna.ui.components.EmptyState

/**
 * Shown at startup when the stored account list could not be read
 * ([RootState.AccountsUnreadable]). It says three things and does nothing:
 *
 *  - the account settings on this device could not be read;
 *  - NOTHING was deleted — they are still stored, untouched, and the store refuses every write
 *    while it cannot read them, which is precisely why the app cannot carry on as usual;
 *  - UPDATING the app may bring them back, at its first launch. Never "reinstalling": uninstalling
 *    wipes `/data/data/app.sterna`, which takes the blob AND the Keystore key with it (that is why
 *    [app.sterna.core.data.account.AccountStore.clear] deletes the key too), so the advice that
 *    reads as the gentler one is the one that destroys the accounts for good. And "may", not
 *    "will": the gate raises its flag on ANY decode failure — a truncated blob, a damaged prefs
 *    file — and no build fixes those.
 *
 * ⛔ NO BUTTON, on purpose, and least of all a "start over" one. The only action that could
 * "unblock" this screen is destroying the accounts it exists to protect, and a destructive button
 * on a screen reached by accident is how the data gets lost anyway. Android's own "Clear data"
 * remains available in the system settings, with the system's own confirmation in front of it; it
 * is not offered here and not mentioned in the text, because pointing a stuck user at a wipe is
 * pointing her at the loss this whole change prevents.
 *
 * ⛔ No "add an account" either: the store refuses to write, so that account would be accepted,
 * appear to work, and be gone at the next launch — the very trap this screen replaces.
 */
@Composable
fun AccountsUnreadableScreen() {
    Surface(Modifier.fillMaxSize()) {
        // Scrollable so the whole message stays reachable at large font scales.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                art = EmptyArt.OFFLINE,
                title = stringResource(R.string.accounts_unreadable_title),
                body = stringResource(R.string.accounts_unreadable_body),
            )
        }
    }
}
