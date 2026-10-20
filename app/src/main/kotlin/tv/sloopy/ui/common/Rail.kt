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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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

/** The strip in three bands (0.6.21, after TiviMate): watching, the library, and the foot of the screen. */
val STRIP_BANDS: List<List<Pair<String, String>>> = listOf(
    listOf("search" to "Search", "guide" to "Guide", "tonight" to "Tonight", "scores" to "Sports"),   // Search on top, as TiviMate (the buddy, 0.6.40)
    listOf("movies" to "Movies", "series" to "Series", "history" to "History", "recordings" to "Recordings"),
    listOf("settings" to "Settings"),
)
val STRIP_ITEMS = STRIP_BANDS.flatten()
fun stripBands(): List<List<Pair<String, String>>> = if (ToadFlag.on.value) STRIP_BANDS.dropLast(1) + listOf(listOf("toad" to "Toad") + STRIP_BANDS.last()) else STRIP_BANDS
val STRIP_W = 56.dp
val STRIP_ROW_H = 50.dp
val STRIP_GAP_H = 26.dp

/** Whether this stick may use the Toad; set by the view model after asking the code server. */
object ToadFlag { val on = mutableStateOf(false) }
/** The strip's rows: the fixed seven, plus the Toad above Settings when this stick is allowed. */
fun stripItems(): List<Pair<String, String>> = stripBands().flatten()

/**
 * The strip's vertical plan, shared by the icon column and the label panel so they line up:
 * the bands with a hairline gap between them, and the last band pinned to the foot.
 */
@Composable
private fun StripColumn(modifier: Modifier, row: @Composable (index: Int, route: String, label: String, rowH: androidx.compose.ui.unit.Dp) -> Unit) {
    val bands = stripBands()
    var i = 0
    // The guide gives the strip only the space under its programme panel, so rows shrink to fit
    // the height they are given (never above STRIP_ROW_H, never below 34dp). Before 0.6.37 a
    // fixed 50dp let the Toad push Settings off the bottom of the guide.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxHeight()) {
        val rows = bands.sumOf { it.size }
        val gaps = (bands.size - 2).coerceAtLeast(0)
        val fixed = RAIL_HEADER_H + 1.dp + 36.dp + STRIP_GAP_H * gaps + 8.dp
        val rowH = ((maxHeight - fixed) / rows.coerceAtLeast(1)).coerceIn(34.dp, STRIP_ROW_H)
        // 36dp at the foot keeps Settings inside a TV's overscan (about 5% of the height is unsafe).
        Column(Modifier.fillMaxHeight().padding(top = RAIL_HEADER_H + 1.dp, bottom = 36.dp)) {
            bands.forEachIndexed { b, band ->
                if (b == bands.lastIndex) Spacer(Modifier.weight(1f))
                else if (b > 0) Spacer(Modifier.height(STRIP_GAP_H))   // air between bands, no mark (the hairline read as a stray line)
                band.forEach { (route, label) -> row(i, route, label, rowH); i++ }
            }
        }
    }
}

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
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val topRow = remember { FocusRequester() }
    val items = stripItems()
    Box(
        Modifier.width(STRIP_W).fillMaxHeight().zIndex(2f).background(Sloopy.surface)
            .focusProperties { this.canFocus = canFocus }
            .onFocusChanged { expanded = it.hasFocus; onFocusChange(it.hasFocus) }
            // Right from an icon with nothing beside it (Settings and the Toad sit at the foot,
            // below every screen's menu) went nowhere; retry from the top icon, which always has
            // the screen's menu to its right. Screens that handle Right themselves (the guide)
            // see the key first and never reach this.
            .onPreviewKeyEvent { ev ->
                if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == androidx.compose.ui.input.key.Key.DirectionRight) {
                    if (!fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)) { runCatching { topRow.requestFocus() }; fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Right) }
                    true
                } else false
            },
    ) {
        // The mark in the strip's header zone: "TV" when folded, the full name when the strip
        // has focus (it overflows to the right, over the label panel's own empty header zone).
        Box(Modifier.height(RAIL_HEADER_H).width(STRIP_W).zIndex(3f).padding(start = if (expanded) 16.dp else 0.dp), contentAlignment = if (expanded) Alignment.CenterStart else Alignment.Center) {
            StripMark(expanded)
        }
        StripColumn(Modifier) { i, route, label, rowH ->
                var fc by remember { mutableStateOf(false) }
                Surface(
                    onClick = { go(route) },
                    shape = ClickableSurfaceDefaults.shape(RectangleShape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.focus, contentColor = Sloopy.text, focusedContentColor = Color.White),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.size(STRIP_W, rowH)
                        .then(if (route == current && requester != null) Modifier.focusRequester(requester) else Modifier)
                        .then(if (i == 0) Modifier.focusRequester(topRow) else Modifier)
                        .focusProperties {
                            left = FocusRequester.Cancel
                            if (i == 0) up = FocusRequester.Cancel
                            if (i == items.lastIndex) down = FocusRequester.Cancel
                        }
                        .onFocusChanged { fc = it.isFocused; if (it.isFocused) focusedRoute = route },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NavIcon(route, color = if (route == current || fc) Sloopy.text else Sloopy.dim, size = if (rowH < 44.dp) 21.dp else 24.dp)
                    }
                }
        }
        if (expanded) {
            StripColumn(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset(x = STRIP_W).width(236.dp).background(Sloopy.surface)
                    .drawBehind { drawRect(Sloopy.hair, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height)) },
            ) { _, route, label, rowH ->
                Box(Modifier.height(rowH).fillMaxWidth().padding(start = 18.dp), contentAlignment = Alignment.CenterStart) {
                    Text(label.uppercase(), style = Sloopy.titleRow.copy(color = when { route == focusedRoute -> Color.White; route == current -> Sloopy.text; else -> Sloopy.muted }))
                }
            }
        }
    }
}

/** The launcher banner's colour bars (SMPTE order), drawn, for the strip mark. */
private val BARS = listOf(Color(0xFFC0C0C0), Color(0xFFC0C000), Color(0xFF00C0C0), Color(0xFF00C000), Color(0xFFC000C0), Color(0xFFC00000), Color(0xFF0000C0))

/**
 * The strip's mark, after the launcher banner: an amber TV when folded; SLOOPY in off-white
 * and TV in amber when the strip has focus; the colour bars underneath in both. Drawn text
 * and seven rectangles, so it costs nothing to ship or to draw.
 */
@Composable
private fun StripMark(expanded: Boolean) {
    val face = androidx.compose.ui.text.TextStyle(fontFamily = Sloopy.display, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = if (expanded) 22.sp else 18.sp, letterSpacing = 0.01.em, lineHeight = 1.em)
    Column(Modifier.wrapContentWidth(if (expanded) Alignment.Start else Alignment.CenterHorizontally, unbounded = true), horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (expanded) Text("SLOOPY", style = face.copy(color = Sloopy.text), softWrap = false, maxLines = 1)
            if (expanded) Spacer(Modifier.width(4.dp))
            Text("TV", style = face.copy(color = Sloopy.amber), softWrap = false, maxLines = 1)
        }
        Row(Modifier.padding(top = 2.dp).height(3.dp).width(if (expanded) 116.dp else 30.dp)) {
            BARS.forEach { c -> Box(Modifier.weight(1f).fillMaxHeight().background(c)) }
        }
    }
}

/** Line icons at one weight, drawn rather than shipped, so they match the hairline look. */
@Composable
fun NavIcon(route: String, color: Color, size: androidx.compose.ui.unit.Dp = 22.dp) {
    val ground = Sloopy.ground
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
            "tonight" -> {
                // A crescent: the circle with a smaller circle cleared from one side.
                drawCircle(color, radius = w / 2 - sw / 2, style = st)
                drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), sw)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), sw)
            }
            "scores" -> {
                // Two score bars.
                drawRect(color, Offset(sw / 2, h * 0.18f), Size(w - sw, h * 0.26f), style = st)
                drawRect(color, Offset(sw / 2, h * 0.56f), Size(w - sw, h * 0.26f), style = st)
                drawRect(color, Offset(sw / 2, h * 0.18f), Size(w * 0.62f, h * 0.26f))
                drawRect(color, Offset(sw / 2, h * 0.56f), Size(w * 0.38f, h * 0.26f))
            }
            "toad" -> {
                // A toad, face on: a squat body, two eyes sitting on top of it (filled with the
                // ground so the body line stops at them), pupils, and a wide mouth.
                val body = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.20f, h * 0.44f)
                    cubicTo(w * 0.06f, h * 0.52f, w * 0.06f, h * 0.86f, w * 0.30f, h * 0.88f)
                    lineTo(w * 0.70f, h * 0.88f)
                    cubicTo(w * 0.94f, h * 0.86f, w * 0.94f, h * 0.52f, w * 0.80f, h * 0.44f)
                }
                drawPath(body, color, style = st)
                for (cx in listOf(w * 0.32f, w * 0.68f)) {
                    drawCircle(ground, radius = w * 0.13f, center = Offset(cx, h * 0.36f))
                    drawCircle(color, radius = w * 0.13f, center = Offset(cx, h * 0.36f), style = st)
                    drawCircle(color, radius = w * 0.04f, center = Offset(cx, h * 0.36f))
                }
                val mouth = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.36f, h * 0.66f); quadraticTo(w * 0.50f, h * 0.76f, w * 0.64f, h * 0.66f) }
                drawPath(mouth, color, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            "search" -> {
                drawCircle(color, radius = w * 0.3f, center = Offset(w * 0.42f, h * 0.42f), style = st)
                line(w * 0.64f, h * 0.64f, w * 0.96f, h * 0.96f)
            }
            else -> {
                // Settings: a gear with eight flat teeth and a hollow centre, one closed outline.
                val cx = w / 2; val cy = h / 2; val ro = w * 0.47f; val ri = w * 0.34f
                val path = androidx.compose.ui.graphics.Path()
                for (k in 0 until 8) {
                    val base = Math.toRadians(k * 45.0)
                    val pts = listOf(base - Math.toRadians(16.0) to ri, base - Math.toRadians(9.0) to ro, base + Math.toRadians(9.0) to ro, base + Math.toRadians(16.0) to ri)
                    pts.forEach { (a, r) ->
                        val x = cx + kotlin.math.cos(a).toFloat() * r; val y = cy + kotlin.math.sin(a).toFloat() * r
                        if (k == 0 && a == pts.first().first) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                path.close()
                drawPath(path, color, style = st)
                drawCircle(color, radius = w * 0.14f, center = Offset(cx, cy), style = st)
            }
        }
    }
}
