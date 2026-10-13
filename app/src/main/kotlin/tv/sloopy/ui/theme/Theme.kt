package tv.sloopy.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import tv.sloopy.R

/** Design tokens from the approved mockups. Sharp corners, hairlines, one amber accent. */
object Sloopy {
    val ground = Color(0xFF0B0B0C)
    val surface = Color(0xFF111113)
    val surface2 = Color(0xFF1B1B1F)
    val focus = Color(0xFF222226)
    val hair = Color(0xFF232325)
    val hair2 = Color(0xFF1A1A1C)
    val text = Color(0xFFEBE9E4)
    val textSoft = Color(0xFFB9B6AE)
    val muted = Color(0xFF8D8A84)
    val dim = Color(0xFF5F5D58)
    val amber = Color(0xFFE8A53A)
    val white = Color(0xFFFFFFFF)
    val black = Color(0xFF000000)
    val scrim = Color(0xF50B0B0C)

    private fun v(weight: Int) = FontVariation.Settings(FontVariation.weight(weight))

    val display = FontFamily(
        Font(R.font.big_shoulders_display, FontWeight.Medium, variationSettings = v(500)),
        Font(R.font.big_shoulders_display, FontWeight.SemiBold, variationSettings = v(600)),
        Font(R.font.big_shoulders_display, FontWeight.Bold, variationSettings = v(700)),
        Font(R.font.big_shoulders_display, FontWeight.ExtraBold, variationSettings = v(800)),
    )
    val sans = FontFamily(
        Font(R.font.instrument_sans, FontWeight.Normal, variationSettings = v(400)),
        Font(R.font.instrument_sans, FontWeight.Medium, variationSettings = v(500)),
        Font(R.font.instrument_sans, FontWeight.SemiBold, variationSettings = v(600)),
    )
    val mono = FontFamily(
        Font(R.font.geist_mono, FontWeight.Normal, variationSettings = v(400)),
        Font(R.font.geist_mono, FontWeight.Medium, variationSettings = v(500)),
    )

    // TV type scale. The 1080p reference is 1920x1080 at 320dpi-ish "tvdpi": 1dp ~ 1.33px, so
    // the 92px mockup title is ~69sp. Values below are the mockup sizes divided by 1.33.
    val titleHuge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 69.sp, lineHeight = 0.92.em, letterSpacing = 0.005.em, color = white)
    val titleBig = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 0.95.em, color = white)
    val titleRow = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, letterSpacing = 0.04.em, color = text)
    val osdName = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 0.95.em, letterSpacing = 0.01.em, color = white)
    val body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 1.4.em, color = textSoft)
    val bodyBig = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 19.sp, lineHeight = 1.4.em, color = textSoft)
    val cellTitle = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = text)
    val listName = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 19.sp, color = text)
    val listSub = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = muted)
    val label = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.18.em, color = muted)
    val labelBright = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, letterSpacing = 0.18.em, color = text)
    val mono14 = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, letterSpacing = 0.08.em, color = muted)
    val mono16 = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = 0.08.em, color = text)
    val meta = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, letterSpacing = 0.06.em, color = amber)
    val clock = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 18.sp, letterSpacing = 0.08.em, color = text)
    val button = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, letterSpacing = 0.16.em, color = text)
    val input = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 22.sp, color = text)
    val inputMono = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 20.sp, letterSpacing = 0.02.em, color = text)
}

@Composable
fun SloopyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sloopy.text,
            onPrimary = Sloopy.ground,
            secondary = Sloopy.amber,
            background = Sloopy.ground,
            onBackground = Sloopy.text,
            surface = Sloopy.surface,
            onSurface = Sloopy.text,
            surfaceVariant = Sloopy.surface2,
            onSurfaceVariant = Sloopy.muted,
            border = Sloopy.hair,
        ),
        content = content,
    )
}
