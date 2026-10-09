package app.sterna.push

import app.sterna.core.jmap.JmapException

/**
 * How a failed notification action is named in the device log. `JmapException` is spelled out:
 * R8 renames the app's own classes, and this is the one a bench reads. Its `errorType` is the
 * `type` field of the server's error, a string the server chose, so it is written only when it
 * has the shape of a protocol error type. Never the exception's message, which can quote an address.
 */
fun notificationActionFailureLabel(e: Throwable): String = when (e) {
    is JmapException -> "JmapException" + e.errorType?.takeIf { PROTOCOL_ERROR_TYPE.matches(it) }?.let { "($it)" }.orEmpty()
    else -> e.javaClass.simpleName
}

private val PROTOCOL_ERROR_TYPE = Regex("[A-Za-z]{1,40}")
