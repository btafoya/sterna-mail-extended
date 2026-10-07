package app.sterna.core.data.account

/** The user id hint for the OpenPGP provider's key chooser: the default identity's address, else
 *  the login when that is an address, else null. A login can be a bare name (`john.doe`), which
 *  matches no user id on a keyring (#246). Not the address the cached public key is minimized
 *  to: that one stays the login, the only address [autocryptHeaderValue] announces. */
fun pgpOwnAddress(account: StoredAccount): String? =
    listOfNotNull(account.defaultIdentity()?.email, account.username)
        .firstOrNull(::plausibleIdentityAddress)
        ?.trim()
