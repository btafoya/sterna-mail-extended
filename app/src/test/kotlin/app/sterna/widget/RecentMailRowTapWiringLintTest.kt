package app.sterna.widget

import app.sterna.widget.DeclarationSource.codeLines
import app.sterna.widget.DeclarationSource.file
import app.sterna.widget.DeclarationSource.functionBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test, the tap pane's half of what no JVM test can run. What a row
 */
class RecentMailRowTapWiringLintTest {

    /**
     * The template's action is load-bearing, and it is the same trap the counter's `tapIntent`
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
                "private fun rowTapTemplate(context: Context, kind: RecentWidgetKind, extras: Map<String, Boolean>): PendingIntent {",
                "val intent = Intent(context, MainActivity::class.java)",
                ".setAction(kind.action)",
                ".addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)",
                "extras.forEach { (name, value) -> intent.putExtra(name, value) }",
                "return PendingIntent.getActivity(",
                "context,",
                "kind.requestCode,",
                "intent,",
                "PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,",
                ")",
            ),
            functionBody(DRAW, "private fun rowTapTemplate("),
        )
        assertEquals(
            "each kind's action must be a real, app-owned string, and its own: emptied, nulled or " +
                "set to another widget's it stops telling this template apart from the intents " +
                "around it.",
            mapOf(
                RecentWidgetKind.Inboxes to ("app.sterna.widget.OPEN_RECENT" to 1),
                RecentWidgetKind.Unread to ("app.sterna.widget.OPEN_UNREAD_LIST" to 3),
            ),
            RecentWidgetKind.entries.associateWith { it.action to it.requestCode },
        )
    }

    @Test fun `the unread cell opens its view from its frame and its notice, through the rows' own template`() {
        assertEquals(
            "RecentMailWidgetDraw.draw must build ONE template per draw, hand it to the list, and " +
                "post the very same PendingIntent on the root and the notice when the kind opens a " +
                "view — and only then. A second PendingIntent for the frame is a second identity to " +
                "keep apart from the notifications; a frame target on the inboxes' list opens " +
                "something no row of it says.",
            listOf(
                "val template = rowTapTemplate(app, kind, drawing.tapExtras)",
                "views.setPendingIntentTemplate(R.id.widget_recent_list, template)",
                "if (kind.opensAs != null) {",
                "views.setOnClickPendingIntent(R.id.widget_recent_root, template)",
                "views.setOnClickPendingIntent(R.id.widget_recent_notice, template)",
            ),
            functionBody(DRAW, DRAW_SIGNATURE).filter {
                it.startsWith("val template =") || it.startsWith("views.setPendingIntentTemplate(") ||
                    it.startsWith("if (kind.opensAs") || it.startsWith("views.setOnClickPendingIntent(")
            },
        )
        assertEquals(
            "only the unread list has a cell target.",
            mapOf(RecentWidgetKind.Inboxes to null, RecentWidgetKind.Unread to UnreadWidgetKind.EveryFolder),
            RecentWidgetKind.entries.associateWith { it.opensAs },
        )
    }

    /**
     * The template is set on the LIST, inside the per-cell loop that builds the `RemoteViews`, and
     * before that `RemoteViews` is sent. A template attached anywhere else, or not at all, is a
     * widget whose rows are drawn and swallow every touch, which is exactly what this branch
     * shipped in its previous pane.
     */
    @Test fun `the drawn cell hands the list its template`() {
        assertEquals(
            "RecentMailWidgetDraw.draw must attach the row template to the ListView it has just " +
                "given an adapter. Without it a fill-in intent is a message in a bottle: the " +
                "launcher has nothing to fill in, and no row opens anything.",
            listOf(TEMPLATE_ATTACH),
            functionBody(DRAW, DRAW_SIGNATURE)
                .filter { it.startsWith("views.setPendingIntentTemplate(") },
        )
    }

    /**
     * Extras and nothing else, which is what makes the mutable template above acceptable.
     */
    @Test fun `a row's fill-in intent carries extras and nothing else`() {
        assertEquals(
            "the per-row fill-in must be a bare Intent carrying RecentMailWidgetTap.rowExtras and " +
                "nothing else — no action, no data, no component. Anything else in here is " +
                "written into the activity the template is about to start; and the extras are " +
                "the executed rule's, which WidgetCellOpensWhatItSaysTest runs per kind.",
            listOf(
                "fun rowFillIn(kind: RecentWidgetKind, tap: RecentRowTap): Intent {",
                "val intent = Intent()",
                "RecentMailWidgetTap.rowExtras(kind, tap).forEach { (name, value) -> intent.putExtra(name, value) }",
                "return intent",
            ),
            functionBody(DRAW, "fun rowFillIn("),
        )
    }

    /**
     * Every served row is given its own fill-in, on the row's root view, not on one of its
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
        // And the whole function, not merely that one line. The filter above proves a line is
        // present and says nothing about the others, which was harmless while this factory held
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
        // And the one line getViewAt delegates. Extracting the badge keeps the function above
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
     * The two widgets must not share a request code, and this rule reads the two numbers rather
     */
    @Test fun `every widget's request code, and every widget's action, is its own`() {
        val identities = UnreadWidgetKind.entries.map { "counter ${it.name}" to (it.action to it.requestCode) } +
            RecentWidgetKind.entries.map { "list ${it.name}" to (it.action to it.requestCode) }
        assertEquals(
            "four widgets, four request codes: 0 and 2 the counters', 1 and 3 the lists'. The " +
                "identity of a PendingIntent ignores extras, so two kinds on one pair rewrite each " +
                "other's template under FLAG_UPDATE_CURRENT: the unread list's rows open a message, " +
                "or the inboxes' rows the Unread view. Read off both enums as they run.",
            mapOf("counter Inboxes" to 0, "counter EveryFolder" to 2, "list Inboxes" to 1, "list Unread" to 3),
            identities.associate { (name, id) -> name to id.second },
        )
        assertEquals(
            "and four actions, none shared.",
            identities.size,
            identities.map { it.second.first }.toSet().size,
        )
    }

    private companion object {
        val DRAW = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetDraw.kt")
        val SERVICE = file("app/src/main/kotlin/app/sterna/widget/RecentMailWidgetService.kt")

        const val DRAW_SIGNATURE =
            "fun draw(app: Application, kind: RecentWidgetKind, ids: IntArray, drawing: RecentWidgetDrawing)"
        val ROW_LAYOUT = file("app/src/main/res/layout/widget_recent_row.xml")

        const val TEMPLATE_ATTACH =
            "views.setPendingIntentTemplate(R.id.widget_recent_list, template)"

        const val FILL_IN_ATTACH =
            "views.setOnClickFillInIntent(R.id.widget_recent_row_root, " +
                "RecentMailWidgetDraw.rowFillIn(kind, item.tap))"
    }
}
