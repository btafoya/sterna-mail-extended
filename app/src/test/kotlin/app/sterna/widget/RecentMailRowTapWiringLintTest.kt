package app.sterna.widget

import app.sterna.widget.DeclarationSource.codeLines
import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import app.sterna.widget.DeclarationSource.kotlinLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the tap pane's half of what no JVM test can run. What a row
 * ASKS FOR is executed next door ([RecentMailWidgetTapTest]); `PendingIntent`, `Intent`,
 * `RemoteViews` and `MainActivity` cannot be built in this suite at all (there is no Robolectric
 * here), so how that request is handed to the launcher is pinned as text.
 *
 * ⛔ WHOLE LINES, never a `contains`: a substring rule is satisfied by every mutation that
 * LENGTHENS the line, which is how the first version of another lint in this repo was walked past
 * twice. The price is that reformatting a pinned line reddens this file — loud, and one line to fix.
 */
class RecentMailRowTapWiringLintTest {

    /**
     * ⛔ THE TEMPLATE'S ACTION IS LOAD-BEARING, and it is the same trap the counter's `tapIntent`
     * carries in its own KDoc.
     *
     * A `PendingIntent`'s identity is `(requestCode, Intent as compared by filterEquals, flags)`,
     * and `filterEquals` IGNORES EXTRAS. Every new-mail notification is built as
     * `Intent(context, MainActivity::class.java)` with no action and no data, and they are told
     * apart by request code alone — `Notifications.childId(accountId, emailId)`, a
     * `"$accountId $emailId".hashCode()` spread over the whole `Int` range, so no interval can be
     * reserved against it. Without an action of its own this template is filterEquals-identical to
     * every one of them, and the first `childId` that lands on its request code has its extras
     * REWRITTEN by `FLAG_UPDATE_CURRENT`: the user taps the banner announcing a message and the app
     * opens a different one.
     *
     * ⚠ AND IT IS MUTABLE. A collection widget cannot hand out one `PendingIntent` per row; the
     * platform's single route is a template the launcher FILLS IN per row, and filling in a
     * `PendingIntent` requires it to be mutable. It is the app's second one, alongside the
     * quick-reply action in `Notifications`, which `RemoteInput` demands. Two bounds are pinned by
     * this very body: the intent names an explicit component (`MainActivity::class.java`), and it
     * carries an action of its own. ⛔ NOT a third bound, though the fill-in test next door reads
     * like one: `Intent.fillIn` copies whatever the base intent left unset, so a holder of the
     * template can add data, categories and a package whatever we put in ours. What makes that
     * cost nothing is that `MainActivity` is exported with a LAUNCHER filter and can already be
     * started by anything with anything.
     */
    @Test fun `the row template names an explicit component, its own action, and is deliberately mutable`() {
        assertEquals(
            "the latest-messages widget's template must target MainActivity explicitly, set its " +
                "OWN action, and be FLAG_MUTABLE (the platform's only way to fill in a per-row " +
                "intent). Drop the action and FLAG_UPDATE_CURRENT rewrites a notification's " +
                "target the day a childId hash lands on this request code — the reader taps a " +
                "banner and opens someone else's message. Drop FLAG_MUTABLE and every row of the " +
                "cell opens the same nothing.",
            listOf(
                "private fun rowTapTemplate(context: Context): PendingIntent {",
                "val intent = Intent(context, MainActivity::class.java)",
                ".setAction(ACTION_ROW_TAP)",
                ".addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)",
                "return PendingIntent.getActivity(",
                "context,",
                "ROW_TAP_REQUEST_CODE,",
                "intent,",
                "PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,",
                ")",
            ),
            functionBody(DRAW, "private fun rowTapTemplate("),
        )
        assertEquals(
            "the action must be a real, app-owned string, and its own: emptied, nulled or set to " +
                "the counter's it stops telling this template apart from the intents around it.",
            listOf("private const val ACTION_ROW_TAP = \"app.sterna.widget.OPEN_RECENT\""),
            kotlinLines(DRAW).filter { it.startsWith("private const val ACTION_ROW_TAP") },
        )
    }

    /**
     * The template is set on the LIST, inside the per-cell loop that builds the `RemoteViews`, and
     * before that `RemoteViews` is sent. A template attached anywhere else, or not at all, is a
     * widget whose rows are drawn and swallow every touch — which is exactly what this branch
     * shipped in its previous pane.
     */
    @Test fun `the drawn cell hands the list its template`() {
        assertEquals(
            "RecentMailWidgetDraw.draw must attach the row template to the ListView it has just " +
                "given an adapter. Without it a fill-in intent is a message in a bottle: the " +
                "launcher has nothing to fill in, and no row opens anything.",
            listOf(TEMPLATE_ATTACH),
            functionBody(DRAW, "fun draw(app: Application, ids: IntArray, drawing: RecentWidgetDrawing)")
                .filter { it.startsWith("views.setPendingIntentTemplate(") },
        )
    }

    /**
     * ⛔ EXTRAS AND NOTHING ELSE, which is what makes the mutable template above acceptable.
     *
     * A fill-in `Intent` is merged into the template by the launcher. An action, a data URI or a
     * component set here would be a third party writing into what this app is about to start; the
     * three `putExtra`s below are the identity of a message and nothing more, and they are the very
     * three a tapped new-mail notification already carries — so opening arrives on the route that
     * has been carrying notification taps for releases, with every guard it already holds.
     *
     * ⛔ The two nullable ones are put ONLY when present. `MainActivity.parseEmailOpen` reads them
     * as `?.ifBlank { null }` and `SternaApp` acts on the folder half only when both are there; a
     * blank travelling as a blank would ask for the folder "" of the account "".
     */
    @Test fun `a row's fill-in intent carries extras and nothing else`() {
        assertEquals(
            "the per-row fill-in must be a bare Intent carrying the three OPEN_* extras and " +
                "nothing else — no action, no data, no component. Anything else in here is " +
                "written into the activity the template is about to start.",
            listOf(
                "fun rowFillIn(tap: RecentRowTap): Intent {",
                "val intent = Intent()",
                "intent.putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, tap.emailId)",
                "tap.accountId?.let { intent.putExtra(MainActivity.EXTRA_OPEN_ACCOUNT_ID, it) }",
                "tap.mailboxId?.let { intent.putExtra(MainActivity.EXTRA_OPEN_MAILBOX_ID, it) }",
                "return intent",
            ),
            functionBody(DRAW, "fun rowFillIn("),
        )
    }

    /**
     * Every served row is given its own fill-in, on the row's ROOT view — not on one of its
     * TextViews, or a cell would only react on the sender's name and read as broken.
     */
    @Test fun `every served row is given its own fill-in, on its root view`() {
        assertEquals(
            "RecentMailWidgetFactory.getViewAt must attach the row's fill-in intent to the row " +
                "root. The rows are drawn either way; without this line the whole list is inert.",
            listOf(FILL_IN_ATTACH),
            functionBody(SERVICE, "override fun getViewAt(position: Int): RemoteViews")
                .filter { it.startsWith("views.setOnClickFillInIntent(") },
        )
        // ⛔ AND THE WHOLE FUNCTION, not merely that one line. The filter above proves a line is
        // PRESENT and says nothing about the others — which was harmless while this factory held
        // rows that carried no identifier at all, and stopped being harmless the moment a row
        // became a pair of what it draws and what it opens. Both halves are now in scope here,
        // three lines above a `setTextViewText`, and a review demonstrated two one-line changes
        // that compiled and left this whole suite green: drawing `item.tap.mailboxId` in place of
        // the date, which puts a folder id on a LOCKED home screen, and reading `position + 1`
        // into `row`, which makes every line name one message and open another with no way to
        // find out but to tap. The type still refuses to carry an id; this file is what now
        // refuses to draw one.
        assertEquals(
            "RecentMailWidgetFactory.getViewAt is pinned whole. Only `item.drawn` may reach a " +
                "setTextViewText, and `item.tap` may reach nothing but the fill-in — under the " +
                "app lock a row names nothing at all, and an identifier drawn here would be " +
                "exactly the thing SECURITY.md says the lock prevents. The drawn half and the " +
                "opened half must also come from the SAME item: a row that names one message and " +
                "opens another is undiscoverable except by tapping it. The account dot is pinned " +
                "here too: it is retinted with setInt(…, \"setColorFilter\", …) — setBackgroundColor " +
                "replaces the oval with a square block — and the out-of-range branch must hide it, " +
                "or a recycled view keeps the colour of a row that no longer exists. ⛔ AND SO IS " +
                "THE BADGE, on the same terms and for a stricter reason: it is drawn ONLY from " +
                "monogramFor(row), which returns null when row.monogram is null — under the app " +
                "lock and at the NONE position of the notification-content setting — so the pixel " +
                "that would carry an initial is never produced in the two states SECURITY.md says " +
                "a row names nobody. ⛔ And the out-of-range branch must CLEAR the bitmap " +
                "(setImageViewBitmap(…, null)) as well as hide the view: a recycled row otherwise " +
                "keeps the face of a message that no longer exists under a blank line, which is " +
                "the very thing the three empty setTextViewText calls are there for. ⛔ Never " +
                "GONE, either: the badge holds the column, and a GONE badge shifts the sender of " +
                "one row against the next. ⛔ AND THE " +
                "WEIGHT IS NOW THE ROW'S ONLY UNREAD MARK: the dot is gone, so weighted(…, " +
                "row.unread) on ALL THREE fields — sender, subject and date — is the whole of it. " +
                "DESIGN.md:58 is the rule and it names the three: \"Unread is shown by weight, " +
                "never by a status dot: sender, time and subject go bold\", which is what the " +
                "message list does (EmailListItem). Lose the call on any one field and the cell " +
                "and the list say two different things about the same message — a bold sender and " +
                "a bold time over a plain subject — silently, with every row still drawn, still " +
                "tappable and still correct in every other respect, which is a defect nobody " +
                "reports because nothing looks broken.",
            listOf(
                "override fun getViewAt(position: Int): RemoteViews {",
                "val views = RemoteViews(context.packageName, R.layout.widget_recent_row)",
                "val item = rows.getOrNull(position)",
                "if (item == null) {",
                "views.setTextViewText(R.id.widget_recent_row_primary, \"\")",
                "views.setTextViewText(R.id.widget_recent_row_secondary, \"\")",
                "views.setTextViewText(R.id.widget_recent_row_date, \"\")",
                "views.setImageViewBitmap(R.id.widget_recent_row_monogram, null)",
                "views.setViewVisibility(R.id.widget_recent_row_monogram, View.INVISIBLE)",
                "views.setViewVisibility(R.id.widget_recent_row_account, View.INVISIBLE)",
                "return views",
                "}",
                "val row = item.drawn",
                "val colours = palette",
                "if (colours != null) {",
                "views.setTextColor(R.id.widget_recent_row_primary, colours.rowPrimary)",
                "views.setTextColor(R.id.widget_recent_row_secondary, colours.label)",
                "views.setTextColor(R.id.widget_recent_row_date, colours.label)",
                "}",
                "views.setTextViewText(R.id.widget_recent_row_primary, weighted(row.primary, row.unread))",
                "views.setTextViewText(R.id.widget_recent_row_secondary, weighted(row.secondary.orEmpty(), row.unread))",
                "views.setViewVisibility(",
                "R.id.widget_recent_row_secondary,",
                "if (row.secondary == null) View.GONE else View.VISIBLE,",
                ")",
                "views.setTextViewText(R.id.widget_recent_row_date, weighted(row.date, row.unread))",
                "val badge = monogramFor(row)",
                "views.setImageViewBitmap(R.id.widget_recent_row_monogram, badge)",
                "views.setViewVisibility(",
                "R.id.widget_recent_row_monogram,",
                "if (badge == null) View.INVISIBLE else View.VISIBLE,",
                ")",
                "views.setInt(R.id.widget_recent_row_account, \"setColorFilter\", row.accountColor ?: 0)",
                "views.setViewVisibility(",
                "R.id.widget_recent_row_account,",
                "if (row.accountColor == null) View.INVISIBLE else View.VISIBLE,",
                ")",
                FILL_IN_ATTACH,
                "return views",
            ),
            functionBody(SERVICE, "override fun getViewAt(position: Int): RemoteViews"),
        )
        // ⛔ AND THE ONE LINE getViewAt DELEGATES. Extracting the badge keeps the function above
        // short, and it would also move the lock's promise out of everything this file pins: the
        // whole of "a locked row wears no face" is `row.monogram ?: return null`, three lines down
        // where nothing looks. Both guards are pinned here, in order.
        assertEquals(
            "RecentMailWidgetFactory.monogramFor is the badge's only source, and its two early " +
                "returns are the rule. `row.monogram ?: return null` IS the app lock and the NONE " +
                "setting — RecentMailWidgetContent leaves the field null in exactly those two " +
                "states — so dropping it draws an initial on a locked home screen while every " +
                "other rule here stays green. `monogramRamps ?: return null` is the other: a read " +
                "that did not answer must draw NO badge rather than a colour this file invented, " +
                "since a bitmap has no res/values-night behind it.",
            listOf(
                "private fun monogramFor(row: RecentWidgetRow): Bitmap? {",
                "val monogram = row.monogram ?: return null",
                "val ramps = monogramRamps ?: return null",
                "return WidgetMonogram.badge(context, monogram, ramps)",
            ),
            functionBody(SERVICE, "private fun monogramFor("),
        )
        assertEquals(
            "the row layout's ROOT must carry that id. On a child, only that child would open " +
                "anything — the rest of the row would swallow the tap.",
            listOf(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"",
                "android:id=\"@+id/widget_recent_row_root\"",
            ),
            codeLines(ROW_LAYOUT).take(3),
        )
    }

    /**
     * ⛔ THE TWO WIDGETS MUST NOT SHARE A REQUEST CODE, and this rule reads the two NUMBERS rather
     * than any prose about them.
     *
     * They already differ by action, which is the real defence; this is the belt. Aligned — the
     * likeliest way being a reader "tidying up" the second widget to reuse the first's constant —
     * the two templates would differ only by their action, and the day either action is touched the
     * two `PendingIntent`s become one: the counter's cell and the message rows would then rewrite
     * each other's extras through `FLAG_UPDATE_CURRENT`, and a tap on the counter would open a
     * message.
     */
    @Test fun `the two widgets' request codes, and their actions, are different things`() {
        assertNotEquals(
            "the latest-messages template must not reuse the counter's request code. Both are " +
                "constants in this repository, so this is one line to keep apart and nothing on " +
                "either home-screen cell would show that they had been merged.",
            requestCode(UNREAD_DRAW, "private const val TAP_REQUEST_CODE ="),
            requestCode(DRAW, "private const val ROW_TAP_REQUEST_CODE ="),
        )
        assertNotEquals(
            "and their actions must differ too — the action is what tells either template apart " +
                "from the notification intents, and one shared string makes the pair collide on " +
                "the request code alone.",
            action(UNREAD_DRAW, "private const val ACTION_WIDGET_TAP ="),
            action(DRAW, "private const val ACTION_ROW_TAP ="),
        )
    }

    /**
     * The integer a `private const val … = <n>` declares, read from the source. Fails loudly when
     * the declaration is gone: a rename must redden this rule rather than satisfy it against
     * nothing.
     */
    private fun requestCode(file: File, declaration: String): Int {
        val line = kotlinLines(file).singleOrNull { it.startsWith(declaration) }
            ?: error("${file.name} no longer declares exactly one `$declaration` — was it renamed?")
        return line.substringAfter("=").trim().toInt()
    }

    /** The same for a string constant, quotes included, so two spellings can be compared. */
    private fun action(file: File, declaration: String): String {
        val line = kotlinLines(file).singleOrNull { it.startsWith(declaration) }
            ?: error("${file.name} no longer declares exactly one `$declaration` — was it renamed?")
        return line.substringAfter("=").trim()
    }

    private companion object {
        val DRAW = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetDraw.kt")
        val SERVICE = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetService.kt")
        val UNREAD_DRAW = file("app/src/main/kotlin/app/sterna/widget/UnreadWidgetDraw.kt")
        val ROW_LAYOUT = file("app/src/main/res/layout/widget_recent_row.xml")

        const val TEMPLATE_ATTACH =
            "views.setPendingIntentTemplate(R.id.widget_recent_list, rowTapTemplate(app))"

        const val FILL_IN_ATTACH =
            "views.setOnClickFillInIntent(R.id.widget_recent_row_root, " +
                "RecentMailWidgetDraw.rowFillIn(item.tap))"
    }
}
