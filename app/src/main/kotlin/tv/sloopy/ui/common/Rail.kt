package tv.sloopy.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
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
    leftTarget: FocusRequester? = null,
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
                left = leftTarget ?: FocusRequester.Cancel; right = FocusRequester.Cancel
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

// ---- The icon strip ---------------------------------------------------------------------------

val STRIP_ITEMS = listOf(
    "guide" to "Guide", "movies" to "Movies", "series" to "Series", "history" to "History",
    "recordings" to "Recordings", "search" to "Search", "settings" to "Settings",
)
val STRIP_W = 56.dp

/**
 * The thin column of icons at the far left of every screen, TiviMate-style. Landing on it opens
 * a label panel over whatever sits to its right; leaving closes it. [requester] attaches to the
 * icon for [current], so a screen can hand focus straight to its own icon.
 */
@Composable
fun NavStrip(
    current: String,
    go: (String) -> Unit,
    requester: FocusRequester? = null,
    canFocus: Boolean = true,
    onFocusChange: (Boolean) -> Unit = {},
) {
    var focusedRoute by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    Box(
        Modifier.width(STRIP_W).fillMaxHeight().zIndex(2f)
            .focusProperties { this.canFocus = canFocus }
            .onFocusChanged { expanded = it.hasFocus; onFocusChange(it.hasFocus) },
    ) {
        Column(Modifier.padding(top = RAIL_HEADER_H + 1.dp)) {
            STRIP_ITEMS.forEachIndexed { i, (route, label) ->
                var fc by remember { mutableStateOf(false) }
                Surface(
                    onClick = { go(route) },
                    shape = ClickableSurfaceDefaults.shape(RectangleShape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.focus, contentColor = Sloopy.text, focusedContentColor = Color.White),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.size(STRIP_W, RAIL_ROW_H)
                        .then(if (route == current && requester != null) Modifier.focusRequester(requester) else Modifier)
                        .focusProperties {
                            left = FocusRequester.Cancel
                            if (i == 0) up = FocusRequester.Cancel
                            if (i == STRIP_ITEMS.lastIndex) down = FocusRequester.Cancel
                        }
                        .onFocusChanged { fc = it.isFocused; if (it.isFocused) focusedRoute = route },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NavIcon(route, color = if (route == current || fc) Sloopy.text else Sloopy.dim)
                    }
                }
            }
        }
        if (expanded) {
            Column(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset(x = STRIP_W).width(236.dp).fillMaxHeight().background(Sloopy.ground)
                    .drawBehind { drawRect(Sloopy.hair, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height)) }
                    .padding(top = RAIL_HEADER_H + 1.dp),
            ) {
                STRIP_ITEMS.forEach { (route, label) ->
                    Box(Modifier.height(RAIL_ROW_H).fillMaxWidth().padding(start = 18.dp), contentAlignment = Alignment.CenterStart) {
                        Text(label.uppercase(), style = Sloopy.titleRow.copy(color = when { route == focusedRoute -> Color.White; route == current -> Sloopy.text; else -> Sloopy.muted }))
                    }
                }
            }
        }
    }
}

/** Seven line icons at one weight, drawn rather than shipped, so they match the hairline look. */
@Composable
fun NavIcon(route: String, color: Color, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = 1.6.dp.toPx()
        val st = Stroke(sw)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), sw)
        when (route) {
            "guide" -> {
                drawRect(color, Offset(sw / 2, sw / 2), Size(w - sw, h - sw), style = st)
                line(0f, h * 0.36f, w, h * 0.36f); line(0f, h * 0.68f, w, h * 0.68f); line(w * 0.36f, h * 0.36f, w * 0.36f, h)
            }
            "movies" -> {
                drawRect(color, Offset(sw / 2, sw / 2), Size(w - sw, h - sw), style = st)
                line(w * 0.22f, 0f, w * 0.22f, h); line(w * 0.78f, 0f, w * 0.78f, h)
                for (k in 0..3) { val y = h * (0.2f + 0.2f * k); line(0f, y, w * 0.22f, y); line(w * 0.78f, y, w, y) }
            }
            "series" -> {
                drawRect(color, Offset(sw / 2, h * 0.38f), Size(w - sw, h * 0.62f - sw / 2), style = st)
                line(w * 0.14f, h * 0.22f, w * 0.86f, h * 0.22f); line(w * 0.28f, h * 0.06f, w * 0.72f, h * 0.06f)
            }
            "history" -> {
                drawCircle(color, radius = w / 2 - sw / 2, style = st)
                line(w / 2, h * 0.24f, w / 2, h / 2); line(w / 2, h / 2, w * 0.7f, h * 0.62f)
            }
            "recordings" -> {
                drawCircle(color, radius = w / 2 - sw / 2, style = st)
                drawCircle(color, radius = w * 0.2f)
            }
            "search" -> {
                drawCircle(color, radius = w * 0.3f, center = Offset(w * 0.42f, h * 0.42f), style = st)
                line(w * 0.64f, h * 0.64f, w * 0.96f, h * 0.96f)
            }
            else -> {
                drawCircle(color, radius = w * 0.2f, style = st)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0); val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
                    line(w / 2 + c * w * 0.3f, h / 2 + s * h * 0.3f, w / 2 + c * w * 0.47f, h / 2 + s * h * 0.47f)
                }
            }
        }
    }
}
