package app.sterna.ui.inbox

import app.sterna.core.jmap.JmapException

/** Bound so a cause chain that loops back on itself cannot hang the banner. */
private const val CAUSE_DEPTH = 16

/**
 * True when the server answered a token refresh with an OAuth `error` (#271): the stored client id
 * or refresh token is no longer accepted, and only signing in again gets past it. Read from
 * `oauthError`, never from `httpCode`: a JMAP 401 is a different failure, and a refused refresh
 * may come back as a 400. Walked down the causes so a layer that wraps the failure does not hide it.
 */
internal fun isOAuthRefusal(cause: Throwable): Boolean =
    generateSequence(cause) { it.cause }
        .take(CAUSE_DEPTH)
        .any { it is JmapException && it.oauthError != null }

/** The sync banner's text for [cause]: [oauthRefused] for an OAuth refusal, else the cause's own message. */
internal fun syncErrorText(cause: Throwable, oauthRefused: String): String =
    if (isOAuthRefusal(cause)) oauthRefused else cause.message ?: cause.javaClass.simpleName
