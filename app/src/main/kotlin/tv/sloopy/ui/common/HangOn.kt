package tv.sloopy.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tv.sloopy.ui.theme.Sloopy

/**
 * The waiting card: "HANG ON SLOOPY" (reconnecting) or "HANG ON" (buffering), big and in the
 * middle of the picture, with the one piece of motion the viewer asked for: a hairline outline
 * of Ohio and the amber dot running round it. Buffering waits a moment before it shows, so a
 * normal channel change never flashes it; a reconnect shows at once.
 */
@Composable
fun HangOnSloopy(reconnecting: Boolean, buffering: Boolean, modifier: Modifier = Modifier) {
    val active = reconnecting || buffering
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(active, reconnecting) {
        if (!active) { show = false; return@LaunchedEffect }
        if (!reconnecting) { show = false; delay(1200) }
        show = true
    }
    if (!show) return
    val t by rememberInfiniteTransition(label = "ohio").animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "dot")
    val line = Sloopy.text; val amber = Sloopy.amber
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.tv.material3.Text(
            if (reconnecting) "HANG ON SLOOPY" else "HANG ON",
            style = TextStyle(fontFamily = Sloopy.display, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 84.sp, letterSpacing = 0.02.sp, color = androidx.compose.ui.graphics.Color.White),
        )
        Spacer(Modifier.height(18.dp))
        Canvas(Modifier.size(150.dp, 138.dp)) {
            val w = size.width; val h = size.height
            val path = Path().apply {
                OHIO.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h) }
                close()
            }
            drawPath(path, line.copy(alpha = 0.85f), style = Stroke(1.5.dp.toPx()))
            val pm = PathMeasure().apply { setPath(path, true) }
            val p = pm.getPosition(pm.length * t)
            if (p != Offset.Unspecified) {
                drawCircle(amber.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = p)
                drawCircle(amber, radius = 4.dp.toPx(), center = p)
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.tv.material3.Text(if (reconnecting) "Getting the stream back" else "Loading the stream", style = Sloopy.label)
    }
}

/** Ohio, simplified: Lake Erie along the top, the straight western line, the river's bends below. 0..1 units. */
private val OHIO = listOf(
    // north-west corner, then the Lake Erie shore climbing to the north-east corner
    0.04f to 0.13f, 0.18f to 0.10f, 0.30f to 0.08f, 0.37f to 0.11f, 0.45f to 0.08f, 0.56f to 0.05f, 0.68f to 0.03f, 0.82f to 0.00f, 0.94f to 0.02f,
    // the Pennsylvania line, straight down
    0.95f to 0.40f,
    // the Ohio River, bend by bend, south-west to Cincinnati
    0.90f to 0.48f, 0.86f to 0.56f, 0.80f to 0.63f, 0.77f to 0.73f, 0.70f to 0.82f, 0.60f to 0.92f, 0.54f to 0.99f, 0.48f to 0.93f, 0.41f to 0.96f,
    0.33f to 0.89f, 0.26f to 0.85f, 0.18f to 0.90f, 0.12f to 0.84f, 0.06f to 0.78f,
    // the Indiana line, straight back up
    0.04f to 0.78f,
)
