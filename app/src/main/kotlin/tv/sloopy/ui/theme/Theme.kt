package tv.sloopy.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/** A palette: what a theme changes. Everything else (type, layout, hairlines) stays. */
data class Palette(
    val id: String, val name: String,
    val ground: Color, val surface: Color, val surface2: Color, val focus: Color,
    val hair: Color, val hair2: Color,
    val text: Color, val textSoft: Color, val muted: Color, val dim: Color,
    val accent: Color,
)

object Palettes {
    val PHOSPHOR = Palette("phosphor", "Phosphor", Color(0xFF0B0B0C), Color(0xFF111113), Color(0xFF1B1B1F), Color(0xFF222226), Color(0xFF232325), Color(0xFF1A1A1C), Color(0xFFEBE9E4), Color(0xFFB9B6AE), Color(0xFF8D8A84), Color(0xFF5F5D58), Color(0xFFE8A53A))
    val CATHODE = Palette("cathode", "Cathode", Color(0xFF090C0A), Color(0xFF0F1511), Color(0xFF17201A), Color(0xFF1E2A22), Color(0xFF1F2A23), Color(0xFF151D18), Color(0xFFE4EEE6), Color(0xFFAFC0B3), Color(0xFF7F9486), Color(0xFF56685C), Color(0xFF5BE38A))
    val COBALT = Palette("cobalt", "Cobalt", Color(0xFF090B10), Color(0xFF0F1219), Color(0xFF171C27), Color(0xFF1E2533), Color(0xFF202735), Color(0xFF161B27), Color(0xFFE6E9F0), Color(0xFFB2B9C8), Color(0xFF838CA0), Color(0xFF596273), Color(0xFF5FA8FF))
    val EMBER = Palette("ember", "Ember", Color(0xFF0D0908), Color(0xFF15100E), Color(0xFF201917), Color(0xFF2A201C), Color(0xFF2A211E), Color(0xFF1D1614), Color(0xFFF0E8E2), Color(0xFFC3B6AD), Color(0xFF948880), Color(0xFF665C56), Color(0xFFFF6B4A))
    val CONTRAST = Palette("contrast", "High contrast", Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFF161616), Color(0xFF2A2A2A), Color(0xFF3A3A3A), Color(0xFF262626), Color(0xFFFFFFFF), Color(0xFFE0E0E0), Color(0xFFB0B0B0), Color(0xFF808080), Color(0xFFFFC14D))
    val all = listOf(PHOSPHOR, CATHODE, COBALT, EMBER, CONTRAST)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: PHOSPHOR
}

/**
 * Design tokens from the approved mockups. Sharp corners, hairlines, one accent. Colours read
 * the current palette, so switching the theme recomposes everything that uses them.
 */
object Sloopy {
    var palette: Palette by androidx.compose.runtime.mutableStateOf(Palettes.PHOSPHOR)

    val ground get() = palette.ground
    val surface get() = palette.surface
    val surface2 get() = palette.surface2
    val focus get() = palette.focus
    val hair get() = palette.hair
    val hair2 get() = palette.hair2
    val text get() = palette.text
    val textSoft get() = palette.textSoft
    val muted get() = palette.muted
    val dim get() = palette.dim
    val amber get() = palette.accent
    val white get() = palette.text
    val black = Color(0xFF000000)
    val scrim get() = palette.ground.copy(alpha = 0.96f)

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
    val titleHuge get() = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 69.sp, lineHeight = 0.92.em, letterSpacing = 0.005.em, color = white)
    val titleBig get() = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 0.95.em, color = white)
    val titleRow get() = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, letterSpacing = 0.04.em, color = text)
    val osdName get() = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 0.95.em, letterSpacing = 0.01.em, color = white)
    val body get() = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 1.4.em, color = textSoft)
    val bodyBig get() = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 19.sp, lineHeight = 1.4.em, color = textSoft)
    val cellTitle get() = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = text)
    val listName get() = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 19.sp, color = text)
    val listSub get() = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = muted)
    val label get() = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.18.em, color = muted)
    val labelBright get() = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, letterSpacing = 0.18.em, color = text)
    val mono14 get() = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, letterSpacing = 0.08.em, color = muted)
    val mono16 get() = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = 0.08.em, color = text)
    val meta get() = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, letterSpacing = 0.06.em, color = amber)
    val clock get() = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 18.sp, letterSpacing = 0.08.em, color = text)
    val button get() = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, letterSpacing = 0.16.em, color = text)
    val input get() = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 22.sp, color = text)
    val inputMono get() = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 20.sp, letterSpacing = 0.02.em, color = text)
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
