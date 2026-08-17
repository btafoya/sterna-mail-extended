package app.sterna.core.data.mail

/**
 * A message could not be brought back from the server: offline, destroyed server-side, or its
 * folder renumbered. Raised where the cache has no copy to fall back on (#159) — the reader turns
 * it into a translated sentence, so the wording carried here stays technical and English: it is
 * what a bug report needs to be able to quote.
 */
class MessageUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
