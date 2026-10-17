package tv.sloopy.ui.tonight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.ChoicePrompt
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.NavStrip
import tv.sloopy.ui.common.ScreenHeader
import tv.sloopy.ui.theme.Sloopy

/** Twelve tiles at most: what you follow, on tonight. Learns from "Not interested". */
@UnstableApi
@Composable
fun TonightScreen(vm: SloopyViewModel, go: (String) -> Unit, onBack: () -> Unit, onOpen: (String, Long) -> Unit) {
    val items by vm.tonight.collectAsState()
    val busy by vm.tonightBusy.collectAsState()
    var menu by remember { mutableStateOf<SloopyViewModel.TonightItem?>(null) }
    var recordStep by remember { mutableStateOf<Pair<tv.sloopy.data.db.ChannelEntity, tv.sloopy.data.db.ProgrammeEntity?>?>(null) }
    val first = remember { FocusRequester() }
    val hold = remember { FocusRequester() }
    BackHandler { onBack() }
    // Focus leaves the strip at once (so its label panel folds away) and rests on the notice until
    // the tiles exist; a slow lineup shows "Hang on" instead of a popped-out menu.
    LaunchedEffect(Unit) { vm.buildTonight(); repeat(10) { delay(50); if (runCatching { hold.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
    LaunchedEffect(busy, items.size) { if (!busy && items.isNotEmpty()) repeat(20) { delay(60); if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }

    Column(Modifier.fillMaxSize().background(Sloopy.ground)) {
        ScreenHeader("Tonight")
        Row(Modifier.fillMaxSize().padding(start = 72.dp)) {
            NavStrip("tonight", go = go)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.fillMaxSize().padding(end = 72.dp, top = 8.dp)) {
                Label("Tonight · from your channels, your list, your reminders and what's popular today")
                Text("ON TONIGHT", style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
                Box(Modifier.focusRequester(hold).focusProperties { up = FocusRequester.Cancel; down = FocusRequester.Cancel; right = FocusRequester.Cancel }.focusable()) {
                    when {
                        busy && items.isEmpty() -> Column { Label("Hang on", bright = true); Text("Fetching tonight's programmes…", style = Sloopy.bodyBig, modifier = Modifier.padding(top = 6.dp)) }
                        items.isEmpty() -> Text("Nothing you follow is on tonight. Watch a few channels and this fills in on its own. Settings › Picture can widen it to sport on any channel, new episodes on demand, and what's popular today.", style = Sloopy.bodyBig, modifier = Modifier.width(900.dp))
                    }
                }
                LazyVerticalGrid(columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    itemsIndexed(items, key = { _, it -> it.kind + it.title }) { i, item ->
                        Tile(vm, item, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            onClick = {
                                val now = System.currentTimeMillis() / 1000
                                when {
                                    item.vodId != null -> onOpen(item.vodKind ?: "series", item.vodId)
                                    item.channel != null && (item.prog == null || item.prog.startEpoch <= now) -> { vm.tune(item.channel); vm.goHome() }
                                    item.channel != null && item.prog != null -> vm.addReminder(item.channel, item.prog.title, item.prog.startEpoch, item.prog.stopEpoch)
                                }
                            },
                            onHold = { menu = item })
                    }
                }
            }
        }
    }
    menu?.let { item ->
        val opts = buildList {
            if (item.channel != null && item.prog != null && item.prog.startEpoch > System.currentTimeMillis() / 1000) { add("remind" to "Remind me when it starts"); add(tv.sloopy.ui.record.RecordOptions.entry) }
            add("no_title" to "Not interested in ${item.title.take(24)}")
            add("no" to "Not now")
        }
        ChoicePrompt(item.title.uppercase().take(28), opts, onCancel = { menu = null }, onPick = { k ->
            when {
                k == "remind" && item.channel != null && item.prog != null -> vm.addReminder(item.channel, item.prog.title, item.prog.startEpoch, item.prog.stopEpoch)
                k == "no_title" -> vm.notInterested(item.title)
                k == "record" && item.channel != null -> recordStep = item.channel to item.prog
            }
            menu = null
        })
    }
    recordStep?.let { (c, p) -> tv.sloopy.ui.record.RecordPrompt(vm, c, p) { recordStep = null } }
}

@UnstableApi
@Composable
private fun Tile(vm: SloopyViewModel, item: SloopyViewModel.TonightItem, modifier: Modifier, onClick: () -> Unit, onHold: () -> Unit) {
    val poster by produceState<String?>(item.image, item.title) { if (value == null) value = vm.poster(item.title, item.year, if (item.vodKind == "vod") "movie" else "tv") }
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick, onLongClick = onHold,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Sloopy.surface, focusedContainerColor = Sloopy.focus, contentColor = Sloopy.text, focusedContentColor = Color.White),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)), shape = RectangleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.height(196.dp).fillMaxWidth().onFocusChanged { focused = it.isFocused },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (poster != null) AsyncImage(model = poster, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = if (focused) 0.55f else 0.4f)
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Label(when (item.kind) { "reminder" -> "Reminder"; "series" -> "New episode"; "popular" -> "Popular"; else -> "Tonight" }, bright = item.kind == "reminder")
                Text(item.title, style = Sloopy.titleRow.copy(color = Color.Unspecified), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(item.sub, style = Sloopy.listSub.copy(color = if (focused) Sloopy.textSoft else Sloopy.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
