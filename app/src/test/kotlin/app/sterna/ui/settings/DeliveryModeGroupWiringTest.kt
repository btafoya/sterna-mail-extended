package app.sterna.ui.settings

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so neither list of radios is
 * ever drawn here and no screen reader was listened to. What is pinned is that every
 * `DeliveryModeOption` call of the settings sits directly inside a `Column` that says its children
 * are one group, and that such a `Column` holds the options of one choice and nothing else.
 *
 * The group is found from each call, by walking up to the block that opens around it: a call added
 * elsewhere shows up as a group of its own instead of going unread.
 *
 * Holes of `CONTRIBUTING.md` that apply: 3 (each group is pinned whole, so no option can leave it
 * and no switch can enter it, but a branch drawn around the `Column` is not seen) and 4 (what the
 * rows end up exposing is never observed: that `selectableGroup` on the parent makes a reader
 * count the rows as one set is the library's, read and not run).
 */
class DeliveryModeGroupWiringTest {

    private val lines = DeclarationSource.kotlinLines(
        DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"),
    )

    private val groups: List<List<String>> =
        lines.indices.filter { lines[it] == "DeliveryModeOption(" }.map(::enclosingBlock).distinct()

    @Test fun `every list of exclusive modes says it is one group`() {
        assertEquals(
            "a radio outside a selectable group is read as a lone button: nothing tells the " +
                "listener that the rows exclude one another, nor how many there are. Each entry " +
                "is the line that opens the block directly around a `DeliveryModeOption` call",
            listOf(GROUP, GROUP),
            groups.map { it.first() },
        )
    }

    @Test fun `a group holds the options of one choice and nothing else`() {
        assertEquals(
            "a switch or a note inside the group is counted among the choices, and two choices " +
                "under one group are read as a single set",
            listOf(NOTIFICATION_CONTENT, DELIVERY),
            groups,
        )
    }

    private fun enclosingBlock(at: Int): List<String> {
        var depth = 0
        val start = (at - 1 downTo 0).first { i ->
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            depth > 0
        }
        val block = mutableListOf<String>()
        depth = 0
        for (i in start until lines.size) {
            block += lines[i]
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (depth == 0) return block
        }
        error("the block opened by `${lines[start]}` never closes")
    }

    private companion object {
        const val GROUP = "Column(Modifier.selectableGroup()) {"

        val NOTIFICATION_CONTENT = listOf(
            GROUP,
            "DeliveryModeOption(",
            "title = stringResource(R.string.settings_notif_content_preview),",
            "subtitle = stringResource(R.string.settings_notif_content_preview_desc),",
            "selected = state.notificationContent == NotificationContent.BODY_PREVIEW,",
            "onClick = { viewModel.setNotificationContent(NotificationContent.BODY_PREVIEW) },",
            ")",
            "DeliveryModeOption(",
            "title = stringResource(R.string.settings_notif_content_full),",
            "subtitle = stringResource(R.string.settings_notif_content_full_desc),",
            "selected = state.notificationContent == NotificationContent.SENDER_AND_SUBJECT,",
            "onClick = { viewModel.setNotificationContent(NotificationContent.SENDER_AND_SUBJECT) },",
            ")",
            "DeliveryModeOption(",
            "title = stringResource(R.string.settings_notif_content_sender),",
            "subtitle = stringResource(R.string.settings_notif_content_sender_desc),",
            "selected = state.notificationContent == NotificationContent.SENDER_ONLY,",
            "onClick = { viewModel.setNotificationContent(NotificationContent.SENDER_ONLY) },",
            ")",
            "DeliveryModeOption(",
            "title = stringResource(R.string.settings_notif_content_none),",
            "subtitle = stringResource(R.string.settings_notif_content_none_desc),",
            "selected = state.notificationContent == NotificationContent.NONE,",
            "onClick = { viewModel.setNotificationContent(NotificationContent.NONE) },",
            ")",
            "}",
        )

        val DELIVERY = listOf(
            GROUP,
            "DeliveryModeOption(",
            "title = stringResource(R.string.settings_delivery_instant),",
            "subtitle = stringResource(R.string.settings_delivery_instant_desc),",
            "selected = deliveryMode == DeliveryMode.INSTANT,",
            "onClick = {",
            "deliveryMode = DeliveryMode.INSTANT",
            "viewModel.setDeliveryMode(accountId, DeliveryMode.INSTANT)",
            "},",
            ")",
            "DeliveryModeOption(",
            "title = stringResource(R.string.settings_delivery_saver),",
            "subtitle = stringResource(R.string.settings_delivery_saver_desc),",
            "selected = deliveryMode == DeliveryMode.BATTERY_SAVER,",
            "onClick = {",
            "deliveryMode = DeliveryMode.BATTERY_SAVER",
            "viewModel.setDeliveryMode(accountId, DeliveryMode.BATTERY_SAVER)",
            "},",
            ")",
            "}",
        )
    }
}
