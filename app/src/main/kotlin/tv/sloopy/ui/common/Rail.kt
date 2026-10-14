package tv.sloopy.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.sloopy.ui.theme.Sloopy

/** The left-hand panel every screen uses, TiviMate-style: groups, categories, sections. */
val RAIL_W = 300.dp
val RAIL_ROW_H = 54.dp
val RAIL_HEADER_H = 36.dp

/** Slim top line: what screen this is, and the clock. */
@Composable
fun ScreenHeader(title: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 72.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(title, bright = true); Spacer(Modifier.weight(1f)); Clock()
    }
}

@Composable
fun RailHeader(text: String, amber: Boolean = false) {
    Box(Modifier.height(RAIL_HEADER_H), contentAlignment = Alignment.CenterStart) {
        Text(text.uppercase(), style = Sloopy.label.copy(color = if (amber) Sloopy.amber else Sloopy.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Hairline()
}

/**
 * One rail row. Left and Right never move focus out of the rail (the screen decides what Right
 * does), and the first and last rows do not leak focus into whatever is above or below.
 * [grabbed] is the press-and-hold reorder state: amber edge, Up and Down move it.
 */
@Composable
fun RailItem(
    name: String,
    selected: Boolean,
    requester: FocusRequester?,
    first: Boolean,
    last: Boolean,
    grabbed: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var fc by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.focus, contentColor = Sloopy.muted, focusedContentColor = Color.White),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(1.dp, if (grabbed) Sloopy.amber else Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth().height(RAIL_ROW_H)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .focusProperties {
                left = FocusRequester.Cancel; right = FocusRequester.Cancel
                if (first) up = FocusRequester.Cancel
                if (last) down = FocusRequester.Cancel
            }
            .onFocusChanged { fc = it.isFocused },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (selected || grabbed) Sloopy.amber else Color.Transparent))
            Spacer(Modifier.width(14.dp))
            Text(name.uppercase(), style = Sloopy.titleRow.copy(color = if (selected || fc) Color.Unspecified else Sloopy.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
