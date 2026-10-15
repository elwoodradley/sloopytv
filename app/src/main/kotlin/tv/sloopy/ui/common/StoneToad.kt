package tv.sloopy.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.sloopy.ui.theme.Sloopy

/**
 * The Stone Toad wordmark, cut from the original artwork as a white-on-transparent image and
 * tinted with the theme colour. The source is far larger than the mark ever appears, so it
 * stays sharp on 4K.
 */
@Composable
fun StoneToadMark(modifier: Modifier = Modifier, height: Dp = 30.dp, color: Color = Sloopy.text) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(tv.sloopy.R.drawable.stone_toad_mark),
        contentDescription = "Stone Toad",
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = modifier.height(height).width(height * (1058f / 294f)),
    )
}

/** The mark with the line the other Stone Toad apps carry. */
@Composable
fun StoneToadCredit(modifier: Modifier = Modifier, operator: String? = null, color: Color = Sloopy.muted) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        StoneToadMark(height = 32.dp, color = color)
        Spacer(Modifier.height(8.dp))
        Text("A STONE TOAD JOINT" + (if (!operator.isNullOrBlank()) "  ·  SET UP BY ${operator.uppercase()}" else ""), style = Sloopy.label.copy(color = color))
    }
}
