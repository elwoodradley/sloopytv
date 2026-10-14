package tv.sloopy.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.sloopy.data.db.ChannelEntity
import tv.sloopy.ui.Playing
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.BACK_KEYS
import tv.sloopy.ui.common.ChannelLogo
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.SloopyButton
import tv.sloopy.ui.common.inKeys
import tv.sloopy.ui.common.isDown
import tv.sloopy.ui.theme.Sloopy

/**
 * OK on the picture: the badge, a strip of the ten channels watched most recently (OK on one
 * switches), and under it an action bar. Goes away after ten seconds without a key press.
 */
@UnstableApi
@Composable
fun InfoOverlay(
    vm: SloopyViewModel,
    channel: ChannelEntity,
    playing: Playing?,
    quality: String?,
    onClose: () -> Unit,
    go: (String) -> Unit,
    openChannels: () -> Unit,
) {
    val recents by vm.recentChannels.collectAsState()
    val favourites by vm.favouriteIds.collectAsState()
    val strip = remember(recents, channel.id) { recents.filter { it.id != channel.id }.take(10) }
    val first = remember { FocusRequester() }
    var idle by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LaunchedEffect(idle) { delay(10_000); onClose() }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE60B0B0C)))
            .onPreviewKeyEvent { ev ->
                if (!ev.isDown) return@onPreviewKeyEvent false
                idle++
                if (ev.inKeys(BACK_KEYS)) { onClose(); true } else false
            }
    ) {
        Osd(vm, channel, playing, quality)

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 40.dp)) {
            Label(if (strip.isEmpty()) "Recent channels · none yet" else "Recent channels", modifier = Modifier.padding(start = 72.dp))
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 72.dp, vertical = 10.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(strip, key = { _, c -> c.id }) { i, c ->
                    var focused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { vm.tune(c); onClose() },
                        shape = ClickableSurfaceDefaults.shape(RectangleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xCC111113), focusedContainerColor = Sloopy.focus, contentColor = Sloopy.text, focusedContentColor = Color.White),
                        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)), shape = RectangleShape)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.width(236.dp).height(72.dp)
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { focused = it.isFocused },
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChannelLogo(c.logo, c.name, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.number.toString(), style = Sloopy.mono14.copy(color = if (focused) Sloopy.textSoft else Sloopy.muted))
                                Text(c.name, style = Sloopy.cellTitle.copy(color = Color.Unspecified), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(start = 72.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val fav = channel.id in favourites
                SloopyButton(if (fav) "Remove favourite" else "Add favourite", onClick = { vm.toggleFavourite(channel); idle++ }, modifier = if (strip.isEmpty()) Modifier.focusRequester(first) else Modifier)
                Spacer(Modifier.width(8.dp))
                SloopyButton("Record", onClick = { vm.recordNow(channel); onClose() })
                Spacer(Modifier.width(8.dp))
                SloopyButton("Channels", onClick = { onClose(); openChannels() })
                Spacer(Modifier.width(8.dp))
                SloopyButton("Guide", onClick = { onClose(); go("guide") })
                Spacer(Modifier.width(8.dp))
                SloopyButton("Menu", onClick = { onClose(); vm.guideOpensAtSections = true; go("guide") })
                Spacer(Modifier.width(20.dp))
                if (playing is Playing.Live) { LiveDot(); Spacer(Modifier.width(8.dp)); Text("OK on a channel switches · Back closes", style = Sloopy.label.copy(color = Sloopy.dim)) }
            }
        }
    }
}
