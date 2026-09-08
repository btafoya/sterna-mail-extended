package app.sterna.core.jmap.model

/**
 * The `envelope` of an EmailSubmission (RFC 8621 §7.5): the SMTP `mail from` and `RCPT to` the
 * server is to use, instead of deriving them from the submitting identity and the message.
 */
data class SubmissionEnvelope(val mailFrom: String, val rcptTo: List<String>)
