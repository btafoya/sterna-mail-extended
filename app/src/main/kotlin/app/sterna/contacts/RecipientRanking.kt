package app.sterna.contacts

import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.ReceivedContactRow
import app.sterna.core.data.db.SentContactRow
import java.text.Normalizer
import java.util.Locale

/** One address the recipient menu may offer, its four sources folded into one line. */
data class PoolContact(
    val email: String,
    val name: String?,
    val photoUri: String?,
    val sent: Int,
    val lastSent: Long,
    val received: Int,
    val lastReceived: Long,
    /** Written to, or in the server book, or in the phone's contacts; a sender she only received from is not. */
    val known: Boolean,
    /** The names the other sources give the address: a query finds it by any of them, [name] alone is shown. */
    val otherNames: List<String> = emptyList(),
) {
    internal val key: String = email.lowercase(Locale.ROOT)
    internal val matchName: String = normalizeForMatch(name.orEmpty())
    internal val matchNames: List<String> =
        (listOf(matchName) + otherNames.map { normalizeForMatch(it) }).filter { it.isNotEmpty() }.distinct()
    internal val matchEmail: String = normalizeForMatch(email)
}

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * What a query and a contact are compared as: canonical decomposition, combining marks dropped,
 * lowercased under [Locale.ROOT] so a Turkish phone does not turn `I` into a dotless `ı`.
 * `ł`, `ø`, `đ` and `ß` have no decomposition and stay as they are.
 */
fun normalizeForMatch(text: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

private val AUTOMATED_PREFIXES = listOf("noreply", "no-reply", "no_reply", "donotreply", "do-not-reply", "do_not_reply")
private val AUTOMATED_NAMES = setOf("mailer-daemon", "postmaster", "bounce", "bounces")

/** Whether [address] is a machine's, read on its local part without its `+tag`. A closed list. */
fun isAutomatedAddress(address: String): Boolean {
    val local = address.substringBeforeLast('@').substringBefore('+').lowercase(Locale.ROOT)
    return local in AUTOMATED_NAMES || AUTOMATED_PREFIXES.any { local.startsWith(it) }
}

/**
 * Every source folded by lowercased address. The name is the phone's, else the server book's, else
 * the one she wrote, else the newest received; the photo only ever comes from the phone.
 * Her own addresses, of every account and matched exactly, never enter: `me+tag@` does, since
 * writing to a sub-address is how one tests or files it.
 */
fun buildRecipientPool(
    sent: List<SentContactRow>,
    received: List<ReceivedContactRow>,
    book: List<ContactRow>,
    phone: List<ContactSuggestion>,
    selves: Set<String>,
): List<PoolContact> {
    class Acc(val email: String) {
        var phoneName: String? = null
        var bookName: String? = null
        var sentName: String? = null
        var receivedName: String? = null
        var photo: String? = null
        var sends = 0
        var lastSend = 0L
        var receptions = 0
        var lastReception = 0L
        var listed = false
    }
    val byKey = LinkedHashMap<String, Acc>()
    fun at(email: String): Acc? {
        val address = email.trim()
        if (!address.contains('@')) return null
        return byKey.getOrPut(address.lowercase(Locale.ROOT)) { Acc(address) }
    }
    fun String?.orNullIfBlank() = this?.trim()?.ifBlank { null }
    for (p in phone) at(p.email)?.run {
        listed = true
        phoneName = phoneName ?: p.name.orNullIfBlank()
        photo = photo ?: p.photoUri.orNullIfBlank()
    }
    for (b in book) at(b.email)?.run {
        listed = true
        bookName = bookName ?: b.name.orNullIfBlank()
    }
    for (s in sent) at(s.email)?.run {
        sends += s.sendCount
        lastSend = maxOf(lastSend, s.lastSeen)
        sentName = sentName ?: s.name.orNullIfBlank()
    }
    for (r in received) at(r.email)?.run {
        if (r.latest >= lastReception) receivedName = r.name.orNullIfBlank() ?: receivedName
        receptions += r.received
        lastReception = maxOf(lastReception, r.latest)
    }
    return byKey.filterKeys { it !in selves }.values
        .map {
            val names = listOfNotNull(it.phoneName, it.bookName, it.sentName, it.receivedName)
            PoolContact(
                email = it.email,
                name = names.firstOrNull(),
                photoUri = it.photo,
                sent = it.sends,
                lastSent = it.lastSend,
                received = it.receptions,
                lastReceived = it.lastReception,
                known = it.sends > 0 || it.listed,
                otherNames = names.drop(1).distinct(),
            )
        }
        // Her own gesture outranks the heuristic: an automated-looking address she has written to stays.
        .filterNot { it.sent == 0 && isAutomatedAddress(it.email) }
}

/** 2 when [query] starts a word of one of the names or of the address, 1 when it only sits inside one, 0 otherwise. */
internal fun matchStrength(query: String, contact: PoolContact): Int {
    val texts = contact.matchNames + contact.matchEmail
    return when {
        texts.any { startsAWord(it, query) } -> 2
        texts.any { query in it } -> 1
        else -> 0
    }
}

/** A word starts after anything but a letter or a digit: a name's spaces, a local part's `.` `_` `-` `+`, `@`, a domain's dots. */
private fun startsAWord(text: String, query: String): Boolean {
    var at = text.indexOf(query)
    while (at >= 0) {
        if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
        at = text.indexOf(query, at + 1)
    }
    return false
}

private const val SEND_WEIGHT = 4.0
private const val DAY_MILLIS = 86_400_000.0

private fun freshness(at: Long, now: Long): Double = 1.0 / (1.0 + (now - at).coerceAtLeast(0) / DAY_MILLIS / 30.0)

/**
 * How much she deals with [contact]: `4 × sends × a(last send) + receptions × a(last reception)`,
 * with `a(t) = 1 / (1 + age in days / 30)`.
 *
 * A send is her own choice of recipient, a reception is anyone's choice of her, hence the weight: at
 * equal counts and dates a send always outweighs a reception, and one send still beats three
 * receptions. The age factor halves at a month and never reaches zero, so a correspondent of long
 * ago fades behind a current one without ever falling out. Why not a window: a hard cut makes the
 * order jump on the day someone crosses it.
 */
internal fun usage(contact: PoolContact, now: Long): Double =
    SEND_WEIGHT * contact.sent * freshness(contact.lastSent, now) +
        contact.received * freshness(contact.lastReceived, now)

private class Scored(val contact: PoolContact, val match: Int, val usage: Double) {
    val sortName: String = contact.matchName.ifEmpty { contact.matchEmail }
}

private val RANKING: Comparator<Scored> = compareByDescending<Scored> { it.match }
    .thenByDescending { it.contact.known }
    .thenByDescending { it.usage }
    .thenBy { it.sortName }
    .thenBy { it.contact.key }

private fun PoolContact.toSuggestion() = ContactSuggestion(email = email, name = name, photoUri = photoUri)

/**
 * The menu for [query]: a word start before a mere substring, then anyone known before anyone only
 * received from, then [usage], then the name. Cut to [limit] only once everything is ranked.
 * [onScreen] holds the lowercased addresses already in To, Cc and Bcc.
 */
fun rankRecipients(
    pool: List<PoolContact>,
    query: String,
    onScreen: Set<String>,
    now: Long,
    limit: Int = 6,
): List<ContactSuggestion> {
    val q = normalizeForMatch(query.trim())
    if (q.isEmpty()) return emptyList()
    return pool.asSequence()
        .filter { it.key !in onScreen }
        .map { Scored(it, matchStrength(q, it), usage(it, now)) }
        .filter { it.match > 0 }
        .sortedWith(RANKING)
        .take(limit)
        .map { it.contact.toSuggestion() }
        .toList()
}

/** What a recipient field offers when it takes the focus empty: only people she has written to, by [usage]. */
fun usualRecipients(
    pool: List<PoolContact>,
    onScreen: Set<String>,
    now: Long,
    limit: Int = 6,
): List<ContactSuggestion> =
    pool.asSequence()
        .filter { it.sent > 0 && it.key !in onScreen }
        .map { Scored(it, 0, usage(it, now)) }
        .sortedWith(RANKING)
        .take(limit)
        .map { it.contact.toSuggestion() }
        .toList()
