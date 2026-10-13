package tv.tube.ui.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.max

/**
 * The CRT power-on. Draws the real screen underneath and shutters it with four black rects
 * so no blend modes are needed: a horizontal line opens first, then the line bloats
 * vertically into the full picture with a bloom and fading scanlines.
 *
 * Timeline (1.6 s): 0.00–0.20 line grows from the centre to full width;
 * 0.20–0.46 hold; 0.46–0.82 vertical open; 0.82–1.00 settle, bloom fades;
 * scanlines fade over the last half plus 0.6 s.
 */
@Composable
fun TubePowerOn(
    modifier: Modifier = Modifier,
    durationMs: Int = 1600,
    onFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(durationMs + 600, easing = LinearEasing))
        onFinished()
    }
    Box(modifier.fillMaxSize()) {
        content()
        Canvas(Modifier.fillMaxSize()) {
            val p = t.value * (durationMs + 600f) / durationMs   // 0..1 over the main 1.6 s, then >1 during scanline tail
            drawTube(p, powerOn = true)
        }
    }
}

/** Reverse: the picture collapses to a line, then a dot, then afterglow. */
@Composable
fun TubePowerOff(
    modifier: Modifier = Modifier,
    durationMs: Int = 1300,
    onFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(durationMs + 700, easing = LinearEasing))
        onFinished()
    }
    Box(modifier.fillMaxSize()) {
        content()
        Canvas(Modifier.fillMaxSize()) {
            val p = t.value * (durationMs + 700f) / durationMs
            drawTube(p, powerOn = false)
        }
    }
}

private val openEase = CubicBezierEasing(0.25f, 0.8f, 0.25f, 1f)
private val closeEase = CubicBezierEasing(0.6f, 0.05f, 0.3f, 1f)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTube(p: Float, powerOn: Boolean) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f

    // Phase values in 0..1 for the line width (lw), the vertical opening (vo) and bloom (bl).
    val lw: Float
    val vo: Float
    val bloom: Float
    val scan: Float
    val dot: Float
    if (powerOn) {
        lw = openEase.transform(((p - 0f) / 0.20f).coerceIn(0f, 1f))
        vo = openEase.transform(((p - 0.46f) / 0.36f).coerceIn(0f, 1f))
        bloom = when {
            p < 0.20f -> 0.55f * (p / 0.20f)
            p < 0.46f -> 0.55f
            p < 0.82f -> 0.55f - 0.25f * ((p - 0.46f) / 0.36f)
            else -> (0.30f * (1f - ((p - 0.82f) / 0.18f))).coerceAtLeast(0f)
        }
        scan = if (p < 0.5f) 1f else (1f - (p - 0.5f) / 0.9f).coerceIn(0f, 1f)
        dot = 0f
    } else {
        vo = 1f - closeEase.transform(((p - 0f) / 0.34f).coerceIn(0f, 1f))
        lw = 1f - closeEase.transform(((p - 0.56f) / 0.24f).coerceIn(0f, 1f))
        bloom = when {
            p < 0.34f -> 0.45f * (p / 0.34f)
            p < 0.80f -> 0.45f
            else -> 0f
        }
        scan = 0f
        dot = if (p < 0.80f) 0f else (1f - (p - 0.80f) / 0.7f).coerceIn(0f, 1f)
    }

    val halfW = cx * lw
    val lineHalf = max(1.5f, h * 0.0015f)
    val halfH = lineHalf + (cy - lineHalf) * vo
    val left = cx - halfW
    val right = cx + halfW
    val top = cy - halfH
    val bottom = cy + halfH

    // Shutters.
    val black = Color.Black
    drawRect(black, Offset(0f, 0f), Size(w, max(0f, top)))
    drawRect(black, Offset(0f, bottom), Size(w, max(0f, h - bottom)))
    drawRect(black, Offset(0f, top), Size(max(0f, left), bottom - top))
    drawRect(black, Offset(right, top), Size(max(0f, w - right), bottom - top))
    if (lw <= 0.001f && vo <= 0.001f) {
        // fully closed
        if (dot > 0f) drawDot(cx, cy, dot)
        return
    }

    // Over-bright wash while the tube warms up: strongest at the beam, falling off vertically.
    if (bloom > 0f) {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFFFFF1DC).copy(alpha = bloom * 0.25f),
                0.5f to Color.White.copy(alpha = bloom),
                1f to Color(0xFFFFF1DC).copy(alpha = bloom * 0.25f),
                startY = top, endY = bottom,
            ),
            Offset(left, top), Size(right - left, bottom - top),
        )
    }

    // Scanlines.
    if (scan > 0f) {
        val step = max(3f, h / 220f)
        var y = top
        val c = Color.Black.copy(alpha = 0.42f * scan)
        while (y < bottom) {
            drawRect(c, Offset(left, y), Size(right - left, step * 0.4f))
            y += step
        }
    }

    // The beam: a bright line with vertical glow, visible while the picture is a line or just opening.
    val beamAlpha = when {
        powerOn -> if (vo < 1f) (1f - vo * 0.9f) else (0.1f * (1f - ((p - 0.82f) / 0.18f).coerceIn(0f, 1f)))
        else -> if (p < 0.80f) 1f else 0f
    }
    if (beamAlpha > 0f && lw > 0f) {
        val glowH = lineHalf * 14f + halfH * 0.25f
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to Color(0xFFFFE6C8).copy(alpha = 0.55f * beamAlpha),
                1f to Color.Transparent,
                startY = cy - glowH, endY = cy + glowH,
            ),
            Offset(left, cy - glowH), Size(right - left, glowH * 2),
        )
        drawRect(Color.White.copy(alpha = beamAlpha), Offset(left, cy - lineHalf), Size(right - left, lineHalf * 2))
    }
    if (dot > 0f) drawDot(cx, cy, dot)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDot(cx: Float, cy: Float, a: Float) {
    val r = 10f + 18f * a
    drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = a), 1f to Color.Transparent, center = Offset(cx, cy), radius = r * 2.5f), r * 2.5f, Offset(cx, cy))
    drawCircle(Color.White.copy(alpha = a), r * 0.35f, Offset(cx, cy))
}
