package tv.sloopy.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.sloopy.ui.Groups
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.BACK_KEYS
import tv.sloopy.ui.common.ChannelLogo
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.ProgressHairline
import tv.sloopy.ui.common.isDown
import tv.sloopy.ui.common.isKey
import tv.sloopy.ui.common.inKeys
import tv.sloopy.ui.theme.Sloopy

/**
 * The channel list over the live picture: groups rail on the left, channels beside it, the
 * picture keeps playing behind a scrim on the right.
 */
@UnstableApi
@Composable
fun ChannelsOverlay(vm: SloopyViewModel, onClose: () -> Unit) {
    val categories by vm.liveCategories.collectAsState()
    val group by vm.group.collectAsState()
    val channels by vm.visibleChannels.collectAsState()
    val favourites by vm.favouriteIds.collectAsState()
    val nowPlaying by vm.nowPlaying.collectAsState()
    val current by vm.currentChannel.collectAsState()
    val locked by vm.lockedGroups.collectAsState()
    val unlocked by vm.unlockedSession.collectAsState()

    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val now = System.currentTimeMillis() / 1000

    val groups = remember(categories, locked, unlocked) {
        listOf(Groups.ALL to "All", Groups.FAV to "Favourites") +
            categories.filter { unlocked || it.id !in locked }.map { it.id to it.name }
    }

    LaunchedEffect(channels.size) {
        val i = channels.indexOfFirst { it.id == current?.id }
        if (i >= 0) { listState.scrollToItem((i - 3).coerceAtLeast(0)); runCatching { currentFocus.requestFocus() } }
        else if (channels.isNotEmpty()) { listState.scrollToItem(0); runCatching { firstFocus.requestFocus() } }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(0f to Color(0xF80B0B0C), 0.56f to Color(0xF00B0B0C), 0.72f to Color(0x8C0B0B0C), 1f to Color.Transparent))
            .onPreviewKeyEvent { ev ->
                if (!ev.isDown) return@onPreviewKeyEvent false
                when {
                    ev.inKeys(BACK_KEYS) -> { onClose(); true }
                    ev.isKey(Key.DirectionRight) && false -> true
                    else -> false
                }
            }
    ) {
        Row(Modifier.fillMaxSize().padding(start = 72.dp, top = 54.dp, bottom = 36.dp)) {
            // Groups rail
            LazyColumn(Modifier.width(190.dp).fillMaxHeight().padding(top = 42.dp)) {
                itemsIndexed(groups, key = { _, g -> g.first }) { _, (id, name) ->
                    var focused by remember { mutableStateOf(false) }
                    val cur = id == group
                    Surface(
                        onClick = { vm.selectGroup(id) },
                        shape = ClickableSurfaceDefaults.shape(RectangleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent, contentColor = Sloopy.muted, focusedContentColor = Color.White),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.fillMaxWidth().height(40.dp).onFocusChanged { focused = it.isFocused },
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).background(if (cur) Sloopy.amber else Color.Transparent))
                            Spacer(Modifier.width(11.dp))
                            Text(name.uppercase(), style = Sloopy.labelBright.copy(color = if (cur || focused) Color.Unspecified else Sloopy.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.width(38.dp))
            Column(Modifier.width(560.dp).fillMaxHeight()) {
                Label(groups.firstOrNull { it.first == group }?.second.orEmpty() + " · ${channels.size} channels", modifier = Modifier.height(42.dp))
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
                        val isCurrent = c.id == current?.id
                        val np = c.epgId?.let { nowPlaying[it] }
                        var focused by remember { mutableStateOf(false) }
                        val fr = if (isCurrent) currentFocus else if (i == 0) firstFocus else null
                        Surface(
                            onClick = { vm.tune(c); onClose() },
                            onLongClick = { vm.toggleFavourite(c) },
                            shape = ClickableSurfaceDefaults.shape(RectangleShape),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.surface2, contentColor = Sloopy.text, focusedContentColor = Color.White),
                            border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            modifier = Modifier.fillMaxWidth().height(66.dp)
                                .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
                                .onFocusChanged { focused = it.isFocused },
                        ) {
                            Column {
                                Row(Modifier.fillMaxWidth().height(65.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.number.toString(), style = Sloopy.mono14.copy(color = if (focused) Sloopy.textSoft else Sloopy.muted), modifier = Modifier.width(44.dp))
                                    ChannelLogo(c.logo, c.name, 34.dp)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, style = Sloopy.listName.copy(color = Color.Unspecified), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(np?.title ?: " ", style = Sloopy.listSub.copy(color = if (focused) Sloopy.textSoft else Sloopy.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (isCurrent) { LiveDot(); Spacer(Modifier.width(10.dp)) }
                                    if (c.id in favourites) Text("★", style = Sloopy.mono14.copy(color = Sloopy.amber), modifier = Modifier.padding(end = 10.dp))
                                    if (np != null) ProgressHairline(
                                        fraction = (now - np.startEpoch).toFloat() / (np.stopEpoch - np.startEpoch).coerceAtLeast(1),
                                        modifier = Modifier.width(80.dp), track = Color(0x30FFFFFF), fill = if (focused) Color.White else Sloopy.muted,
                                    )
                                }
                                Hairline(color = Sloopy.hair2)
                            }
                        }
                    }
                }
            }
        }
        Text("OK TUNE · HOLD OK FAVOURITE · BACK CLOSES", style = Sloopy.label.copy(color = Sloopy.dim), modifier = Modifier.align(Alignment.BottomStart).padding(start = 300.dp, bottom = 14.dp))
    }
}
