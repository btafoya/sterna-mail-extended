package app.sterna.core.imap

/** What a session reports of its own traffic, one call per command line and one per tagged completion. */
interface ImapCommandSink {
    /** [command] is the line as it left, tag off, already cut down by [traceable]. */
    fun sent(tag: String, command: String)

    /** [status] is the completion's `OK`, `NO` or `BAD`, without the text after it. A tagged line
     *  carrying none of the three is not reported. */
    fun completed(tag: String, status: String)
}

/**
 * Where a build installs the sink of every session opened afterwards. Null in the published app,
 * which holds no reference to this object: the one assignment lives in the test app's source set,
 * and a source lint refuses any other.
 *
 * A sink is expected to write somewhere readable from outside the process (the test app's goes to
 * the system log, which an adb cable and a bug report both carry), so what it may see is decided
 * here and not left to it:
 * - `LOGIN` and `AUTHENTICATE` are reported as their verb alone. Their arguments are the account's
 *   password and its OAuth bearer token. The whole tail is dropped rather than masked field by
 *   field: a password may itself hold a quote or a space, and a mask that parses quoting is one
 *   bug away from printing half of it.
 * - Nothing sent after a server's `+` is reported: a literal is a message body or a draft, and a
 *   SASL response is a credential. `ImapSession` writes to its socket in two functions only:
 *   `send` for a command line, the one that calls a sink, and `sendContinuation` for the rest,
 *   which must never call one.
 * - A client built with a sink of its own reports to that one instead: the unit tests do.
 * - A completion is reported as tag and status only, the free text after it can quote the command.
 *
 * Every other command line is reported whole: folder names, UID sets and the text of a search are
 * readable in it, which is why no sink belongs in the published app.
 */
object ImapCommandTap {
    @Volatile
    var installed: ImapCommandSink? = null
}

private val CREDENTIAL_VERBS = setOf("LOGIN", "AUTHENTICATE")

/** [line] as a sink may see it: whole, or its verb alone when its arguments are credentials. */
internal fun traceable(line: String): String {
    val verb = line.substringBefore(' ')
    return if (verb.uppercase() in CREDENTIAL_VERBS) verb else line
}
