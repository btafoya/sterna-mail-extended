package app.sterna.core.jmap

/**
 * Any failure talking to a JMAP server (transport, HTTP, parsing, or a JMAP-level
 * error). [httpCode] carries the HTTP status when the failure was an HTTP response
 * (null for transport/parse errors) — autodiscovery uses it to tell "reached the
 * server but the credentials were rejected" (401/403) from "no server here".
 */
class JmapException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
    /** The JMAP method-level error type (e.g. "anchorNotFound"), when the failure was one. */
    val errorType: String? = null,
    /**
     * The OAuth protocol's `error` field (RFC 6749 §5.2, e.g. "invalid_client"), when the failure
     * was an OAuth error response — never the response body, and never `error_description`. It is
     * a short, closed vocabulary chosen by the protocol; the body is the caller's tokens.
     */
    val oauthError: String? = null,
    /**
     * The server REFUSED this delivery, and replaying it can only duplicate what already went out.
     * Set on the one failure we can prove is final: an `EmailSubmission` the server created and
     * then reported as `delivered: "no"` for at least one recipient (#183) — a refusal issued
     * before the message was ever queued, so no bounce will ever follow it either.
     *
     * ⛔ It is NOT "this looked like a 5xx". Transport errors, HTTP failures and parse errors leave
     * it false: they say nothing about whether the message left, and the outbox's retries are what
     * gets a message out over a flaky link. Only a per-recipient refusal read back from the
     * submission itself sets it.
     *
     * The caller that acts on it is `OutboxLogic.stateAfterFailure`, which parks such a row as
     * FAILED at once instead of spending its five retries on a verdict that will not change.
     */
    val permanent: Boolean = false,
) : Exception(message, cause)
