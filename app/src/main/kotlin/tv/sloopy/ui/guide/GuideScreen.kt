package tv.sloopy.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import tv.sloopy.ui.GUIDE_HOURS_BACK
import tv.sloopy.ui.GUIDE_HOURS_FWD
import tv.sloopy.ui.Groups
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.BACK_KEYS
import tv.sloopy.ui.common.ChannelLogo
import tv.sloopy.ui.common.ChoicePrompt
import tv.sloopy.ui.common.Clock
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.inKeys
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.ProgressHairline
import tv.sloopy.ui.common.RAIL_W
import tv.sloopy.ui.common.RailHeader
import tv.sloopy.ui.common.RailItem
import tv.sloopy.ui.common.ScreenHeader
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

/** Focus inside the timeline must not scroll it: the guide moves the timeline on its own terms. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val NoBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

private data class Focused(val channel: ChannelEntity, val prog: ProgrammeEntity?)

/** What the left panel shows. Back walks NONE → GROUPS → SECTIONS → switch-off, like TiviMate. */
private enum class Rail { NONE, GROUPS, SECTIONS }

private val SECTIONS = listOf(
    "live" to "Live TV", "guide" to "Guide", "movies" to "Movies", "series" to "Series",
    "history" to "History", "recordings" to "Recordings", "search" to "Search", "settings" to "Settings",
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
    val winStart = remember(nowEpoch) { vm.guideWindowStart(nowEpoch) }
    val winEnd = winStart + (GUIDE_HOURS_BACK + GUIDE_HOURS_FWD) * 3600
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    var focused by remember { mutableStateOf<Focused?>(null) }
    val nowFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    val lastRowFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    var focusedRow by remember { mutableStateOf(-1) }
    // Vertical moves keep a cursor time and land on the cell under it in the next row, without
    // scrolling the timeline (TiviMate behaviour). Only Left and Right move time.
    val cellRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val rowProgs = remember { mutableMapOf<Long, List<ProgrammeEntity>>() }
    var cursorEpoch by remember { mutableStateOf(nowEpoch) }
    var verticalMove by remember { mutableStateOf(false) }
    var timelinePx by remember { mutableStateOf(0) }

    var rail by remember { mutableStateOf(if (vm.takeGuideOpensAtSections()) Rail.SECTIONS else Rail.NONE) }
    var quit by remember { mutableStateOf(false) }
    var pendingGroup by remember { mutableStateOf(false) }
    // Press-and-hold on a group picks it up; Up/Down move it; OK or Back drop it.
    var grabbed by remember { mutableStateOf<String?>(null) }
    var dropOnUp by remember { mutableStateOf(false) }
    // Menu on the remote: quick sort for the groups (on the rail) or the channels (in the grid).
    var sortMenu by remember { mutableStateOf<String?>(null) }
    var recordAsk by remember { mutableStateOf<Pair<ChannelEntity, ProgrammeEntity>?>(null) }
    val channelSort by vm.channelSort.collectAsState()

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

    fun viewStartEpoch(): Long = winStart + (hScroll.value / (dpPerSec * density)).toLong()
    /** The cell in [list] under the cursor time; else the next one; else the last; else the no-data cell. */
    fun pickKey(c: ChannelEntity, list: List<ProgrammeEntity>): String {
        val t = cursorEpoch
        val p = list.firstOrNull { it.startEpoch <= t && it.stopEpoch > t }
            ?: list.firstOrNull { it.startEpoch > t && it.startEpoch < winEnd }
            ?: list.lastOrNull { it.stopEpoch > winStart }
        return "${c.id}|${p?.startEpoch ?: "nodata"}"
    }
    suspend fun focusRow(target: Int) {
        val c = channels.getOrNull(target) ?: return
        verticalMove = true
        val vis = listState.layoutInfo.visibleItemsInfo
        if (vis.none { it.index == target }) {
            val below = target > (vis.lastOrNull()?.index ?: 0)
            listState.scrollToItem(if (below) (target - 8).coerceAtLeast(0) else target)
        }
        repeat(25) {
            val list = rowProgs[c.id]
            if (list != null) {
                val fr = cellRequesters[pickKey(c, list)]
                if (fr != null && runCatching { fr.requestFocus() }.getOrDefault(false)) return
            }
            delay(40)
        }
        verticalMove = false
    }
    /** After a horizontal move: the cursor follows the cell and the timeline scrolls only as far as needed. */
    fun cellFocused(p: ProgrammeEntity?) {
        if (verticalMove) { verticalMove = false; return }
        if (p == null) return
        val a = maxOf(p.startEpoch, winStart)
        cursorEpoch = maxOf(a, viewStartEpoch())
        val xs = ((a - winStart) * dpPerSec * density).toInt()
        val slot = (SLOT.value * density).toInt()
        val target = when {
            xs < hScroll.value -> xs
            timelinePx > 0 && xs > hScroll.value + timelinePx - slot -> xs - slot
            else -> return
        }
        scope.launch { hScroll.scrollTo(target.coerceIn(0, hScroll.maxValue)) }
    }

    LaunchedEffect(Unit) { if (vm.epgError.value != null) vm.refreshEpgNow() }
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
                if (quit || sortMenu != null || recordAsk != null) return@onPreviewKeyEvent false
                // Taken here on key-up: something below the grid cells swallows Back's key-down and
                // drops focus instead, so the system BackHandler never saw every other press.
                if (grabbed != null) {
                    when {
                        // Only a fresh press of OK drops it: a real remote repeats key-down while held, and the
                        // key-up of the long press that grabbed it must not count.
                        ev.isKey(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) -> {
                            if (ev.isDown) { if (ev.nativeKeyEvent.repeatCount == 0) dropOnUp = true }
                            else if (dropOnUp) { grabbed = null; dropOnUp = false }
                        }
                        ev.inKeys(BACK_KEYS) -> { if (!ev.isDown) grabbed = null }
                        ev.isDown && ev.isKey(Key.DirectionUp) -> vm.moveGroup(grabbed!!, -1)
                        ev.isDown && ev.isKey(Key.DirectionDown) -> vm.moveGroup(grabbed!!, 1)
                    }
                    return@onPreviewKeyEvent true
                }
                if (ev.inKeys(BACK_KEYS)) { if (!ev.isDown) back(); return@onPreviewKeyEvent true }
                if (!ev.isDown) return@onPreviewKeyEvent false
                val inGrid = rail == Rail.NONE
                when {
                    ev.isKey(Key.Guide) -> { setRail(if (inGrid) Rail.GROUPS else Rail.NONE); true }
                    ev.isKey(Key.Menu) -> { sortMenu = if (rail == Rail.GROUPS) "groups" else if (inGrid) "channels" else null; sortMenu != null }
                    // Left off the edge of the grid opens the groups, never a random rail row.
                    inGrid && ev.isKey(Key.DirectionLeft) && (hScroll.value == 0 || focused?.prog == null) -> { setRail(Rail.GROUPS); true }
                    !inGrid && ev.isKey(Key.DirectionLeft) -> true
                    !inGrid && ev.isKey(Key.DirectionRight) -> { setRail(if (rail == Rail.SECTIONS) Rail.GROUPS else Rail.NONE); true }
                    inGrid && channels.isNotEmpty() && ev.isKey(Key.DirectionUp) -> { scope.launch { focusRow(if (focusedRow <= 0) channels.lastIndex else focusedRow - 1) }; true }
                    inGrid && channels.isNotEmpty() && ev.isKey(Key.DirectionDown) -> { scope.launch { focusRow(if (focusedRow >= channels.lastIndex) 0 else focusedRow + 1) }; true }
                    inGrid && channels.isNotEmpty() && ev.isKey(Key.ChannelUp, Key.PageUp) -> { scope.launch { focusRow((focusedRow - 10).coerceAtLeast(0)) }; true }
                    inGrid && channels.isNotEmpty() && ev.isKey(Key.ChannelDown, Key.PageDown) -> { scope.launch { focusRow((focusedRow + 10).coerceAtMost(channels.lastIndex)) }; true }
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
            Label("Guide  ·  " + dayLabel(nowEpoch), bright = true)
            val epgError by vm.epgError.collectAsState()
            if (epgError != null) { Spacer(Modifier.width(28.dp)); Text("Guide feed refused ($epgError) · using the provider's channel guide instead", style = Sloopy.label.copy(color = Sloopy.amber), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.weight(1f)); Clock()
        }

        DetailPanel(vm, focused, current, nowEpoch)

        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp)) {
        if (rail != Rail.NONE) {
            Column(Modifier.width(RAIL_W).fillMaxHeight()) {
                RailHeader(
                    when { grabbed != null -> "Moving · Up/Down · OK drops"; rail == Rail.GROUPS -> "Groups · hold OK to move · Menu sorts"; else -> "SloopyTV" },
                    amber = grabbed != null,
                )
                if (rail == Rail.GROUPS) GroupRail(groups, groupNow, railFocus, grabbed, onGrab = { grabbed = it }) { id ->
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
                        onFocused = { p -> focusedRow = i; focused = Focused(c, p); cellFocused(p) },
                        onSelect = { p -> if (p != null && p.startEpoch > System.currentTimeMillis() / 1000) recordAsk = c to p else onSelect(vm, c, p, go) },
                        onProgs = { list -> rowProgs[c.id] = list },
                        register = { key, fr -> if (fr == null) cellRequesters.remove(key) else cellRequesters[key] = fr },
                        onTimelineWidth = { timelinePx = it },
                    )
                }
            }
        }
        }
    }
    if (quit) QuitPrompt(onQuit = onQuit, onCancel = { quit = false })
    when (sortMenu) {
        "groups" -> ChoicePrompt("SORT GROUPS", listOf("az" to "A to Z", "provider" to "Provider order"), onPick = { vm.sortGroups(it); sortMenu = null }, onCancel = { sortMenu = null })
        "channels" -> ChoicePrompt("SORT THIS GROUP", listOf("provider" to "Provider order", "number" to "By number", "name" to "By name"), current = channelSort, onPick = { vm.setChannelSort(it); sortMenu = null }, onCancel = { sortMenu = null })
    }
    recordAsk?.let { (c, p) ->
        ChoicePrompt("RECORD?", listOf("record" to "Record ${p.title.take(30)} · ${hhmm(p.startEpoch)}", "no" to "Not now"), onCancel = { recordAsk = null }, onPick = { if (it == "record") vm.scheduleRecording(c, p.title, p.startEpoch, p.stopEpoch); recordAsk = null })
    }
    LaunchedEffect(sortMenu, recordAsk) { if (sortMenu == null && recordAsk == null) { if (rail == Rail.NONE) focusGrid() else focusRail() } }
    }
}

/** The provider's groups in the viewer's order. Opens on the group the grid is showing. */
@Composable
private fun GroupRail(groups: List<Pair<String, String>>, group: String, railFocus: FocusRequester, grabbed: String?, onGrab: (String) -> Unit, onPick: (String) -> Unit) {
    val state = rememberLazyListState()
    val cur = groups.indexOfFirst { it.first == group }.coerceAtLeast(0)
    val focusIdx = if (grabbed != null) groups.indexOfFirst { it.first == grabbed } else cur
    LaunchedEffect(Unit) { state.scrollToItem((cur - 4).coerceAtLeast(0)) }
    // A moved group keeps focus: scroll it into view if it left, then re-request.
    LaunchedEffect(groups, grabbed) {
        if (grabbed == null || focusIdx < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
        if (focusIdx !in visible) state.scrollToItem((focusIdx - 4).coerceAtLeast(0))
        repeat(10) { delay(30); if (runCatching { railFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(groups, key = { _, g -> g.first }) { i, (id, name) ->
            val movable = id != Groups.ALL && id != Groups.FAV
            RailItem(
                name, selected = i == cur, requester = if (i == focusIdx) railFocus else null, first = i == 0, last = i == groups.lastIndex,
                grabbed = id == grabbed, onLongClick = if (movable) ({ onGrab(id) }) else null,
            ) { onPick(id) }
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
    onProgs: (List<ProgrammeEntity>) -> Unit,
    register: (String, FocusRequester?) -> Unit,
    onTimelineWidth: (Int) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // Served from the view model's cache when it has the row, so a re-entry draws on the first frame.
    val key = tv.sloopy.data.channelEpgKey(channel)
    val progs by produceState<List<ProgrammeEntity>?>(initialValue = vm.cachedProgrammes(key, winStart), key, winStart) {
        value = vm.programmesFor(channel, winStart)
    }
    LaunchedEffect(progs) { progs?.let { onProgs(it) } }

    Column {
        Row(Modifier.fillMaxWidth().height(ROW_H)) {
            Row(Modifier.width(CHANNEL_COL).fillMaxHeight().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(channel.number.toString(), style = Sloopy.mono16.copy(color = Sloopy.muted), modifier = Modifier.width(52.dp))
                ChannelLogo(channel.logo, channel.name, 40.dp)
                Spacer(Modifier.width(14.dp))
                Text(channel.name, style = Sloopy.listName.copy(fontSize = 21.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (isCurrent) LiveDot()
            }
            Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { onTimelineWidth(it.width) }) {
             // Focus never scrolls the timeline by itself: the screen scrolls it for horizontal moves only.
             CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) {
              Box(
                Modifier.fillMaxSize().horizontalScroll(hScroll)
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
                    // Starts where the timeline opens (half an hour before now), so focusing it does not
                    // drag the view back to the start of the window.
                    val x0 = (nowEpoch - winStart - 1800).coerceAtLeast(0)
                    Cell(
                        title = "No guide data", time = "", x = (x0 * dpPerSec).dp, w = (((winEnd - winStart - x0) * dpPerSec).dp), past = true, isNow = false, archive = false,
                        requesters = requesters, cellKey = "${channel.id}|nodata", register = register,
                        onFocus = { onFocused(null) }, onClick = { onSelect(null) },
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
                            cellKey = "${channel.id}|${p.startEpoch}", register = register,
                            onFocus = { onFocused(p) },
                            onClick = { onSelect(p) },
                        )
                    }
                }
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
    cellKey: String, register: (String, FocusRequester?) -> Unit,
    onFocus: () -> Unit, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val own = remember { FocusRequester() }
    DisposableEffect(cellKey) { register(cellKey, own); onDispose { register(cellKey, null) } }
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
            .focusRequester(own)
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
    val fallback by produceState<Focused?>(null, current?.id) {
        val c = current ?: return@produceState
        val prog = vm.programmesFor(c, vm.guideWindowStart(nowEpoch)).firstOrNull { it.startEpoch <= nowEpoch && it.stopEpoch > nowEpoch }
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

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private fun hhmm(epoch: Long) = tv.sloopy.ui.common.TimeFmt.hhmm(epoch)
private fun dayLabel(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DAY)
