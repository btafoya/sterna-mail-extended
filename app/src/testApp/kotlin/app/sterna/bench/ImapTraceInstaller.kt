package app.sterna.bench

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import app.sterna.core.imap.ImapCommandSink
import app.sterna.core.imap.ImapCommandTap

/**
 * Puts the IMAP command trace of the test app on logcat, so a bench can tell what a refresh
 * asked the server:
 *
 *   adb logcat -s ImapTrace
 *   > a3 SELECT "INBOX" (CONDSTORE)
 *   < a3 OK
 *
 * A provider that provides nothing: Android creates every declared provider before
 * `Application.onCreate`, which makes this the one entry point that runs ahead of the first
 * connection without `src/main` holding a reference to it. The published app has neither this
 * class nor its manifest entry, and so no sink: `ImapCommandTap.installed` stays null there.
 *
 * Logcat leaves the process: an adb cable reads it, and so does every bug report taken on the
 * device. Two things are cut before this sink is called: `LOGIN` and `AUTHENTICATE` arrive as
 * their verb alone, and nothing sent after a server's `+` (an `APPEND` body, a SASL response)
 * arrives at all. The cut is made in `core/imap`, see `ImapCommandTap`, so that a sink written
 * here cannot undo it; this one prints what it is given and must not grow a second source of
 * text, an `ImapException` message included.
 *
 * Nothing else is cut. Every other command line is printed whole, so folder names, UID sets and
 * the text of a search can be read in the log. That is acceptable on a bench account and nowhere
 * else, and it is the reason this file must stay under `src/testApp/kotlin`.
 *
 * Two limits of what is printed: sessions number their tags independently, so the push
 * connection and a refresh both have an `a3`, told apart by logcat's thread column only; and
 * logcat cuts an entry at about 4000 bytes, which a long UID set can reach.
 */
class ImapTraceInstaller : ContentProvider() {

    override fun onCreate(): Boolean {
        ImapCommandTap.installed = LogcatSink
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private object LogcatSink : ImapCommandSink {
    override fun sent(tag: String, command: String) {
        Log.i(TAG, "> $tag $command")
    }

    override fun completed(tag: String, status: String) {
        Log.i(TAG, "< $tag $status")
    }
}

private const val TAG = "ImapTrace"
