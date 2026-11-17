package app.sterna.ui.compose

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.jmap.model.Email
import app.sterna.util.isValidEmail

/** No stored identity carries this id prefix. */
internal const val PASSING_IDENTITY_ID_PREFIX = "passing:"

internal fun isPassingIdentity(identity: StoredIdentity): Boolean =
    identity.id.startsWith(PASSING_IDENTITY_ID_PREFIX)

/** The identity is asked first: a message to an identity and to an alias opens under the identity. */
internal fun receivingOrPassingFromOption(
    options: List<FromOption>,
    account: StoredAccount?,
    o: Email,
): FromOption? =
    receivingFromOption(options, account?.id, o) ?: passingFromOption(options, account, o)

/**
 * The address [o] was delivered to that is none of [account]'s identities, as a From for this
 * composer only, never stored. The proof is what the account's server accepts in a From: on JMAP a
 * `+tag` sub-address of an identity in To then Cc (an alias is `forbiddenFrom`); on IMAP the highest
 * `X-Original-To` or `Delivered-To`, on an identity's domain and off any list (To and Cc never
 * prove an alias there: SMTP answers 501). A linked account gets none: `onBehalf` sends no envelope.
 */
internal fun passingFromOption(
    options: List<FromOption>,
    account: StoredAccount?,
    o: Email,
): FromOption? {
    if (account == null) return null
    val received = when (account.protocol) {
        MailProtocol.IMAP -> deliveredAddressOf(o)
        MailProtocol.JMAP -> subAddressInRecipients(o, identityAddressesOf(identitiesOf(options, account)))
    }
    return passingFromByForm(options, account, received)
}

/**
 * [address] as a passing From of [account] by its form alone, the proof of where it was delivered
 * being the caller's: a sub-address of an identity on JMAP, an address on an identity's domain on
 * IMAP, none on a linked account.
 */
internal fun passingFromByForm(
    options: List<FromOption>,
    account: StoredAccount?,
    address: String?,
): FromOption? {
    if (account == null || account.isLinked || address == null) return null
    val mine = identitiesOf(options, account)
    val identityAddresses = identityAddressesOf(mine)
    if (!isValidEmail(address) || address.lowercase() in identityAddresses) return null
    if (account.protocol == MailProtocol.JMAP && subAddressBase(address)?.lowercase() !in identityAddresses) return null
    val template = passingTemplate(mine, address, account.defaultIdentity()?.id) ?: return null
    return FromOption(
        account.id,
        StoredIdentity(
            id = PASSING_IDENTITY_ID_PREFIX + address.lowercase(),
            name = template.name,
            email = address,
            signature = template.signature,
            signatureHtml = template.signatureHtml,
        ),
    )
}

private fun identitiesOf(options: List<FromOption>, account: StoredAccount): List<StoredIdentity> =
    options.filter { it.accountId == account.id && !isPassingIdentity(it.identity) }.map { it.identity }

private fun identityAddressesOf(identities: List<StoredIdentity>): Set<String> =
    identities.map { it.email.trim().lowercase() }.toSet()

private fun deliveredAddressOf(o: Email): String? {
    if (o.listId != null) return null
    return o.deliveredAddress
        ?.trim()?.removePrefix("<")?.removeSuffix(">")?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun subAddressInRecipients(o: Email, identityAddresses: Set<String>): String? =
    (o.to + o.cc)
        .map { it.email.trim() }
        .firstOrNull { address ->
            address.lowercase() !in identityAddresses &&
                subAddressBase(address)?.lowercase() in identityAddresses
        }

/** Cut at the first `+` as `SubmissionIdentity` cuts it, so the base is the identity it submits under. */
internal fun subAddressBase(address: String): String? {
    val at = address.lastIndexOf('@')
    val plus = address.indexOf('+')
    if (at <= 0 || plus <= 0 || plus > at) return null
    return address.substring(0, plus) + address.substring(at)
}

/** The sub-address's base identity, else the default identity on the domain, else the domain's first. */
private fun passingTemplate(mine: List<StoredIdentity>, address: String, defaultIdentityId: String?): StoredIdentity? {
    val base = subAddressBase(address)
    mine.firstOrNull { base != null && it.email.trim().equals(base, ignoreCase = true) }?.let { return it }
    val domain = domainOf(address)
    val ofDomain = mine.filter { domainOf(it.email) == domain }
    return ofDomain.firstOrNull { it.id == defaultIdentityId } ?: ofDomain.firstOrNull()
}

private fun domainOf(address: String): String =
    address.trim().substringAfterLast('@', missingDelimiterValue = "").lowercase()

internal fun withFromOption(options: List<FromOption>, option: FromOption): List<FromOption> =
    if (option in options) options else options + option

/** [options] count: a passing From left out of a reply-all's selves re-addresses the alias it answers from. */
internal fun replyingSelves(
    username: String,
    identities: List<StoredIdentity>,
    options: List<FromOption>,
    accountId: String,
): Set<String> =
    (listOf(username) + identities.map { it.email } + options.filter { it.accountId == accountId }.map { it.identity.email })
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

/**
 * The address a reply-all opened from the cache must drop from its recipients once the fetched
 * original settles the From on [settled]: the cached prefill left out only the selves known then,
 * and a passing From is learnt from the original's delivery headers alone.
 */
internal fun recipientDroppedOnceSettled(
    opening: ComposeOpening,
    prefilledFromCache: Boolean,
    atCache: FromOption?,
    settled: FromOption?,
): String? =
    settled
        ?.takeIf { prefilledFromCache && opening == ComposeOpening.REPLY_ALL && it != atCache && isPassingIdentity(it.identity) }
        ?.identity?.email

/**
 * [field] without the committed chips naming [address], case folded, `Name <address>` included; the
 * token still being typed is left as it is, and a field naming no such chip comes back untouched.
 */
internal fun recipientsWithout(field: String, address: String): String {
    val target = address.trim().lowercase()
    val (chips, input) = splitRecipients(field)
    val kept = chips.filterNot { chipAddress(it) == target }
    if (target.isEmpty() || kept.size == chips.size) return field
    return joinRecipients(kept, input)
}

private fun chipAddress(chip: String): String {
    val open = chip.lastIndexOf('<')
    val close = chip.lastIndexOf('>')
    val address = if (open >= 0 && close > open) chip.substring(open + 1, close) else chip
    return address.trim().lowercase()
}

/**
 * A stored From is a bare address, so one that is none of the identities is judged by
 * [passingFromByForm]: the original that proved where it was delivered is not at hand.
 */
internal fun reopenedLocalDraftFrom(
    options: List<FromOption>,
    accountId: String,
    account: StoredAccount?,
    written: String,
): FromOption? =
    options.firstOrNull { it.accountId == accountId && it.identity.email.equals(written, ignoreCase = true) }
        ?: account?.takeIf { it.id == accountId }?.let { passingFromByForm(options, it, written) }

internal fun reopenedQueuedRowFrom(
    options: List<FromOption>,
    fromAccountId: String?,
    account: StoredAccount?,
    written: String?,
): FromOption? =
    options.firstOrNull { it.accountId == fromAccountId && it.identity.email == written }
        ?: account?.takeIf { it.id == fromAccountId }?.let { passingFromByForm(options, it, written) }
        ?: options.firstOrNull { it.accountId == fromAccountId }
