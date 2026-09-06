package app.sterna.core.data.account

import kotlinx.serialization.Serializable

/**
 * Which mail protocol an account speaks.
 *
 * These names ARE the stored format: they go by name into every account record
 * ([StoredAccount.protocol]), the account list is one blob, and a name this build cannot resolve
 * stops the WHOLE list from decoding — no accounts on screen, and no write until a build that knows
 * the name is installed ([AccountBlobGate], mechanism in full under [SyncWindow.ALL]). Never rename,
 * never remove; retire from the UI instead. Pinned by `StoredAccountReferenceRecordTest`.
 */
@Serializable
enum class MailProtocol { JMAP, IMAP }

/**
 * Transport security for an IMAP/SMTP endpoint (persisted; maps to core:imap's MailSecurity).
 *
 * Same rule, twice over: these names are stored under BOTH [StoredAccount.imapSecurity] and
 * [StoredAccount.smtpSecurity], so renaming or removing one empties the account screen of every
 * install that ever configured an IMAP account. Pinned by `StoredAccountReferenceRecordTest`.
 */
@Serializable
enum class ConnectionSecurity { TLS, STARTTLS, NONE }
