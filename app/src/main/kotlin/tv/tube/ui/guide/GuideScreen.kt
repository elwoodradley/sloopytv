package tv.tube.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.tube.data.db.ChannelEntity
import tv.tube.data.db.ProgrammeEntity
import tv.tube.ui.TubeViewModel
import tv.tube.ui.common.ChannelLogo
import tv.tube.ui.common.Hairline
import tv.tube.ui.common.Label
import tv.tube.ui.common.LiveDot
import tv.tube.ui.common.ProgressHairline
import tv.tube.ui.common.TopNav
import tv.tube.ui.common.isDown
import tv.tube.ui.common.isKey
import tv.tube.ui.theme.Tube
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SLOT = 228.dp            // 30 minutes
private val CHANNEL_COL = 270.dp
private val ROW_H = 54.dp
private val HEADER_H = 33.dp
private const val HOURS_BACK = 2L
private const val HOURS_FWD = 24L

private data class Focused(val channel: ChannelEntity, val prog: ProgrammeEntity?)

@UnstableApi
@Composable
fun GuideScreen(vm: TubeViewModel, go: (String) -> Unit, onBack: () -> Unit) {
    val provider by vm.active.collectAsState()
    val channels by vm.visibleChannels.collectAsState()
    val current by vm.currentChannel.collectAsState()
    val scope = rememberCoroutineScope()

    val nowEpoch = remember { System.currentTimeMillis() / 1000 }
    val winStart = remember(nowEpoch) { (nowEpoch / 1800) * 1800 - HOURS_BACK * 3600 }
    val winEnd = winStart + (HOURS_BACK + HOURS_FWD) * 3600
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    var focused by remember { mutableStateOf<Focused?>(null) }
    val nowFocus = remember { FocusRequester() }
    var didInitialFocus by remember { mutableStateOf(false) }

    val dpPerSec: Float = SLOT.value / 1800f
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    BackHandler { onBack() }

    // The grid is wider than the screen only once rows exist, so wait for a real max scroll.
    var scrolledToNow by remember { mutableStateOf(false) }
    LaunchedEffect(hScroll.maxValue) {
        if (!scrolledToNow && hScroll.maxValue > 0) {
            hScroll.scrollTo(((nowEpoch - winStart - 1800) * dpPerSec * density).toInt().coerceIn(0, hScroll.maxValue))
            scrolledToNow = true
        }
    }

    // Scroll so "now" sits one slot in from the left, then focus the current channel's now cell.
    LaunchedEffect(channels.size, provider?.id) {
        if (channels.isEmpty() || didInitialFocus) return@LaunchedEffect
        val i = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        listState.scrollToItem((i - 3).coerceAtLeast(0))
        didInitialFocus = true
    }

    Column(
        Modifier.fillMaxSize().background(Tube.ground)
            .onPreviewKeyEvent { ev ->
                if (!ev.isDown) return@onPreviewKeyEvent false
                when {
                    ev.isKey(Key.Guide) -> { onBack(); true }
                    ev.isKey(Key.ChannelUp, Key.PageUp) -> { scope.launch { listState.scrollToItem((listState.firstVisibleItemIndex - 10).coerceAtLeast(0)) }; true }
                    ev.isKey(Key.ChannelDown, Key.PageDown) -> { scope.launch { listState.scrollToItem((listState.firstVisibleItemIndex + 10).coerceAtMost(channels.lastIndex.coerceAtLeast(0))) }; true }
                    else -> false
                }
            }
    ) {
        TopNav(current = "guide", onSelect = go, modifier = Modifier.padding(top = 10.dp))

        DetailPanel(vm, focused, current)

        // Grid
        Column(Modifier.fillMaxSize().padding(horizontal = 72.dp).clipToBounds()) {
            // Header
            Row(Modifier.fillMaxWidth().height(HEADER_H)) {
                Box(Modifier.width(CHANNEL_COL).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Label(dayLabel(nowEpoch))
                }
                Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll, enabled = false)) {
                    Row {
                        var t = winStart
                        while (t < winEnd) {
                            Box(Modifier.width(SLOT).fillMaxHeight().drawBehind { drawRect(Tube.hair, Offset(0f, 0f), Size(1.dp.toPx(), size.height)) }, contentAlignment = Alignment.CenterStart) {
                                Text(hhmm(t), style = Tube.mono14, modifier = Modifier.padding(start = 9.dp))
                            }
                            t += 1800
                        }
                    }
                    NowTag(nowEpoch, winStart, dpPerSec)
                }
            }
            Hairline()

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(channels, key = { _, c -> c.id }) { _, c ->
                    GuideRow(
                        vm = vm, channel = c, isCurrent = c.id == current?.id,
                        winStart = winStart, winEnd = winEnd, nowEpoch = nowEpoch, dpPerSec = dpPerSec,
                        hScroll = hScroll,
                        nowFocus = if (c.id == current?.id || (current == null && c == channels.firstOrNull())) nowFocus else null,
                        onFocused = { p -> focused = Focused(c, p) },
                        onSelect = { p -> onSelect(vm, c, p, nowEpoch, go) },
                    )
                }
            }
        }
    }
}

@UnstableApi
private fun onSelect(vm: TubeViewModel, c: ChannelEntity, p: ProgrammeEntity?, now: Long, go: (String) -> Unit) {
    val nowSec = System.currentTimeMillis() / 1000
    when {
        p == null || (p.startEpoch <= nowSec && p.stopEpoch > nowSec) -> { vm.tune(c); go("live") }
        p.stopEpoch <= nowSec && c.archive -> { vm.playCatchup(c, p.title, p.startEpoch, p.stopEpoch); go("live") }
        p.stopEpoch <= nowSec -> vm.toast("No catch-up on this channel")
        else -> vm.toast("Starts at ${hhmm(p.startEpoch)}")
    }
}

@Composable
private fun NowTag(nowEpoch: Long, winStart: Long, dpPerSec: Float) {
    val x = ((nowEpoch - winStart) * dpPerSec).dp
    Box(Modifier.offset(x = x - 44.dp).height(HEADER_H), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.background(Tube.amber).padding(horizontal = 5.dp, vertical = 2.dp)) {
            Text(hhmm(nowEpoch), style = Tube.mono14.copy(color = Tube.ground, fontSize = Tube.label.fontSize))
        }
    }
}

@UnstableApi
@Composable
private fun GuideRow(
    vm: TubeViewModel,
    channel: ChannelEntity,
    isCurrent: Boolean,
    winStart: Long, winEnd: Long, nowEpoch: Long, dpPerSec: Float,
    hScroll: androidx.compose.foundation.ScrollState,
    nowFocus: FocusRequester?,
    onFocused: (ProgrammeEntity?) -> Unit,
    onSelect: (ProgrammeEntity?) -> Unit,
) {
    val provider by vm.active.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val progs by produceState<List<ProgrammeEntity>?>(initialValue = null, channel.epgId, provider?.id, winStart) {
        val p = provider
        val epg = channel.epgId
        value = if (p == null || epg == null) emptyList() else runCatching { vm.catalog.programmes(p.id, epg, winStart, winEnd) }.getOrDefault(emptyList())
    }

    LaunchedEffect(progs, nowFocus) {
        if (nowFocus != null && progs != null) {
            kotlinx.coroutines.delay(50)
            runCatching { nowFocus.requestFocus() }
            // Focus pulls the cell into view at the right edge; put "now" one slot in from the left instead.
            kotlinx.coroutines.delay(120)
            hScroll.scrollTo(((nowEpoch - winStart - 1800) * dpPerSec * density).toInt().coerceIn(0, hScroll.maxValue))
        }
    }

    Column {
        Row(Modifier.fillMaxWidth().height(ROW_H)) {
            Row(Modifier.width(CHANNEL_COL).fillMaxHeight().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(channel.number.toString(), style = Tube.mono14, modifier = Modifier.width(44.dp))
                ChannelLogo(channel.logo, channel.name, 33.dp)
                Spacer(Modifier.width(12.dp))
                Text(channel.name, style = Tube.listName.copy(fontSize = Tube.cellTitle.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (isCurrent) LiveDot()
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)
                    .drawWithContent {
                        drawContent()
                        val x = (nowEpoch - winStart) * dpPerSec * density
                        drawRect(Tube.amber, Offset(x, 0f), Size(1.5f * density, size.height))
                    }
            ) {
              // Explicit width so the shared scroll state knows how far the timeline extends.
              Box(Modifier.width(((winEnd - winStart) * dpPerSec).dp).fillMaxHeight()) {
                val list = progs
                if (list == null) {
                    // loading: nothing yet
                } else if (list.isEmpty()) {
                    Cell(
                        title = "No guide data", time = "", x = 0.dp, w = (((winEnd - winStart) * dpPerSec).dp), past = true, isNow = false, archive = false,
                        focusRequester = nowFocus, onFocus = { onFocused(null) }, onClick = { onSelect(null) },
                    )
                } else {
                    for (p in list) {
                        val a = maxOf(p.startEpoch, winStart)
                        val b = minOf(p.stopEpoch, winEnd)
                        if (b <= a) continue
                        val isNow = p.startEpoch <= nowEpoch && p.stopEpoch > nowEpoch
                        Cell(
                            title = p.title + (p.episode?.let { "  $it" } ?: ""),
                            time = hhmm(p.startEpoch) + " – " + hhmm(p.stopEpoch),
                            x = ((a - winStart) * dpPerSec).dp,
                            w = ((b - a) * dpPerSec).dp,
                            past = p.stopEpoch <= nowEpoch, isNow = isNow, archive = channel.archive,
                            focusRequester = if (isNow) nowFocus else null,
                            onFocus = { onFocused(p) },
                            onClick = { onSelect(p) },
                        )
                    }
                }
              }
            }
        }
        Hairline(color = Tube.hair2)
    }
}

@Composable
private fun Cell(
    title: String, time: String,
    x: androidx.compose.ui.unit.Dp, w: androidx.compose.ui.unit.Dp,
    past: Boolean, isNow: Boolean, archive: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (past) Color(0xFF0E0E10) else if (isNow) Color(0xFF151518) else Tube.surface,
            focusedContainerColor = Tube.focus,
            contentColor = if (past) Tube.muted else Tube.text,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .offset(x = x)
            .padding(top = 2.dp, bottom = 2.dp, end = 1.5.dp)
            .width((w - 1.5.dp).coerceAtLeast(6.dp))
            .height(ROW_H - 4.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text(title, style = Tube.cellTitle.copy(color = Color.Unspecified, fontWeight = if (past) androidx.compose.ui.text.font.FontWeight.Normal else androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (past && archive) { Text("↺", style = Tube.mono14.copy(color = if (focused) Tube.textSoft else Tube.muted)); Spacer(Modifier.width(6.dp)) }
                Text(time, style = Tube.mono14.copy(color = if (focused) Tube.textSoft else Tube.muted, fontSize = Tube.label.fontSize), maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

@UnstableApi
@Composable
private fun DetailPanel(vm: TubeViewModel, f: Focused?, current: ChannelEntity?) {
    val now = System.currentTimeMillis() / 1000
    Row(Modifier.fillMaxWidth().height(228.dp).padding(horizontal = 72.dp, vertical = 12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (f != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.channel.number.toString(), style = Tube.mono16)
                    Spacer(Modifier.width(12.dp))
                    ChannelLogo(f.channel.logo, f.channel.name, 27.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(f.channel.name, style = Tube.listName.copy(color = Tube.muted))
                }
                val p = f.prog
                Text(p?.title ?: f.channel.name, style = Tube.titleHuge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                if (p != null) {
                    val left = when {
                        p.startEpoch <= now && p.stopEpoch > now -> "${(p.stopEpoch - now) / 60} min left"
                        p.stopEpoch <= now -> "Ended"
                        else -> "Starts in ${(p.startEpoch - now) / 60} min"
                    }
                    Row(Modifier.padding(top = 12.dp)) {
                        Text(hhmm(p.startEpoch) + " – " + hhmm(p.stopEpoch), style = Tube.meta)
                        Text("  · $left" + (p.category?.let { "  · $it" } ?: "") + (if (p.stopEpoch <= now && f.channel.archive) "  · Catch-up available" else ""), style = Tube.meta.copy(color = Tube.muted))
                    }
                    Text(p.description ?: "No description in the guide feed for this programme.", style = Tube.bodyBig, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).width(780.dp))
                    Spacer(Modifier.weight(1f))
                    ProgressHairline(
                        fraction = if (p.stopEpoch <= now) 1f else if (p.startEpoch > now) 0f else (now - p.startEpoch).toFloat() / (p.stopEpoch - p.startEpoch).coerceAtLeast(1),
                        modifier = Modifier.width(360.dp), track = Color(0xFF2A2A2E), fill = Tube.text,
                    )
                }
            }
        }
        Spacer(Modifier.width(48.dp))
        // Live preview of what is playing now.
        Box(Modifier.width(384.dp).height(216.dp).background(Color.Black).drawBehind { drawRect(Tube.hair, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())) }) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlayerView(ctx).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK); subtitleView?.visibility = android.view.View.GONE; player = vm.player.player } },
                update = { it.player = vm.player.player },
            )
            if (current != null) Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveDot(); Spacer(Modifier.width(8.dp)); Text("NOW · ${current.number} ${current.name.uppercase()}", style = Tube.label.copy(color = Color.White.copy(alpha = 0.85f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private fun hhmm(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(HHMM)
private fun dayLabel(epoch: Long) = "Today · " + Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DAY)
