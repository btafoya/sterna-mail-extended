package app.sterna.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.sterna.contacts.ContactSuggestion
import app.sterna.ui.components.ContactAvatar

/**
 * The floating recipient menu, shared by the composer's recipient fields and the search screen's
 * address criteria. It hangs under its parent layout, which is the anchor, and over whatever is below.
 * The caller measures the field: [fieldWidthPx] its width, [fieldBottomPx] its bottom edge in the
 * root and [windowHeightPx] the root's height, from which the room left above the keyboard is taken
 * (#143). What a pick does is the caller's.
 */
@Composable
internal fun RecipientSuggestionMenu(
    suggestions: List<ContactSuggestion>,
    visible: Boolean,
    fieldWidthPx: Int,
    fieldBottomPx: Int,
    windowHeightPx: Int,
    onPick: (ContactSuggestion) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    // The keyboard is read here, in the field's own composition, and never inside the Popup: a
    // Popup is a separate window and its insets are not the screen's. Reading it as an inset is the
    // only way to know the keyboard is up, since the window is not resized (edge to edge).
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    // Null when there is not even one row of room left under the field, in which case no menu is
    // drawn at all (#143). Keyed on every quantity that moves: the keyboard's height changes without
    // any of the caller's own state changing.
    val menuMaxHeight = remember(windowHeightPx, fieldBottomPx, imeBottomPx, density) {
        suggestionMenuMaxHeight(
            windowHeightPx = windowHeightPx,
            fieldBottomPx = fieldBottomPx,
            imeHeightPx = imeBottomPx,
            density = density,
        )
    }
    if (visible && suggestions.isNotEmpty() && menuMaxHeight != null) {
        // Not focusable, so the keyboard stays up and each keystroke keeps filtering.
        val below = remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.bottom)
            }
        }
        Popup(
            popupPositionProvider = below,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = false),
        ) {
            Surface(
                modifier = if (fieldWidthPx > 0) {
                    Modifier.width(with(density) { fieldWidthPx.toDp() })
                } else {
                    Modifier
                },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                // Capped at the room measured under the field and scrolling on its own: a lower cap
                // loses no suggestion, it only stops the list covering the keys that filter it.
                Column(
                    Modifier
                        .heightIn(max = menuMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    suggestions.forEachIndexed { index, contact ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        // The avatar slot is the same size with a photo or a monogram, so rows keep
                        // their height while photos decode and the list never jumps under the finger.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(contact) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ContactAvatar(
                                email = contact.email,
                                name = contact.name,
                                photoUri = contact.photoUri,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    contact.name ?: contact.email,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (contact.name != null) {
                                    Text(
                                        contact.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
