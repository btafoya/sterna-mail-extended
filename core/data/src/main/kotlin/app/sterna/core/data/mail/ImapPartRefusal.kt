package app.sterna.core.data.mail

import app.sterna.core.imap.ImapException

/**
 * What `ImapMailService.fetchPartAhead` raises when the server refused the FETCH of one part in a
 * way that would come back the same (#157). The download ahead gives such a part up; any other
 * refusal, a LOGIN, an AUTHENTICATE or a SELECT turned down, is the server's state and is tried
 * again at the next pass. Not an [ImapException], which the download ahead reads as passing.
 */
class ImapPartRefused(val section: String, cause: ImapException) : Exception("Part $section refused: ${cause.message}", cause)

/** Response codes that say the refusal is the server's state, not the part's (RFC 5530, RFC 3501). */
private val PASSING_REFUSAL_CODES = setOf("UNAVAILABLE", "LIMIT", "ALERT", "INUSE", "SERVERBUG", "CONTACTADMIN", "OVERQUOTA")

/** Whether a refusal of a part's own FETCH is final: a tagged NO or BAD with no code that names a
 *  passing state of the server. */
internal fun partFetchRefusedForGood(refused: ImapException): Boolean =
    refused.taggedStatus != null && refused.responseCode?.uppercase() !in PASSING_REFUSAL_CODES
