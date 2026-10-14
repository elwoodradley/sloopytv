package tv.sloopy.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.sloopy.data.db.ChannelEntity
import tv.sloopy.data.db.ProgrammeEntity
import tv.sloopy.ui.Groups
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.BACK_KEYS
import tv.sloopy.ui.common.ChannelLogo
import tv.sloopy.ui.common.Clock
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.inKeys
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.ProgressHairline
import tv.sloopy.ui.common.QuitPrompt
import tv.sloopy.ui.common.isDown
import tv.sloopy.ui.common.isKey
import tv.sloopy.ui.theme.Sloopy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SLOT = 228.dp            // 30 minutes
private val CHANNEL_COL = 330.dp
private val ROW_H = 64.dp
private val HEADER_H = 36.dp
private val RAIL_W = 300.dp
private val RAIL_ROW_H = 54.dp
private const val HOURS_BACK = 2L
private const val HOURS_FWD = 24L

private data class Focused(val channel: ChannelEntity, val prog: ProgrammeEntity?)

/** What the left panel shows. Back walks NONE → GROUPS → SECTIONS → switch-off, like TiviMate. */
private enum class Rail { NONE, GROUPS, SECTIONS }

private val SECTIONS = listOf(
    "live" to "Live TV", "guide" to "Guide", "movies" to "Movies", "series" to "Series",
    "history" to "History", "search" to "Search", "settings" to "Settings",
)

@UnstableApi
@Composable
fun GuideScreen(vm: SloopyViewModel, go: (String) -> Unit, onQuit: () -> Unit) {
    val provider by vm.active.collectAsState()
    val channels by vm.visibleChannels.collectAsState()
    val current by vm.currentChannel.collectAsState()
    val groupNow by vm.group.collectAsState()
    val categories by vm.liveCategories.collectAsState()
    val locked by vm.lockedGroups.collectAsState()
    val unlocked by vm.unlockedSession.collectAsState()
    val groups = remember(categories, locked, unlocked) {
        listOf(Groups.ALL to "All channels", Groups.FAV to "Favourites") + categories.filter { unlocked || it.id !in locked }.map { it.id to it.name }
    }
    val scope = rememberCoroutineScope()

    val nowEpoch = remember { System.currentTimeMillis() / 1000 }
    val winStart = remember(nowEpoch) { (nowEpoch / 1800) * 1800 - HOURS_BACK * 3600 }
    val winEnd = winStart + (HOURS_BACK + HOURS_FWD) * 3600
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    var focused by remember { mutableStateOf<Focused?>(null) }
    val nowFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    val lastRowFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    var focusedRow by remember { mutableStateOf(-1) }

    var rail by remember { mutableStateOf(if (vm.takeGuideOpensAtSections()) Rail.SECTIONS else Rail.NONE) }
    var quit by remember { mutableStateOf(false) }
    var pendingGroup by remember { mutableStateOf(false) }

    val dpPerSec: Float = SLOT.value / 1800f
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val nowPx = ((nowEpoch - winStart - 1800) * dpPerSec * density).toInt()

    // Picture → Back → grid; Back → groups panel on the current group; Back → sections; Back → switch off.
    // Focus parks on an invisible node while the rail is rebuilt, so the node being removed is
    // never the focused one (Compose would otherwise hand focus to the nearest grid cell).
    val park = remember { FocusRequester() }
    var parking by remember { mutableStateOf(false) }
    fun setRail(level: Rail) {
        parking = true
        runCatching { park.requestFocus() }
        rail = level
    }
    fun back() {
        when (rail) {
            Rail.NONE -> setRail(Rail.GROUPS)
            Rail.GROUPS -> setRail(Rail.SECTIONS)
            Rail.SECTIONS -> quit = true
        }
    }
    BackHandler { back() }

    // Put focus on the current channel's "now" cell (or the top row), with "now" one slot in from the left.
    suspend fun focusGrid() {
        val i = channels.indexOfFirst { it.id == current?.id }
        listState.scrollToItem((i - 3).coerceAtLeast(0))
        val targets = if (i >= 0) listOf(nowFocus, firstRowFocus) else listOf(firstRowFocus)
        for (t in targets) repeat(40) {
            delay(50)
            if (runCatching { t.requestFocus() }.getOrDefault(false)) {
                delay(120); hScroll.scrollTo(nowPx.coerceIn(0, hScroll.maxValue)); return
            }
        }
    }
    suspend fun focusRail() { repeat(40) { delay(50); if (runCatching { railFocus.requestFocus() }.getOrDefault(false)) return } }

    LaunchedEffect(rail, quit) { if (!quit) { if (rail == Rail.NONE) focusGrid() else focusRail(); parking = false } }
    // A new group's channels arrive a moment after the selection; land on them when they do.
    LaunchedEffect(channels) { if (pendingGroup) { pendingGroup = false; focused = null; focusGrid() } }

    var scrolledToNow by remember { mutableStateOf(false) }
    LaunchedEffect(hScroll.maxValue) {
        if (!scrolledToNow && hScroll.maxValue > 0) { hScroll.scrollTo(nowPx.coerceIn(0, hScroll.maxValue)); scrolledToNow = true }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(Sloopy.ground)
            .onPreviewKeyEvent { ev ->
                if (quit) return@onPreviewKeyEvent false
                // Taken here on key-up: something below the grid cells swallows Back's key-down and
                // drops focus instead, so the system BackHandler never saw every other press.
                if (ev.inKeys(BACK_KEYS)) { if (!ev.isDown) back(); return@onPreviewKeyEvent true }
                if (!ev.isDown) return@onPreviewKeyEvent false
                val inGrid = rail == Rail.NONE
                when {
                    ev.isKey(Key.Guide, Key.Menu) -> { setRail(if (inGrid) Rail.GROUPS else Rail.NONE); true }
                    // Left off the edge of the grid opens the groups, never a random rail row.
                    inGrid && ev.isKey(Key.DirectionLeft) && (hScroll.value == 0 || focused?.prog == null) -> { setRail(Rail.GROUPS); true }
                    !inGrid && ev.isKey(Key.DirectionLeft) -> true
                    !inGrid && ev.isKey(Key.DirectionRight) -> { setRail(if (rail == Rail.SECTIONS) Rail.GROUPS else Rail.NONE); true }
                    inGrid && ev.isKey(Key.DirectionUp) && focusedRow == 0 && channels.size > 1 -> {
                        scope.launch { listState.scrollToItem(channels.lastIndex); repeat(12) { delay(40); if (runCatching { lastRowFocus.requestFocus() }.getOrDefault(false)) return@launch } }; true
                    }
                    inGrid && ev.isKey(Key.DirectionDown) && focusedRow == channels.lastIndex && channels.size > 1 -> {
                        scope.launch { listState.scrollToItem(0); repeat(12) { delay(40); if (runCatching { firstRowFocus.requestFocus() }.getOrDefault(false)) return@launch } }; true
                    }
                    inGrid && ev.isKey(Key.ChannelUp, Key.PageUp) -> { scope.launch { listState.scrollToItem((listState.firstVisibleItemIndex - 10).coerceAtLeast(0)) }; true }
                    inGrid && ev.isKey(Key.ChannelDown, Key.PageDown) -> { scope.launch { listState.scrollToItem((listState.firstVisibleItemIndex + 10).coerceAtMost(channels.lastIndex.coerceAtLeast(0))) }; true }
                    else -> false
                }
            }
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 72.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(1.dp).focusRequester(park)
                    .focusProperties { up = FocusRequester.Cancel; down = FocusRequester.Cancel; left = FocusRequester.Cancel; right = FocusRequester.Cancel }
                    // Reached by the D-pad rather than by setRail: send focus straight back where it belongs.
                    .onFocusChanged { if (it.isFocused && !parking) scope.launch { if (rail == Rail.NONE) focusGrid() else focusRail() } }
                    .focusable()
            )
            Label("Guide  ·  " + dayLabel(nowEpoch), bright = true); Spacer(Modifier.weight(1f)); Clock()
        }

        DetailPanel(vm, focused, current, nowEpoch)

        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp)) {
        if (rail != Rail.NONE) {
            Column(Modifier.width(RAIL_W).fillMaxHeight()) {
                Box(Modifier.height(HEADER_H), contentAlignment = Alignment.CenterStart) { Label(if (rail == Rail.GROUPS) "Groups" else "SloopyTV") }
                Hairline()
                if (rail == Rail.GROUPS) GroupRail(groups, groupNow, railFocus) { id ->
                    if (id != groupNow) { pendingGroup = true; vm.selectGroup(id) }
                    setRail(Rail.NONE)
                } else SectionRail(railFocus) { route ->
                    when (route) { "guide" -> setRail(Rail.GROUPS); else -> go(route) }
                }
            }
            Spacer(Modifier.width(24.dp))
        }
        // While the rail is out the grid cannot take focus, so a rail rebuild never drops focus into a cell.
        Column(Modifier.fillMaxSize().clipToBounds().focusProperties { canFocus = rail == Rail.NONE }) {
            Row(Modifier.fillMaxWidth().height(HEADER_H)) {
                Box(Modifier.width(CHANNEL_COL).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Label(groups.firstOrNull { it.first == groupNow }?.second ?: "All channels", modifier = Modifier.padding(end = 12.dp))
                }
                Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll, enabled = false)) {
                    Row {
                        var t = winStart
                        while (t < winEnd) {
                            Box(Modifier.width(SLOT).fillMaxHeight().drawBehind { drawRect(Sloopy.hair, Offset(0f, 0f), Size(1.dp.toPx(), size.height)) }, contentAlignment = Alignment.CenterStart) {
                                Text(hhmm(t), style = Sloopy.mono16, modifier = Modifier.padding(start = 9.dp))
                            }
                            t += 1800
                        }
                    }
                    NowTag(nowEpoch, winStart, dpPerSec)
                }
            }
            Hairline()

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
                    val requesters = buildList {
                        if (c.id == current?.id) add(nowFocus)
                        if (i == 0) add(firstRowFocus)
                        if (i == channels.lastIndex) add(lastRowFocus)
                    }
                    GuideRow(
                        vm = vm, channel = c, isCurrent = c.id == current?.id,
                        winStart = winStart, winEnd = winEnd, nowEpoch = nowEpoch, dpPerSec = dpPerSec,
                        hScroll = hScroll, requesters = requesters,
                        onFocused = { p -> focusedRow = i; focused = Focused(c, p) },
                        onSelect = { p -> onSelect(vm, c, p, go) },
                    )
                }
            }
        }
        }
    }
    if (quit) QuitPrompt(onQuit = onQuit, onCancel = { quit = false })
    }
}

/** The provider's groups in the viewer's order. Opens on the group the grid is showing. */
@Composable
private fun GroupRail(groups: List<Pair<String, String>>, group: String, railFocus: FocusRequester, onPick: (String) -> Unit) {
    val state = rememberLazyListState()
    val cur = groups.indexOfFirst { it.first == group }.coerceAtLeast(0)
    LaunchedEffect(Unit) { state.scrollToItem((cur - 4).coerceAtLeast(0)) }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(groups, key = { _, g -> g.first }) { i, (id, name) ->
            RailItem(name, selected = i == cur, requester = if (i == cur) railFocus else null, first = i == 0, last = i == groups.lastIndex) { onPick(id) }
        }
    }
}

/** The top level: where to go from the guide. */
@Composable
private fun SectionRail(railFocus: FocusRequester, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SECTIONS.forEachIndexed { i, (route, label) ->
            RailItem(label, selected = route == "guide", requester = if (route == "guide") railFocus else null, first = i == 0, last = i == SECTIONS.lastIndex) { onPick(route) }
        }
    }
}

@Composable
private fun RailItem(name: String, selected: Boolean, requester: FocusRequester?, first: Boolean, last: Boolean, onClick: () -> Unit) {
    var fc by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.focus, contentColor = Sloopy.muted, focusedContentColor = Color.White),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth().height(RAIL_ROW_H)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            // Left/Right are handled by the screen; the ends never leak focus into the grid.
            .focusProperties {
                left = FocusRequester.Cancel; right = FocusRequester.Cancel
                if (first) up = FocusRequester.Cancel
                if (last) down = FocusRequester.Cancel
            }
            .onFocusChanged { fc = it.isFocused },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (selected) Sloopy.amber else Color.Transparent))
            Spacer(Modifier.width(14.dp))
            Text(name.uppercase(), style = Sloopy.titleRow.copy(color = if (selected || fc) Color.Unspecified else Sloopy.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@UnstableApi
private fun onSelect(vm: SloopyViewModel, c: ChannelEntity, p: ProgrammeEntity?, go: (String) -> Unit) {
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
    Box(Modifier.offset(x = x - 48.dp).height(HEADER_H), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.background(Sloopy.amber).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(hhmm(nowEpoch), style = Sloopy.mono14.copy(color = Sloopy.ground))
        }
    }
}

@UnstableApi
@Composable
private fun GuideRow(
    vm: SloopyViewModel,
    channel: ChannelEntity,
    isCurrent: Boolean,
    winStart: Long, winEnd: Long, nowEpoch: Long, dpPerSec: Float,
    hScroll: androidx.compose.foundation.ScrollState,
    requesters: List<FocusRequester>,
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

    Column {
        Row(Modifier.fillMaxWidth().height(ROW_H)) {
            Row(Modifier.width(CHANNEL_COL).fillMaxHeight().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(channel.number.toString(), style = Sloopy.mono16.copy(color = Sloopy.muted), modifier = Modifier.width(52.dp))
                ChannelLogo(channel.logo, channel.name, 40.dp)
                Spacer(Modifier.width(14.dp))
                Text(channel.name, style = Sloopy.listName.copy(fontSize = 21.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (isCurrent) LiveDot()
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)
                    .drawWithContent {
                        drawContent()
                        val x = (nowEpoch - winStart) * dpPerSec * density
                        drawRect(Sloopy.amber, Offset(x, 0f), Size(1.5f * density, size.height))
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
                        requesters = requesters, onFocus = { onFocused(null) }, onClick = { onSelect(null) },
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
                            requesters = if (isNow) requesters else emptyList(),
                            onFocus = { onFocused(p) },
                            onClick = { onSelect(p) },
                        )
                    }
                }
              }
            }
        }
        Hairline(color = Sloopy.hair2)
    }
}

@Composable
private fun Cell(
    title: String, time: String,
    x: androidx.compose.ui.unit.Dp, w: androidx.compose.ui.unit.Dp,
    past: Boolean, isNow: Boolean, archive: Boolean,
    requesters: List<FocusRequester>,
    onFocus: () -> Unit, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (past) Color(0xFF0E0E10) else if (isNow) Color(0xFF151518) else Sloopy.surface,
            focusedContainerColor = Sloopy.focus,
            contentColor = if (past) Sloopy.muted else Sloopy.text,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .offset(x = x)
            .padding(top = 2.dp, bottom = 2.dp, end = 1.5.dp)
            .width((w - 1.5.dp).coerceAtLeast(6.dp))
            .height(ROW_H - 4.dp)
            .then(requesters.fold(Modifier as Modifier) { m, r -> m.focusRequester(r) })
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text(title, style = Sloopy.cellTitle.copy(fontSize = 19.sp, color = Color.Unspecified, fontWeight = if (past) androidx.compose.ui.text.font.FontWeight.Normal else androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (past && archive) { Text("↺", style = Sloopy.mono14.copy(color = if (focused) Sloopy.textSoft else Sloopy.muted)); Spacer(Modifier.width(6.dp)) }
                Text(time, style = Sloopy.mono14.copy(color = if (focused) Sloopy.textSoft else Sloopy.muted), maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

/** The highlighted programme, or what is on the current channel until something is highlighted. */
@UnstableApi
@Composable
private fun DetailPanel(vm: SloopyViewModel, f: Focused?, current: ChannelEntity?, nowEpoch: Long) {
    val provider by vm.active.collectAsState()
    val fallback by produceState<Focused?>(null, current?.id, provider?.id) {
        val c = current ?: return@produceState
        val p = provider
        val epg = c.epgId
        val prog = if (p == null || epg == null) null else runCatching { vm.catalog.programmes(p.id, epg, nowEpoch, nowEpoch + 1) }.getOrNull()
            ?.firstOrNull { it.startEpoch <= nowEpoch && it.stopEpoch > nowEpoch }
        value = Focused(c, prog)
    }
    val shown = f ?: fallback
    val now = System.currentTimeMillis() / 1000
    Row(Modifier.fillMaxWidth().height(216.dp).padding(horizontal = 72.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (shown != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shown.channel.number.toString(), style = Sloopy.mono16)
                    Spacer(Modifier.width(12.dp))
                    ChannelLogo(shown.channel.logo, shown.channel.name, 27.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(shown.channel.name, style = Sloopy.listName.copy(color = Sloopy.muted))
                }
                val p = shown.prog
                Text(p?.title ?: shown.channel.name, style = Sloopy.titleHuge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                if (p != null) {
                    val left = when {
                        p.startEpoch <= now && p.stopEpoch > now -> "${(p.stopEpoch - now) / 60} min left"
                        p.stopEpoch <= now -> "Ended"
                        else -> "Starts in ${(p.startEpoch - now) / 60} min"
                    }
                    Row(Modifier.padding(top = 12.dp)) {
                        Text(hhmm(p.startEpoch) + " – " + hhmm(p.stopEpoch), style = Sloopy.meta)
                        Text("  · $left" + (p.category?.let { "  · $it" } ?: "") + (if (p.stopEpoch <= now && shown.channel.archive) "  · Catch-up available" else ""), style = Sloopy.meta.copy(color = Sloopy.muted))
                    }
                    Text(p.description ?: "No description in the guide feed for this programme.", style = Sloopy.bodyBig, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).width(780.dp))
                    Spacer(Modifier.weight(1f))
                    ProgressHairline(
                        fraction = if (p.stopEpoch <= now) 1f else if (p.startEpoch > now) 0f else (now - p.startEpoch).toFloat() / (p.stopEpoch - p.startEpoch).coerceAtLeast(1),
                        modifier = Modifier.width(360.dp), track = Color(0xFF2A2A2E), fill = Sloopy.text,
                    )
                }
            }
        }
        Spacer(Modifier.width(48.dp))
        // Live preview of what is playing now.
        Box(Modifier.width(356.dp).height(200.dp).background(Color.Black).drawBehind { drawRect(Sloopy.hair, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())) }) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlayerView(ctx).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK); subtitleView?.visibility = android.view.View.GONE; player = vm.player.player } },
                update = { it.player = vm.player.player },
            )
            if (current != null) Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveDot(); Spacer(Modifier.width(8.dp)); Text("NOW · ${current.number} ${current.name.uppercase()}", style = Sloopy.label.copy(color = Color.White.copy(alpha = 0.85f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private fun hhmm(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(HHMM)
private fun dayLabel(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DAY)
