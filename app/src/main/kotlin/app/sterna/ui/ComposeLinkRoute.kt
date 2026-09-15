package app.sterna.ui

import app.sterna.MailtoDraft

/**
 * The compose route a mailto: link or a share opens. The body is not in it, only [bodyToken], the
 * key the composer takes the body back from `LinkBodyHandOff` with: a ~100 kB body in a route makes
 * `navigate` match no destination and throw, and a route is kept in the activity's saved state.
 * No token, no `bodyToken` argument.
 *
 * [encode] is `Uri.encode` in the app, a parameter because the JVM's android.jar stub cannot run it.
 */
fun composeLinkRoute(link: MailtoDraft, bodyToken: String?, encode: (String) -> String): String =
    "compose?to=${encode(link.to)}&cc=${encode(link.cc)}&bcc=${encode(link.bcc)}" +
        "&subject=${encode(link.subject)}" +
        bodyToken?.let { "&bodyToken=$it" }.orEmpty()
