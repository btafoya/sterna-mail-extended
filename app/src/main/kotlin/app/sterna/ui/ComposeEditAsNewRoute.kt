package app.sterna.ui

/**
 * The compose route Edit as new opens (#254): [emailId] under an argument of its own. Not `draftId`,
 * which arms the message for destruction on the next send or save; not `replyTo`, which threads the
 * new message onto it; and not a value of `mode`, which opens a quoted reply for any value it does
 * not know.
 *
 * [encode] is `Uri.encode` in the app, a parameter because the JVM's android.jar stub cannot run it.
 */
fun composeEditAsNewRoute(emailId: String, accountId: String, encode: (String) -> String): String =
    "compose?editAsNew=${encode(emailId)}&accountId=${encode(accountId)}"
