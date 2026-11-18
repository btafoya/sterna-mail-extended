package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.data.pgp.isSmime

/**
 * Whether the missing keys of [recipients] hold Send back: only under an encrypting S/MIME mode, where
 * no certificate selector exists and a recipient known to lack one can only fail the send. An OpenPGP
 * encrypting mode lets the send through, so the provider's own key selector is offered then.
 * In [recipientKeys], `false` is a known-missing key; an address absent from it is not yet checked
 * and passes.
 */
internal fun sendKeysReady(mode: PgpMode, recipients: List<String>, recipientKeys: Map<String, Boolean>): Boolean =
    !(mode.isSmime && mode.encrypts) || recipients.none { recipientKeys[it] == false }
