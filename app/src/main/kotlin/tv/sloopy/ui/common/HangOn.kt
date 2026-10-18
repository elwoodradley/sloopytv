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
 * The waiting card: "HANG ON SLOOPY", big and in the middle of the picture, with the one piece
 * of motion the viewer asked for: a hairline outline of Ohio (the real boundary) and a scarlet
 * dot running round it. Buffering waits a moment before it shows, so a normal channel change
 * never flashes it; a reconnect shows at once. The small line underneath says which it is.
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
    if (show) OhioCard(if (reconnecting) "Getting the stream back" else "Loading the stream", modifier)
}

/**
 * The same card as the app's "wait" everywhere else (the buddy, 2026-10-18: Search, Sports and
 * Tonight said so in small letters that were easy to miss). Shown while the caller is loading;
 * it waits [delayMs] first so a quick answer never flashes it.
 */
@Composable
fun LoadingSloopy(caption: String, modifier: Modifier = Modifier, delayMs: Long = 350) {
    var show by remember { mutableStateOf(delayMs <= 0) }
    LaunchedEffect(Unit) { if (!show) { delay(delayMs); show = true } }
    if (show) OhioCard(caption, modifier)
}

@Composable
private fun OhioCard(caption: String, modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "ohio").animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "dot")
    val line = Sloopy.text; val dot = androidx.compose.ui.graphics.Color(0xFFE0282E)   // scarlet, the viewer's pick for the runner
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.tv.material3.Text(
            "HANG ON SLOOPY",
            style = TextStyle(fontFamily = Sloopy.display, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 84.sp, letterSpacing = 0.02.sp, color = androidx.compose.ui.graphics.Color.White),
        )
        Spacer(Modifier.height(18.dp))
        Canvas(Modifier.size(150.dp, 162.dp)) {
            val w = size.width; val h = size.height
            val path = Path().apply {
                OHIO.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h) }
                close()
            }
            drawPath(path, line.copy(alpha = 0.85f), style = Stroke(1.5.dp.toPx()))
            val pm = PathMeasure().apply { setPath(path, true) }
            val p = pm.getPosition(pm.length * t)
            if (p != Offset.Unspecified) {
                drawCircle(dot.copy(alpha = 0.3f), radius = 9.dp.toPx(), center = p)
                drawCircle(dot, radius = 4.5.dp.toPx(), center = p)
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.tv.material3.Text(caption, style = Sloopy.label)
    }
}

/** Ohio's boundary from the US Census 1:10m cartographic file (us-atlas states-10m, state 39), equirectangular at Ohio's mean latitude, 116 points after simplification. 0..1 units, width:height 0.924. */
private val OHIO = listOf(
    0.0033f to 0.0789f, 0.3180f to 0.0685f, 0.3189f to 0.0748f, 0.3364f to 0.0815f, 0.3472f to 0.0774f,
    0.3781f to 0.0971f, 0.3990f to 0.1019f, 0.4232f to 0.1229f, 0.4391f to 0.1298f, 0.4541f to 0.1243f,
    0.4558f to 0.1123f, 0.4616f to 0.1092f, 0.4733f to 0.1224f, 0.4891f to 0.1219f, 0.4883f to 0.1308f,
    0.5125f to 0.1539f, 0.5442f to 0.1669f, 0.5785f to 0.1532f, 0.5968f to 0.1522f, 0.6536f to 0.1294f,
    0.6845f to 0.1385f, 0.7170f to 0.1371f, 0.8222f to 0.0608f, 0.8865f to 0.0346f, 0.9115f to 0.0305f,
    1.0000f to 0.0000f, 1.0000f to 0.3748f, 0.9733f to 0.3811f, 0.9658f to 0.3931f, 0.9750f to 0.4037f,
    0.9825f to 0.4239f, 0.9816f to 0.4638f, 0.9775f to 0.4794f, 0.9691f to 0.4850f, 0.9566f to 0.5112f,
    0.9566f to 0.5251f, 0.9491f to 0.5323f, 0.9449f to 0.5790f, 0.9341f to 0.5763f, 0.9374f to 0.5908f,
    0.9290f to 0.5963f, 0.9290f to 0.6100f, 0.9190f to 0.6215f, 0.9282f to 0.6336f, 0.9199f to 0.6398f,
    0.9165f to 0.6598f, 0.9032f to 0.6619f, 0.8397f to 0.7235f, 0.7988f to 0.7374f, 0.7938f to 0.7249f,
    0.7796f to 0.7204f, 0.7588f to 0.7386f, 0.7563f to 0.7586f, 0.7304f to 0.7569f, 0.7279f to 0.7701f,
    0.7195f to 0.7730f, 0.7129f to 0.7829f, 0.7154f to 0.8060f, 0.6995f to 0.8105f, 0.7020f to 0.8209f,
    0.7104f to 0.8278f, 0.7070f to 0.8545f, 0.6995f to 0.8485f, 0.6820f to 0.8687f, 0.6728f to 0.8639f,
    0.6795f to 0.8543f, 0.6694f to 0.8353f, 0.6603f to 0.8351f, 0.6494f to 0.8254f, 0.6344f to 0.8408f,
    0.6227f to 0.8620f, 0.6227f to 0.8781f, 0.6052f to 0.8906f, 0.6160f to 0.9401f, 0.6110f to 0.9473f,
    0.5977f to 0.9457f, 0.5876f to 0.9524f, 0.5810f to 0.9877f, 0.5326f to 1.0000f, 0.5175f to 0.9952f,
    0.5159f to 0.9846f, 0.4875f to 0.9572f, 0.4699f to 0.9558f, 0.4591f to 0.9469f, 0.4516f to 0.9202f,
    0.4541f to 0.9096f, 0.4482f to 0.9017f, 0.4307f to 0.9094f, 0.4182f to 0.9094f, 0.3957f to 0.9269f,
    0.3873f to 0.9396f, 0.3623f to 0.9394f, 0.3514f to 0.9452f, 0.3472f to 0.9348f, 0.3381f to 0.9291f,
    0.3147f to 0.9243f, 0.3022f to 0.9166f, 0.2805f to 0.9219f, 0.2688f to 0.9377f, 0.2454f to 0.9308f,
    0.2412f to 0.9187f, 0.2287f to 0.9125f, 0.2262f to 0.9043f, 0.2020f to 0.8932f, 0.1745f to 0.8978f,
    0.1411f to 0.8880f, 0.1369f to 0.8658f, 0.1152f to 0.8257f, 0.0910f to 0.8180f, 0.0876f to 0.8014f,
    0.0810f to 0.7995f, 0.0743f to 0.8064f, 0.0626f to 0.8057f, 0.0509f to 0.8127f, 0.0150f to 0.7925f,
    0.0000f to 0.8040f,
)

