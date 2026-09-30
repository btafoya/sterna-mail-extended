package app.sterna.ui.settings

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.sterna.R
import app.sterna.ui.SCREEN_SLIDE_MS
import app.sterna.ui.rememberMotionEnabled
import kotlinx.coroutines.delay

/** The declaration of [SETTING_FOCUS_ARG] on a destination built with [focusable]. */
internal val settingFocusArguments = listOf(
    navArgument(SETTING_FOCUS_ARG) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

/** Saved-state key: set once the focus key has been shown, so no later composition shows it again. */
private const val SPOTLIGHT_SHOWN = "settingSpotlightShown"

internal fun resolveSetting(context: Context, entry: SettingEntry, accountLabel: String): ResolvedSetting {
    val path = when (val host = entry.host) {
        is SettingHost.Screen -> context.getString(host.path)
        SettingHost.CurrentAccount -> context.getString(R.string.settings_accounts_title) + " › " + accountLabel
    }
    return ResolvedSetting(
        entry = entry,
        title = context.getString(entry.title),
        path = path,
        texts = (listOfNotNull(entry.summary) + entry.options).map(context::getString),
    )
}

/**
 * The row, or section header, a search result opened this screen for: brought into view, then
 * flashed once. Drawn around the row by the row components themselves, and around the header by
 * `SettingsSection`, matched on the title they already receive and on [kind], so no call site
 * carries a marker and the lints that pin those call sites line by line stay as they are.
 *
 * Under reduced motion the flash is skipped. The scroll is still animated by `bringIntoView`, and
 * Compose runs it instantly when the system animator scale is 0, the setting that turns
 * [rememberMotionEnabled] off.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingSpotlightTarget(
    title: String,
    kind: AnchorKind = AnchorKind.ROW,
    content: @Composable () -> Unit,
) {
    // Inside a NavHost destination the owner is its NavBackStackEntry. The key is read off the
    // entry's arguments, and whether it was shown off the entry's saved state: that outlives Back,
    // rotation and process death, where a remembered flag would replay the flash after each.
    val entry = LocalViewModelStoreOwner.current as? NavBackStackEntry
    val focused = entry?.arguments?.getString(SETTING_FOCUS_ARG)?.let { settingEntryFor(it) }
    val anchor = focused?.takeIf { it.anchorKind == kind }?.anchor
    val anchorTitle = anchor?.let { stringResource(it) }
    val target = remember(entry, title, anchorTitle) {
        anchorTitle == title && entry?.savedStateHandle?.get<Boolean>(SPOTLIGHT_SHOWN) != true
    }
    val motion = rememberMotionEnabled()
    val requester = remember { BringIntoViewRequester() }
    val flash = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.primary
    // The same Box whether targeted or not: a structure that changed with the target would reset
    // the row's own remembered state (an open dialog, a revealed password) when the key is used.
    Box(
        if (!target) {
            Modifier
        } else {
            Modifier
                .bringIntoViewRequester(requester)
                .onPlaced { placed = true }
                .drawBehind { drawRect(tint.copy(alpha = 0.16f * flash.value)) }
        },
    ) {
        content()
    }
    if (target) {
        LaunchedEffect(placed) {
            if (!placed) return@LaunchedEffect
            entry?.savedStateHandle?.set(SPOTLIGHT_SHOWN, true)
            requester.bringIntoView()
            if (motion) {
                delay(SCREEN_SLIDE_MS.toLong())
                flash.animateTo(1f, tween(160))
                flash.animateTo(0f, tween(840, easing = FastOutSlowInEasing))
            }
        }
    }
}

/**
 * The hub's search field above [rubrics], which a typed query replaces with the matching settings.
 * The query is saveable: it is still there back from a result, and across rotation, process death
 * and the recreation a language change makes, so the next result is one tap away.
 */
@Composable
internal fun SettingsSearch(
    searchable: List<ResolvedSetting>,
    onOpenSetting: (SettingEntry) -> Unit,
    modifier: Modifier,
    rubrics: @Composable () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier) {
        SettingsSearchField(query = query, onQueryChange = { query = it })
        if (query.isBlank()) {
            rubrics()
        } else {
            SettingsSearchResults(
                results = searchSettings(query, searchable, { it.title }, { it.texts }),
                onOpenSetting = onOpenSetting,
            )
        }
    }
}

/** The hub's search field, the colours of the move dialog's folder filter: it filters what the
 *  app holds and asks nothing of a server, so no IME search action. */
@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    val hint = stringResource(R.string.settings_search_hint)
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                hint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.inbox_clear))
                }
            }
        },
        // The hint is also the field's spoken name, as on Material's own search field: the
        // placeholder is drawn only while the field is empty.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = hint },
    )
}

@Composable
private fun SettingsSearchResults(results: List<ResolvedSetting>, onOpenSetting: (SettingEntry) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (results.isEmpty()) {
            Text(
                stringResource(R.string.settings_search_no_result),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        results.forEach { result ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSetting(result.entry) }
                    .semantics(mergeDescendants = true) { role = Role.Button }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(result.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    result.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
