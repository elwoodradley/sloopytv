package tv.sloopy.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.sloopy.data.db.ChannelEntity
import tv.sloopy.data.db.ProgrammeEntity
import tv.sloopy.ui.GUIDE_BAND_BACK
import tv.sloopy.ui.GUIDE_BAND_FWD
import tv.sloopy.ui.GUIDE_HOURS_BACK
import tv.sloopy.ui.GUIDE_HOURS_FWD
import tv.sloopy.ui.Groups
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.BACK_KEYS
import tv.sloopy.ui.common.ChannelLogo
import tv.sloopy.ui.common.ChoicePrompt
import tv.sloopy.ui.common.Clock
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.NavStrip
import tv.sloopy.ui.common.ProgressHairline
import tv.sloopy.ui.common.QuitPrompt
import tv.sloopy.ui.common.RAIL_W
import tv.sloopy.ui.common.RailHeader
import tv.sloopy.ui.common.RailItem
import tv.sloopy.ui.common.inKeys
import tv.sloopy.ui.common.isDown
import tv.sloopy.ui.common.isKey
import tv.sloopy.ui.theme.Sloopy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SLOT = 228.dp            // 30 minutes
private val CHANNEL_COL = 300.dp
private val ROW_H = 64.dp
private val HEADER_H = 36.dp

private data class Focused(val channel: ChannelEntity, val prog: ProgrammeEntity?)

/** Where the cursor lives. Back walks NONE (grid) → GROUPS → STRIP (the icon column) → switch-off. */
private enum class Rail { NONE, GROUPS, STRIP }

/**
 * The guide. The grid is drawn, not composed: each row paints its cells onto a canvas and the
 * whole grid is one focusable node that moves a cursor (row + time) by itself, the way TiviMate
 * does it. Nothing in the timeline is a widget, so a group with ten thousand channels costs the
 * same to show as one with ten, and a held key moves the cursor on every repeat.
 */
@UnstableApi
@Composable
fun GuideScreen(vm: SloopyViewModel, go: (String) -> Unit, onQuit: () -> Unit) {
    val channels by vm.visibleChannels.collectAsState()
    val current by vm.currentChannel.collectAsState()
    val groupNow by vm.group.collectAsState()
    val categories by vm.liveCategories.collectAsState()
    val locked by vm.lockedGroups.collectAsState()
    val unlocked by vm.unlockedSession.collectAsState()
    val hiddenGroups by vm.hiddenGroups.collectAsState()
    val groups = remember(categories, locked, unlocked, hiddenGroups) {
        listOf(Groups.ALL to "All channels", Groups.FAV to "Favourites") + categories.filter { (unlocked || it.id !in locked) && it.id !in hiddenGroups }.map { it.id to tv.sloopy.core.model.Names.clean(it.name) }
    }
    val scope = rememberCoroutineScope()

    val nowEpoch = remember { System.currentTimeMillis() / 1000 }
    val winStart = remember(nowEpoch) { vm.guideWindowStart(nowEpoch) }
    val winEnd = winStart + (GUIDE_HOURS_BACK + GUIDE_HOURS_FWD) * 3600
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    val railFocus = remember { FocusRequester() }
    val stripFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    var groupMenu by remember { mutableStateOf<String?>(null) }

    // The cursor: a row and a time. Up and Down keep the time and land on the cell under it in
    // the next row (TiviMate behaviour); only Left and Right move the time.
    var cursorRow by remember { mutableStateOf(0) }
    var cursorEpoch by remember { mutableStateOf(nowEpoch) }
    var timelinePx by remember { mutableStateOf(0) }

    var rail by remember { mutableStateOf(if (vm.takeGuideOpensAtSections()) Rail.STRIP else Rail.NONE) }
    var quit by remember { mutableStateOf(false) }
    var pendingGroup by remember { mutableStateOf(false) }
    var hoverGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hoverGroup) { val g = hoverGroup ?: return@LaunchedEffect; delay(220); if (g != groupNow) vm.selectGroup(g) }
    var grabbed by remember { mutableStateOf<String?>(null) }
    var dropOnUp by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf<String?>(null) }
    var cellMenu by remember { mutableStateOf<Pair<ChannelEntity, ProgrammeEntity?>?>(null) }
    var recordFor by remember { mutableStateOf<Pair<ChannelEntity, ProgrammeEntity?>?>(null) }   // the Record row's second step
    val favIds by vm.favouriteIds.collectAsState()
    val reportsOn by vm.reportsEnabled.collectAsState()
    val dupesNow by vm.dupes.collectAsState()
    var copiesFor by remember { mutableStateOf<ChannelEntity?>(null) }
    val channelSort by vm.channelSort.collectAsState()

    val dpPerSec: Float = SLOT.value / 1800f
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val pxPerSec = dpPerSec * density
    val nowPx = ((nowEpoch - winStart - 1800) * pxPerSec).toInt()
    val slotPx = (SLOT.value * density).toInt()

    // The listings in memory: this group, six hours behind the view and six ahead, widened as the
    // view scrolls; rows around the view, more as the list scrolls.
    val guide by vm.groupGuide.collectAsState()
    val viewHour by remember { derivedStateOf { (winStart + (hScroll.value / pxPerSec).toLong()) / 3600 } }
    val rowBucket by remember { derivedStateOf { listState.firstVisibleItemIndex / 30 } }
    LaunchedEffect(channels, groupNow, viewHour, rowBucket) {
        if (channels.isEmpty()) return@LaunchedEffect
        val v = viewHour * 3600
        vm.ensureGuide(groupNow, channels, rowBucket * 30 - 40, rowBucket * 30 + 90, maxOf(winStart, v - GUIDE_BAND_BACK * 3600), minOf(winEnd, v + GUIDE_BAND_FWD * 3600), near = listState.firstVisibleItemIndex + 4)
    }
    fun progsOf(c: ChannelEntity): List<ProgrammeEntity>? = guide?.takeIf { it.key == groupNow }?.rows?.get(tv.sloopy.data.channelEpgKey(c))
    /** The cell under the cursor time; else the next one; else the last in the window; else none. */
    fun cellAt(list: List<ProgrammeEntity>?, t: Long): ProgrammeEntity? {
        if (list == null) return null
        return list.firstOrNull { it.startEpoch <= t && it.stopEpoch > t }
            ?: list.firstOrNull { it.startEpoch > t && it.startEpoch < winEnd }
            ?: list.lastOrNull { it.stopEpoch > winStart }
    }
    val focused: Focused? = remember(cursorRow, cursorEpoch, guide, channels, groupNow) {
        channels.getOrNull(cursorRow)?.let { c -> Focused(c, cellAt(progsOf(c), cursorEpoch)) }
    }

    fun viewStartEpoch(): Long = winStart + (hScroll.value / pxPerSec).toLong()
    fun scrollTimelineTo(px: Int) { scope.launch { hScroll.scrollTo(px.coerceIn(0, hScroll.maxValue)) } }
    /** Keep the cursor's row on screen, a little in from the edge. */
    fun showRow(i: Int) {
        val vis = listState.layoutInfo.visibleItemsInfo
        if (vis.isEmpty()) { scope.launch { listState.scrollToItem((i - 3).coerceAtLeast(0)) }; return }
        val first = vis.first().index; val last = vis.last().index
        val count = (last - first + 1).coerceAtLeast(1)
        when {
            i <= first -> scope.launch { listState.scrollToItem((i - 1).coerceAtLeast(0)) }
            i >= last - 1 -> scope.launch { listState.scrollToItem((i - count + 3).coerceAtLeast(0)) }
        }
    }
    fun moveRow(i: Int) { if (channels.isEmpty()) return; cursorRow = i.coerceIn(0, channels.lastIndex); showRow(cursorRow) }
    /** Left or Right: the previous or next programme in the row; the timeline follows only as far as it must. */
    fun moveTime(dir: Int): Boolean {
        val c = channels.getOrNull(cursorRow) ?: return false
        val list = progsOf(c) ?: return false
        val cur = cellAt(list, cursorEpoch) ?: return false
        val next = (if (dir > 0) list.firstOrNull { it.startEpoch >= cur.stopEpoch && it.startEpoch < winEnd } else list.lastOrNull { it.stopEpoch <= cur.startEpoch && it.stopEpoch > winStart })
            ?: return false
        val a = maxOf(next.startEpoch, winStart)
        val xs = ((a - winStart) * pxPerSec).toInt()
        when {
            xs < hScroll.value -> scrollTimelineTo(xs)
            timelinePx > 0 && xs > hScroll.value + timelinePx - slotPx -> scrollTimelineTo(xs - slotPx)
        }
        cursorEpoch = a
        return true
    }
    // The cursor time stays inside the view after a vertical move, so a long programme that
    // started hours ago is still picked by where you are looking, not where it began.
    LaunchedEffect(cursorRow) { val v = viewStartEpoch(); if (cursorEpoch < v) cursorEpoch = v }

    fun setRail(level: Rail) { rail = level }
    fun back() {
        when (rail) {
            Rail.NONE -> setRail(Rail.GROUPS)
            Rail.GROUPS -> setRail(Rail.STRIP)
            Rail.STRIP -> quit = true
        }
    }
    BackHandler { back() }

    /** The cursor goes to the current channel's row at "now" (or stays where it was), and the grid takes focus. */
    suspend fun focusGrid() {
        val i = channels.indexOfFirst { it.id == current?.id }
        if (i >= 0) { cursorRow = i; cursorEpoch = nowEpoch; listState.scrollToItem((i - 3).coerceAtLeast(0)); scrollTimelineTo(nowPx) }
        else if (cursorRow !in channels.indices) { cursorRow = 0; cursorEpoch = nowEpoch; scrollTimelineTo(nowPx) }
        repeat(40) { if (runCatching { gridFocus.requestFocus() }.getOrDefault(false)) return; delay(50) }
    }
    suspend fun focusRail() { val t = if (rail == Rail.STRIP) stripFocus else railFocus; repeat(40) { delay(50); if (runCatching { t.requestFocus() }.getOrDefault(false)) return } }

    LaunchedEffect(Unit) { if (vm.epgError.value != null) vm.refreshEpgNow() }
    LaunchedEffect(rail, quit) { if (!quit) { if (rail == Rail.NONE) focusGrid() else focusRail() } }
    // A new group's channels arrive a moment after the selection; land on them when they do.
    LaunchedEffect(channels) { if (pendingGroup) { pendingGroup = false; focusGrid() } else if (cursorRow > channels.lastIndex) cursorRow = channels.lastIndex.coerceAtLeast(0) }
    var scrolledToNow by remember { mutableStateOf(false) }
    LaunchedEffect(hScroll.maxValue) { if (!scrolledToNow && hScroll.maxValue > 0) { hScroll.scrollTo(nowPx.coerceIn(0, hScroll.maxValue)); scrolledToNow = true } }

    // OK: a press selects; holding it opens the menu (the repeat of a held key is the signal).
    var okHeld by remember { mutableStateOf(false) }
    var okDownAt by remember { mutableStateOf(0L) }
    fun select(c: ChannelEntity, p: ProgrammeEntity?) { if (p != null && p.startEpoch > System.currentTimeMillis() / 1000) cellMenu = c to p else onSelect(vm, c, p, go) }

    val measurer = rememberTextMeasurer()

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(Sloopy.ground)
            .onPreviewKeyEvent { ev ->
                if (sortMenu != null || cellMenu != null || groupMenu != null || copiesFor != null || recordFor != null) {
                    if (ev.inKeys(BACK_KEYS)) { if (!ev.isDown) { sortMenu = null; cellMenu = null; groupMenu = null; copiesFor = null; recordFor = null }; return@onPreviewKeyEvent true }
                    return@onPreviewKeyEvent false
                }
                if (quit) return@onPreviewKeyEvent false
                if (grabbed != null) {
                    when {
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
                val inGrid = rail == Rail.NONE
                if (inGrid && ev.isKey(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                    val c = channels.getOrNull(cursorRow)
                    if (ev.isDown) {
                        if (ev.nativeKeyEvent.repeatCount == 0) { okHeld = false; okDownAt = ev.nativeKeyEvent.eventTime }
                        // A repeat after the long-press delay means OK is being held: the menu, not a select.
                        else if (!okHeld && ev.nativeKeyEvent.eventTime - okDownAt >= 350) { okHeld = true; if (c != null) cellMenu = c to cellAt(progsOf(c), cursorEpoch) }
                    } else if (!okHeld && c != null) select(c, cellAt(progsOf(c), cursorEpoch))
                    return@onPreviewKeyEvent true
                }
                if (!ev.isDown) return@onPreviewKeyEvent false
                when {
                    ev.isKey(Key.Guide) -> { setRail(if (inGrid) Rail.GROUPS else Rail.NONE); true }
                    ev.isKey(Key.Menu) -> { sortMenu = if (rail == Rail.GROUPS) "groups" else if (inGrid) "channels" else null; sortMenu != null }
                    inGrid && ev.isKey(Key.DirectionLeft) -> { if (!moveTime(-1)) setRail(Rail.GROUPS); true }
                    inGrid && ev.isKey(Key.DirectionRight) -> { moveTime(1); true }
                    !inGrid && ev.isKey(Key.DirectionLeft) -> { if (rail == Rail.GROUPS) setRail(Rail.STRIP); true }
                    !inGrid && ev.isKey(Key.DirectionRight) -> { setRail(if (rail == Rail.STRIP) Rail.GROUPS else Rail.NONE); true }
                    inGrid && channels.isNotEmpty() && ev.isKey(Key.DirectionUp) -> { moveRow(if (cursorRow <= 0) channels.lastIndex else cursorRow - 1); true }
                    inGrid && channels.isNotEmpty() && ev.isKey(Key.DirectionDown) -> { moveRow(if (cursorRow >= channels.lastIndex) 0 else cursorRow + 1); true }
                    inGrid && channels.isNotEmpty() && ev.isKey(Key.ChannelUp, Key.PageUp) -> { moveRow(cursorRow - 10); true }
                    inGrid && channels.isNotEmpty() && ev.isKey(Key.ChannelDown, Key.PageDown) -> { moveRow(cursorRow + 10); true }
                    else -> false
                }
            }
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 72.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("Guide  ·  " + dayLabel(nowEpoch), bright = true)
            val epgError by vm.epgError.collectAsState()
            val epgNote by vm.catalog.epgNote.collectAsState()
            if (epgError != null) { Spacer(Modifier.width(28.dp)); Text("Guide file refused · " + epgNote.ifBlank { epgError!! } + " · filling from the provider's channel tables", style = Sloopy.label.copy(color = Sloopy.amber), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(1100.dp)) }
            Spacer(Modifier.weight(1f))
            val sweep by vm.sweepProgress.collectAsState()
            val refreshing by vm.catalog.refresh.collectAsState()
            val dl = refreshing as? tv.sloopy.data.RefreshState.Running
            if (dl != null && dl.step == "Guide") {
                Text("Downloading TV guide" + (if (dl.count > 0) " · ${"%,d".format(dl.count)} programmes" else ""), style = Sloopy.label.copy(color = Sloopy.amber)); Spacer(Modifier.width(28.dp))
            } else if (sweep != null && sweep!!.second > 0) {
                val (d, t) = sweep!!
                Column(horizontalAlignment = Alignment.End) {
                    Text("Loading TV guide · ${"%,d".format(d)} / ${"%,d".format(t)}", style = Sloopy.label.copy(color = Sloopy.amber))
                    ProgressHairline(d.toFloat() / t, modifier = Modifier.padding(top = 4.dp).width(220.dp), fill = Sloopy.amber)
                }
                Spacer(Modifier.width(28.dp))
            }
            Clock()
        }

        DetailPanel(vm, focused, current, nowEpoch)

        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp)) {
        NavStrip("guide", go = { route -> if (route == "guide") setRail(Rail.GROUPS) else go(route) }, requester = stripFocus, canFocus = rail == Rail.STRIP)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.width(RAIL_W).fillMaxHeight().focusProperties { canFocus = rail == Rail.GROUPS }) {
            RailHeader(
                when { grabbed != null -> "Moving · Up/Down · OK drops"; rail == Rail.GROUPS -> "Groups · hold OK for more · Menu sorts"; else -> "Groups · Left or Back to change" },
                amber = grabbed != null,
            )
            GroupRail(groups, groupNow, railFocus, grabbed, onGrab = { groupMenu = it }, onHover = { if (rail == Rail.GROUPS) hoverGroup = it }) { id ->
                if (id != groupNow) { pendingGroup = true; vm.selectGroup(id) }
                setRail(Rail.NONE)
            }
        }
        Spacer(Modifier.width(24.dp))
        // The grid: one focusable node. Nothing inside it takes focus; the cursor is drawn.
        Column(
            Modifier.fillMaxSize().clipToBounds()
                .focusRequester(gridFocus)
                .focusProperties { canFocus = rail == Rail.NONE; up = FocusRequester.Cancel; down = FocusRequester.Cancel; left = FocusRequester.Cancel; right = FocusRequester.Cancel }
                .focusable(),
        ) {
            Row(Modifier.fillMaxWidth().height(HEADER_H)) {
                Box(Modifier.width(CHANNEL_COL).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Label(groups.firstOrNull { it.first == groupNow }?.second ?: "All channels", modifier = Modifier.padding(end = 12.dp))
                }
                Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { timelinePx = it.width }.horizontalScroll(hScroll, enabled = false)) {
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

            val gridActive = rail == Rail.NONE
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
                    val progs = progsOf(c)
                    val onRow = gridActive && i == cursorRow
                    GuideRow(
                        vm = vm, channel = c, isCurrent = c.id == current?.id, copies = dupesNow.byChannel[c.id]?.members?.size ?: 1, progs = progs,
                        cursor = if (onRow) cellAt(progs, cursorEpoch) else null, cursorOn = onRow,
                        winStart = winStart, winEnd = winEnd, nowEpoch = nowEpoch, pxPerSec = pxPerSec,
                        hScroll = hScroll, measurer = measurer,
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
    groupMenu?.let { id ->
        val name = groups.firstOrNull { it.first == id }?.second ?: "GROUP"
        ChoicePrompt(name.uppercase().take(28), listOf("move" to "Move this group · Up and Down, OK drops", "hide" to "Hide this group", "no" to "Not now"), onCancel = { groupMenu = null }, onPick = { k ->
            groupMenu = null
            when (k) {
                "move" -> grabbed = id
                "hide" -> {
                    val i = groups.indexOfFirst { it.first == id }
                    val next = groups.getOrNull(i + 1) ?: groups.getOrNull(i - 1)
                    if (next != null && next.first != groupNow) vm.selectGroup(next.first)
                    vm.setHidden(id, true); vm.toast("Hidden: $name. Settings › Listings brings it back.")
                    scope.launch { delay(150); focusRail() }
                }
            }
        })
    }
    cellMenu?.let { (c, p) ->
        val now = System.currentTimeMillis() / 1000
        val live = p == null || (p.startEpoch <= now && p.stopEpoch > now)
        val past = p != null && p.stopEpoch <= now
        val future = p != null && p.startEpoch > now
        val inArchive = past && c.archive && (now - p!!.startEpoch) < c.archiveDays.coerceAtLeast(1) * 86_400L
        val options = buildList {
            if (live) add("watch" to "Watch now")
            if (inArchive) add("catchup" to "Play from the start (catch-up)")
            if (future) add(if (vm.hasReminder(c.id, p!!.startEpoch)) "unremind" to "Cancel the reminder" else "remind" to "Remind me when it starts")
            if (!past) add(tv.sloopy.ui.record.RecordOptions.entry)
            if (live && vm.vlcInstalled()) add("vlc" to "Open in VLC")
            add("fav" to (if (c.id in favIds) "Remove ${c.name.take(24)} from favourites" else "Add ${c.name.take(24)} to favourites"))
            val cl = vm.clusterOf(c)
            if (cl != null) add("copies" to "Copies · ${cl.members.size} · using ${tv.sloopy.core.model.Dupes.tags(cl.primary.name).ifBlank { "plain" }}")
            add("hidechan" to "Hide ${c.name.take(24)}")
            if (reportsOn) add("report" to "Report this channel as broken")
            add("no" to "Not now")
        }
        ChoicePrompt((p?.title ?: c.name).uppercase().take(28), options, onCancel = { cellMenu = null }, onPick = { k ->
            when (k) {
                "watch" -> { vm.tune(c); go("live") }
                "catchup" -> { vm.playCatchup(c, p!!.title, p.startEpoch, p.stopEpoch); go("live") }
                "fav" -> vm.toggleFavourite(c)
                "vlc" -> vm.openChannelInVlc(c)
                "remind" -> vm.addReminder(c, p!!.title, p.startEpoch, p.stopEpoch)
                "unremind" -> vm.removeReminder(c, p!!.startEpoch)
                "report" -> vm.reportChannel(c, null)
                "copies" -> copiesFor = c
                "record" -> recordFor = c to p
                "hidechan" -> { vm.setChannelHidden(c.id, true); vm.toast("Hidden: ${c.name}. Settings › Listings brings it back.") }
            }
            cellMenu = null
        })
    }
    recordFor?.let { (c, p) -> tv.sloopy.ui.record.RecordPrompt(vm, c, p) { recordFor = null } }
    copiesFor?.let { c ->
        val cl = vm.clusterOf(c)
        if (cl == null) copiesFor = null else {
            val options = cl.members.map { m -> "use:${m.id}" to ("Use " + tv.sloopy.core.model.Names.clean(m.name).take(30)) } +
                ("split" to "These are different channels, show them all") + ("no" to "Not now")
            ChoicePrompt("COPIES OF " + tv.sloopy.core.model.Names.clean(cl.primary.name).uppercase().take(20), options, current = "use:${cl.primary.id}", onCancel = { copiesFor = null }, onPick = { k ->
                when {
                    k.startsWith("use:") -> cl.members.firstOrNull { it.id == k.removePrefix("use:").toLongOrNull() }?.let { vm.preferCopy(it) }
                    k == "split" -> vm.splitCluster(c)
                }
                copiesFor = null
            })
        }
    }
    LaunchedEffect(sortMenu, cellMenu, groupMenu, copiesFor) { if (sortMenu == null && cellMenu == null && groupMenu == null && copiesFor == null) { if (rail == Rail.NONE) focusGrid() else focusRail() } }
    }
}

/** The provider's groups in the viewer's order. Opens on the group the grid is showing. */
@Composable
private fun GroupRail(groups: List<Pair<String, String>>, group: String, railFocus: FocusRequester, grabbed: String?, onGrab: (String) -> Unit, onHover: (String) -> Unit, onPick: (String) -> Unit) {
    val state = rememberLazyListState()
    val cur = groups.indexOfFirst { it.first == group }.coerceAtLeast(0)
    val focusIdx = if (grabbed != null) groups.indexOfFirst { it.first == grabbed } else cur
    LaunchedEffect(Unit) { state.scrollToItem((cur - 4).coerceAtLeast(0)) }
    LaunchedEffect(groups, grabbed) {
        if (grabbed == null || focusIdx < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
        if (focusIdx !in visible) state.scrollToItem((focusIdx - 4).coerceAtLeast(0))
        repeat(10) { delay(30); if (runCatching { railFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(groups, key = { _, g -> g.first }) { i, (id, name) ->
            val movable = id != Groups.ALL && id != Groups.FAV
            Box(Modifier.onFocusChanged { if (it.hasFocus && grabbed == null) onHover(id) }) {
                RailItem(
                    name, selected = i == cur, requester = if (i == focusIdx) railFocus else null, first = i == 0, last = i == groups.lastIndex,
                    grabbed = id == grabbed, onLongClick = if (movable) ({ onGrab(id) }) else null,
                ) { onPick(id) }
            }
        }
    }
}

@UnstableApi
private fun onSelect(vm: SloopyViewModel, c: ChannelEntity, p: ProgrammeEntity?, go: (String) -> Unit) {
    val nowSec = System.currentTimeMillis() / 1000
    when {
        p == null || (p.startEpoch <= nowSec && p.stopEpoch > nowSec) -> { vm.tune(c); go("live") }
        p.stopEpoch <= nowSec && c.archive && (nowSec - p.startEpoch) < c.archiveDays.coerceAtLeast(1) * 86_400L -> { vm.playCatchup(c, p.title, p.startEpoch, p.stopEpoch); go("live") }
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

/**
 * One row: the channel column is composed (number, logo, name), the cells are painted. Only the
 * cells inside the view are drawn; text is measured once per title and width and kept.
 */
@UnstableApi
@Composable
private fun GuideRow(
    vm: SloopyViewModel,
    channel: ChannelEntity,
    isCurrent: Boolean,
    copies: Int,
    /** The row's listings from the group store; null while the group loads. */
    progs: List<ProgrammeEntity>?,
    /** The programme under the cursor when the cursor is on this row (null = the no-data cell), and whether it is. */
    cursor: ProgrammeEntity?, cursorOn: Boolean,
    winStart: Long, winEnd: Long, nowEpoch: Long, pxPerSec: Float,
    hScroll: androidx.compose.foundation.ScrollState,
    measurer: TextMeasurer,
) {
    val titleStyle = Sloopy.cellTitle.copy(fontSize = 19.sp, fontWeight = FontWeight.Medium)
    val titleStylePast = titleStyle.copy(fontWeight = FontWeight.Normal)
    val timeStyle = Sloopy.mono14
    val colText = Sloopy.text; val colMuted = Sloopy.muted; val colSoft = Sloopy.textSoft
    val colSurface = Sloopy.surface; val colFocus = Sloopy.focus; val colAmber = Sloopy.amber
    val pending = progs != null && progs.isEmpty() && vm.listingsPending(channel)
    val cache = remember { HashMap<String, TextLayoutResult>() }
    Column {
        Row(Modifier.fillMaxWidth().height(ROW_H)) {
            Row(Modifier.width(CHANNEL_COL).fillMaxHeight().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(channel.number.toString(), style = Sloopy.mono16.copy(color = Sloopy.muted), modifier = Modifier.width(52.dp))
                ChannelLogo(channel.logo, channel.name, 40.dp)
                Spacer(Modifier.width(14.dp))
                Text(tv.sloopy.core.model.Names.clean(channel.name), style = Sloopy.listName.copy(fontSize = 21.sp, color = if (cursorOn) Color.White else Sloopy.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (copies > 1) Text("+${copies - 1}", style = Sloopy.mono14.copy(color = Sloopy.dim), modifier = Modifier.padding(end = 8.dp))
                if (isCurrent) LiveDot()
            }
            // clipToBounds: a programme that started before the view's left edge has a negative x, and
            // without the clip its rectangle painted straight over the channel name.
            Canvas(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                val scroll = hScroll.value
                val viewLo = winStart + (scroll / pxPerSec).toLong()
                val viewHi = viewLo + (size.width / pxPerSec).toLong()
                val d = density
                val gap = 1.5f * d; val padY = 2f * d; val padX = 12f * d
                val cellH = size.height - 2 * padY
                fun xOf(t: Long) = ((t - winStart) * pxPerSec) - scroll
                fun paint(a: Long, b: Long, title: String, time: String, past: Boolean, isNow: Boolean, archive: Boolean, selected: Boolean) {
                    val x0 = xOf(a); val x1 = xOf(b) - gap
                    if (x1 <= 0f || x0 >= size.width) return
                    val w = (x1 - x0).coerceAtLeast(6f * d)
                    val bg = when { selected -> colFocus; past -> Color(0xFF0E0E10); isNow -> Color(0xFF151518); else -> colSurface }
                    drawRect(bg, Offset(x0, padY), Size(w, cellH))
                    if (selected) drawRect(Color.White.copy(alpha = 0.55f), Offset(x0 + 0.5f * d, padY + 0.5f * d), Size(w - d, cellH - d), style = Stroke(d))
                    if (title.isEmpty() && time.isEmpty()) return
                    // Sticky text: a long programme's title stays readable while its start is off the left edge.
                    val inset = (-x0).coerceAtLeast(0f).coerceAtMost((w - 120f * d).coerceAtLeast(0f))
                    val tx = x0 + padX + inset
                    val maxW = (x1 - tx - padX).toInt()
                    if (maxW < 8 * d) return
                    clipRect(x0, padY, x1, padY + cellH) {
                        val tcol = if (selected) Color.White else if (past) colMuted else colText
                        val key = (if (past) "p|" else "n|") + maxW + "|" + title
                        val tl = cache.getOrPut(key) { measurer.measure(title, if (past) titleStylePast else titleStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW)) }
                        val sub = (if (past && archive) "↺  " else "") + time
                        val sl = cache.getOrPut("t|$maxW|$sub") { measurer.measure(sub, timeStyle, overflow = TextOverflow.Clip, maxLines = 1, constraints = Constraints(maxWidth = maxW)) }
                        val total = tl.size.height + sl.size.height
                        val top = padY + (cellH - total) / 2f
                        drawText(tl, color = tcol, topLeft = Offset(tx, top))
                        drawText(sl, color = if (selected) colSoft else colMuted, topLeft = Offset(tx, top + tl.size.height))
                    }
                }
                if (cache.size > 600) cache.clear()
                val list = progs
                // The cursor is on this row but its cell is not in view (listings that ended hours ago,
                // or a gap): light the whole row and name the channel, so the viewer always sees
                // where the cursor is. Cells paint over the band where they exist.
                if (cursorOn && !list.isNullOrEmpty()) {
                    val visible = cursor != null && xOf(minOf(cursor.stopEpoch, winEnd)) - gap > 0f && xOf(maxOf(cursor.startEpoch, winStart)) < size.width
                    if (!visible) {
                        drawRect(colFocus, Offset(0f, padY), Size(size.width, cellH))
                        drawRect(Color.White.copy(alpha = 0.55f), Offset(0.5f * d, padY + 0.5f * d), Size(size.width - d, cellH - d), style = Stroke(d))
                        val label = "No listings here  ·  " + tv.sloopy.core.model.Names.clean(channel.name)
                        val maxW = (size.width - 2 * padX).toInt()
                        if (maxW > 8 * d) {
                            val tl = cache.getOrPut("band|$maxW|$label") { measurer.measure(label, titleStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxW)) }
                            drawText(tl, color = Color.White, topLeft = Offset(padX, padY + (cellH - tl.size.height) / 2f))
                        }
                    }
                }
                if (list == null || list.isEmpty()) {
                    val a = maxOf(nowEpoch - 1800, winStart)
                    val label = if (list == null) "" else (if (pending) "Loading listings…" else "No guide data") + (if (cursorOn) "  ·  " + tv.sloopy.core.model.Names.clean(channel.name) else "")
                    paint(a, winEnd, label, "", past = true, isNow = false, archive = false, selected = cursorOn)
                } else {
                    for (p in list) {
                        if (p.stopEpoch <= viewLo - 1800 || p.startEpoch >= viewHi + 1800) continue
                        val a = maxOf(p.startEpoch, winStart); val b = minOf(p.stopEpoch, winEnd)
                        if (b <= a) continue
                        val isNow = p.startEpoch <= nowEpoch && p.stopEpoch > nowEpoch
                        paint(a, b, p.title + (p.episode?.let { "  $it" } ?: ""), hhmm(p.startEpoch) + " – " + hhmm(p.stopEpoch),
                            past = p.stopEpoch <= nowEpoch, isNow = isNow, archive = channel.archive, selected = cursorOn && cursor != null && cursor.startEpoch == p.startEpoch)
                    }
                }
                val nx = xOf(nowEpoch)
                if (nx >= 0f && nx <= size.width) drawRect(colAmber, Offset(nx, 0f), Size(1.5f * d, size.height))
            }
        }
        Hairline(color = Sloopy.hair2)
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
                    Text(tv.sloopy.core.model.Names.clean(shown.channel.name), style = Sloopy.listName.copy(color = Sloopy.muted))
                }
                val p = shown.prog
                Text(p?.title ?: tv.sloopy.core.model.Names.clean(shown.channel.name), style = Sloopy.titleHuge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
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
        Box(Modifier.width(356.dp).height(200.dp).background(Color.Black).drawBehind { drawRect(Sloopy.hair, style = Stroke(1.dp.toPx())) }) {
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
