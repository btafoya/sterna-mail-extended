package app.sterna.core.data.mail

import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.jmap.model.Identity

/**
 * What one `Identity/get` answer becomes in `StoredAccount.serverIdentities` — the list the
 * settings screen and the composer's From picker read.
 *
 * A plain function, called from `MailRepository.refreshServerIdentities`, for one reason: the
 * repository needs a device, a database and a live session, so nothing inside it can be executed
 * by a JVM test. The values that land in the account can be, and are ([IdentityRefreshAfterCreateTest]).
 *
 * Two rules, both visible on screen:
 *  - an identity the server gives no address for is dropped: it can be neither shown nor sent
 *    from, and it would take a row in the picker that selects to nothing;
 *  - a legacy signature carrying raw HTML in the plain-text field is split
 *    ([StoredIdentity.withSplitSignature]), so the composer never shows tag soup.
 */
internal fun storedServerIdentities(identities: List<Identity>): List<StoredIdentity> =
    identities
        .filter { it.email.isNotBlank() }
        .map {
            StoredIdentity(
                id = it.id,
                name = it.name.orEmpty(),
                email = it.email,
                signature = it.textSignature.orEmpty(),
                signatureHtml = it.htmlSignature.orEmpty(),
            ).withSplitSignature()
        }
