package app.sterna.core.jmap.model

/**
 * The `envelope` of an EmailSubmission (RFC 8621 §7.5): the SMTP `MAIL FROM` and `RCPT TO` the
 * server is to use, instead of deriving them from the submitting identity and the message.
 *
 * Both fields are mandatory in the RFC — a submission that names an envelope names ALL of it, so
 * an amputated [rcptTo] does not fall back to the message's recipients, it drops them.
 */
data class SubmissionEnvelope(val mailFrom: String, val rcptTo: List<String>)
