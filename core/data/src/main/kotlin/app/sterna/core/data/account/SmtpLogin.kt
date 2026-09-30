package app.sterna.core.data.account

/** The login the SMTP `AUTH` is sent with. */
data class SmtpLogin(val username: String, val password: String) {
    /** Masked: a send failure carrying this object ends up on the outbox row, on screen. */
    override fun toString(): String = "SmtpLogin(username=$username, password=***)"
}

/**
 * The SMTP login of an account, from [StoredAccount.smtpUsername] and its secret. That secret has an
 * encrypted slot of its own in `AccountStore` and is never a field of [StoredAccount], so no backup
 * carries it.
 *
 * A blank [smtpUsername] means the IMAP login, whole. A set one sends with its own secret, or with
 * the IMAP password when it has none: same server, another identifier. OAuth ignores both, its
 * XOAUTH2 token names one mailbox for IMAP and SMTP alike.
 *
 * [smtpPassword] is blank when no SMTP secret is stored or when it could not be read: both fall back
 * to the IMAP password, never to a missing credential: the send workers read that as a removed
 * account, and the outbox row they hold is the message's only copy.
 */
fun resolveSmtpLogin(
    authType: AuthType,
    imapUsername: String,
    imapPassword: String,
    smtpUsername: String,
    smtpPassword: String,
): SmtpLogin =
    if (authType == AuthType.OAUTH || smtpUsername.isBlank()) {
        SmtpLogin(imapUsername, imapPassword)
    } else {
        SmtpLogin(smtpUsername.trim(), smtpPassword.ifEmpty { imapPassword })
    }

/**
 * [resolveSmtpLogin] on these credentials; an `oauth` block counts as OAuth whatever [AccountCredentials.authType]
 * says. Only the SMTP `AUTH` reads this: [AccountCredentials.username] stays the account's address everywhere
 * else (sender fallback, `Message-Id`, Autocrypt, ManageSieve).
 */
fun AccountCredentials.smtpLogin(): SmtpLogin = resolveSmtpLogin(
    authType = if (oauth != null) AuthType.OAUTH else authType,
    imapUsername = username,
    imapPassword = password,
    smtpUsername = smtpUsername,
    smtpPassword = smtpPassword,
)

/** What a write does to the stored SMTP secret. */
sealed interface SmtpSecretEdit {
    data object Keep : SmtpSecretEdit
    data object Clear : SmtpSecretEdit
    data class Write(val password: String) : SmtpSecretEdit {
        override fun toString(): String = "Write(password=***)"
    }
}

/**
 * The SMTP secret exists only beside a SMTP username: clearing the username clears the secret. A
 * typed password is written. A blank one keeps the stored secret when [blankKeeps] (the account
 * editor, where blank means "unchanged"), and clears it otherwise (an add states the whole login,
 * and a blank there means "the IMAP password").
 */
fun smtpSecretEdit(smtpUsername: String, typedPassword: String, blankKeeps: Boolean): SmtpSecretEdit = when {
    smtpUsername.isBlank() -> SmtpSecretEdit.Clear
    typedPassword.isNotEmpty() -> SmtpSecretEdit.Write(typedPassword)
    blankKeeps -> SmtpSecretEdit.Keep
    else -> SmtpSecretEdit.Clear
}
