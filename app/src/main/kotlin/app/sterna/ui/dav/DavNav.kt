package app.sterna.ui.dav

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.R
import app.sterna.container
import app.sterna.core.jmap.JmapType

/** Opens a route of the app's NavHost; set where the NavHost is, read by the drawer. A no-op outside it. */
val LocalDavNav = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The drawer's calendar, contacts and files entries for [accountId]: each only when the account's
 * server listed the capability at its last sync, so an IMAP account or a server without them shows nothing.
 */
@Composable
fun DavDrawerItems(accountId: String, onClose: () -> Unit) {
    val container = (LocalContext.current.applicationContext as Application).container
    val supported by container.davRepository.watchSupported(accountId).collectAsStateWithLifecycle(emptyList())
    val open = LocalDavNav.current
    val arg = Uri.encode(accountId)
    fun item(type: JmapType, route: String, label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) = type to Triple(route, label, icon)
    listOf(
        item(JmapType.CALENDAR_EVENT, "calendar", R.string.dav_calendar, Icons.Filled.CalendarMonth),
        item(JmapType.CONTACT_CARD, "contacts", R.string.dav_contacts, Icons.Filled.Contacts),
        item(JmapType.FILE_NODE, "files", R.string.dav_files, Icons.Filled.Folder),
    ).filter { (type, _) -> type.name in supported }.forEach { (_, entry) ->
        val (route, label, icon) = entry
        NavigationDrawerItem(
            icon = { Icon(icon, contentDescription = null) },
            label = { Text(stringResource(label)) },
            selected = false,
            onClick = { open("$route?accountId=$arg"); onClose() },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
