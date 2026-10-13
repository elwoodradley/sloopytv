package tv.sloopy.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.sloopy.ui.theme.Sloopy
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Sloopy.hair) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun VHairline(modifier: Modifier = Modifier, color: Color = Sloopy.hair) {
    Box(modifier.fillMaxHeight().width(1.dp).background(color))
}

/** Focus shown as light: lifted surface, one-pixel white edge, soft glow. Never a rounded ring. */
fun Modifier.focusLight(focused: Boolean, lift: Boolean = true): Modifier = this.drawBehind {
    if (focused) {
        if (lift) drawRect(Sloopy.focus)
        drawRect(Color.White.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
    }
}

/**
 * A sharp, hairline-bordered button in mono caps. Primary variant inverts to off-white.
 */
@Composable
fun SloopyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Sloopy.text else Color.Transparent,
            contentColor = if (primary) Sloopy.ground else Sloopy.text,
            focusedContainerColor = if (primary) Color.White else Sloopy.focus,
            focusedContentColor = if (primary) Sloopy.ground else Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Sloopy.dim,
        ),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, if (primary) Sloopy.text else Color(0xFF3A3A3E)), shape = RectangleShape),
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White), shape = RectangleShape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(text.uppercase(), style = Sloopy.button.copy(color = Color.Unspecified))
        }
    }
}

/** Square monogram used when a channel has no logo, or while it loads. */
@Composable
fun Monogram(name: String, size: Dp, modifier: Modifier = Modifier) {
    val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "·"
    Box(modifier.size(size).background(Sloopy.surface2), contentAlignment = Alignment.Center) {
        Text(letter, style = Sloopy.titleRow.copy(fontSize = (size.value * 0.55f).sp, color = Sloopy.text))
    }
}

@Composable
fun ChannelLogo(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    if (url == null) { Monogram(name, size, modifier); return }
    Box(modifier.size(size).background(Sloopy.surface2), contentAlignment = Alignment.Center) {
        Monogram(name, size)
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size).padding(4.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    }
}

@Composable
fun ProgressHairline(fraction: Float, modifier: Modifier = Modifier, track: Color = Color(0x40FFFFFF), fill: Color = Color.White, thickness: Dp = 2.dp) {
    Box(modifier.height(thickness).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(thickness).background(fill))
    }
}

@Composable
fun Clock(style: TextStyle = Sloopy.clock, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(10_000) } }
    Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), style = style, modifier = modifier)
}

@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(Sloopy.amber))
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, bright: Boolean = false) {
    Text(text.uppercase(), style = if (bright) Sloopy.labelBright else Sloopy.label, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Top navigation as in the mockups: mono caps, the current one underlined by a hairline. */
@Composable
fun TopNav(current: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, items: List<Pair<String, String>> = NAV_ITEMS) {
    Row(modifier.fillMaxWidth().height(54.dp).padding(horizontal = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (route, label) ->
            var focused by remember { mutableStateOf(false) }
            val cur = route == current
            Surface(
                onClick = { onSelect(route) },
                shape = ClickableSurfaceDefaults.shape(RectangleShape),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent, contentColor = Sloopy.muted, focusedContentColor = Color.White),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                modifier = Modifier.onFocusChanged { focused = it.isFocused }.padding(end = 30.dp),
            ) {
                Box(Modifier.padding(vertical = 6.dp).drawBehind {
                    if (cur) drawRect(Sloopy.text, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
                    if (focused) drawRect(Color.White.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }) {
                    Text(label.uppercase(), style = Sloopy.labelBright.copy(color = if (cur || focused) Color.Unspecified else Sloopy.muted, fontSize = 13.sp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Clock()
    }
}

val NAV_ITEMS = listOf("live" to "Live", "guide" to "Guide", "movies" to "Movies", "series" to "Series", "history" to "History", "search" to "Search", "settings" to "Settings")

// ---- Key helpers ---------------------------------------------------------------------------

val KeyEvent.isDown: Boolean get() = type == KeyEventType.KeyDown
fun KeyEvent.isKey(k1: Key, k2: Key? = null, k3: Key? = null, k4: Key? = null): Boolean = key == k1 || key == k2 || key == k3 || key == k4
fun KeyEvent.inKeys(keys: Set<Key>): Boolean = key in keys

val KeyEvent.digit: Int? get() = when (key) {
    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2; Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5; Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8; Key.Nine, Key.NumPad9 -> 9; else -> null
}

val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
val BACK_KEYS = setOf(Key.Back, Key.Escape)
