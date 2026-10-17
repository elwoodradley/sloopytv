package tv.sloopy.ui.search

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import tv.sloopy.ui.common.SloopyButton
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.sloopy.data.db.ChannelEntity
import tv.sloopy.data.db.ProgrammeEntity
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.ChannelLogo
import tv.sloopy.ui.common.ChoicePrompt
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.NavStrip
import tv.sloopy.ui.common.ScreenHeader
import tv.sloopy.ui.settings.Field
import tv.sloopy.ui.theme.Sloopy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed class Hit {
    data class Chan(val c: ChannelEntity) : Hit()
    data class Prog(val p: ProgrammeEntity, val c: ChannelEntity?) : Hit()
    data class Item(val kind: String, val id: Long, val title: String, val sub: String?) : Hit()
}

@UnstableApi
@Composable
fun SearchScreen(vm: SloopyViewModel, go: (String) -> Unit, onBack: () -> Unit, onOpen: (String, Long) -> Unit, onWatch: () -> Unit) {
    val provider by vm.active.collectAsState()
    val channels by vm.channels.collectAsState()
    val history by vm.prefs.searchHistory.collectAsState(initial = emptyList())
    val favChannels by vm.favouriteIds.collectAsState()
    val favVod by vm.favouriteVodIds.collectAsState()
    val favSeries by vm.favouriteSeriesIds.collectAsState()
    var recordAsk by remember { mutableStateOf<Pair<ChannelEntity, ProgrammeEntity>?>(null) }
    var recordStep by remember { mutableStateOf<Pair<ChannelEntity, ProgrammeEntity>?>(null) }   // the Record row's second step
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(vm.lastSearch) }
    LaunchedEffect(q) { vm.lastSearch = q }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    val field = remember { FocusRequester() }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    val remoteAddress by vm.remoteAddress.collectAsState()
    LaunchedEffect(Unit) { vm.remoteText.collect { (text, _) -> q = text } }

    // The app's own microphone: the TV keyboard's one stops at the first pause. This one waits
    // two and a half seconds of silence, long enough for a whole title.
    val context = LocalContext.current
    val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) q = said
    }
    fun startSpeaking() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a channel, programme, movie or series")
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        if (intent.resolveActivity(context.packageManager) == null) { vm.toast("This box has no speech recogniser the app can use. Try the keyboard's microphone."); return }
        runCatching { speak.launch(intent) }.onFailure { vm.toast("Could not start voice search") }
    }

    LaunchedEffect(q, provider?.id) {
        val p = provider ?: return@LaunchedEffect
        if (q.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(250)
        val text = q.trim()
        val now = System.currentTimeMillis() / 1000
        val hidden = vm.hiddenGroups.value
        val hiddenCh = vm.hiddenChannels.value
        val shown = vm.collapse(channels.filter { it.categoryId !in hidden && it.id !in hiddenCh })
        val byEpg = shown.filter { it.epgId != null }.associateBy { it.epgId!! }
        val out = ArrayList<Hit>()
        runCatching {
            vm.graph.db.channels().search(p.id, text, 40).filter { it.categoryId !in hidden && it.id !in hiddenCh }.let { vm.collapse(it) }.take(30).forEach { out.add(Hit.Chan(it)) }
            vm.catalog.searchProgrammes(p.id, text, now).filter { byEpg[it.epgId] != null }.take(40).forEach { out.add(Hit.Prog(it, byEpg[it.epgId])) }
            vm.graph.db.vod().search(p.id, text, 30).forEach { out.add(Hit.Item("vod", it.id, it.name, "Movie" + (it.year?.let { y -> " · $y" } ?: ""))) }
            vm.graph.db.series().search(p.id, text, 30).forEach { out.add(Hit.Item("series", it.id, it.name, "Series" + (it.releaseDate?.take(4)?.let { y -> " · $y" } ?: ""))) }
        }
        hits = out
    }
    fun remember(hit: Hit) { scope.launch { vm.prefs.pushSearch(q) } }
    // Typed or spoken, a search that found something is remembered once it has sat for a moment.
    LaunchedEffect(q, hits.isNotEmpty()) { if (q.trim().length >= 3 && hits.isNotEmpty()) { delay(2500); vm.prefs.pushSearch(q.trim()) } }

    Column(Modifier.fillMaxSize().background(Sloopy.ground)) {
        ScreenHeader("Search")
        Row(Modifier.fillMaxSize().padding(start = 72.dp)) {
        NavStrip("search", go = go)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.fillMaxSize().padding(end = 72.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.width(720.dp)) {
                    Field("Search channels, programmes, movies and series", q, { q = it }, modifier = Modifier.focusRequester(field), placeholder = "Type, or press Speak", backLeaves = true)
                }
                Spacer(Modifier.width(12.dp))
                SloopyButton("Speak", onClick = { startSpeaking() }, modifier = Modifier.padding(bottom = 2.dp))
                if (history.isNotEmpty()) { Spacer(Modifier.width(8.dp)); SloopyButton("Clear recent", onClick = { scope.launch { vm.prefs.clearSearchHistory() } }, modifier = Modifier.padding(bottom = 2.dp)) }
            }
            if (remoteAddress != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(Color.White).padding(4.dp)) { tv.sloopy.ui.common.QrCode(remoteAddress!!, 64.dp) }
                Spacer(Modifier.width(12.dp))
                Text("Type on your phone: scan with the camera. Same Wi-Fi. You can also tap a channel there to tune.", style = Sloopy.label.copy(color = Sloopy.dim), modifier = Modifier.width(520.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (q.trim().length >= 2 && hits.isEmpty()) Text("Nothing on.", style = Sloopy.bodyBig)
            if (hits.isNotEmpty()) Text("Hold OK on a channel, movie or series to add it to favourites and keep going. Hold OK on a programme to record it.", style = Sloopy.label.copy(color = Sloopy.dim), modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (q.trim().length < 2) {
                    if (history.isNotEmpty()) {
                        item { Label("Recent searches", modifier = Modifier.padding(bottom = 6.dp)) }
                        items(history) { h -> ResultRow(h, "", logo = null, kicker = "AGAIN") { q = h } }
                    } else item { Text("Recent searches will show here.", style = Sloopy.body.copy(color = Sloopy.dim)) }
                } else {
                    // Movies and series first: a town name matches hundreds of channels and listings.
                    val groups = listOf(
                        "Movies" to hits.filter { it is Hit.Item && it.kind == "vod" },
                        "Series" to hits.filter { it is Hit.Item && it.kind == "series" },
                        "Channels" to hits.filterIsInstance<Hit.Chan>(),
                        "On the guide" to hits.filterIsInstance<Hit.Prog>(),
                    ).filter { it.second.isNotEmpty() }
                    groups.forEach { (name, list) ->
                        item(key = "h-$name") { Divider("$name · ${list.size}") }
                        items(list) { h ->
                            when (h) {
                                is Hit.Chan -> ResultRow(h.c.name, "Channel ${h.c.number}" + if (h.c.id in favChannels) "  ·  Favourite" else "", logo = h.c.logo, kicker = "LIVE",
                                    onLongClick = { vm.toggleFavourite(h.c); vm.toast(if (h.c.id in favChannels) "Removed from favourites: ${h.c.name}" else "Added to favourites: ${h.c.name}") },
                                ) { remember(h); vm.tune(h.c); onWatch() }
                                is Hit.Prog -> ResultRow(h.p.title, (h.c?.name ?: h.p.epgId) + " · " + startLabel(h.p), logo = h.c?.logo, kicker = "GUIDE",
                                    onLongClick = { h.c?.let { c -> recordAsk = c to h.p } },
                                ) {
                                    remember(h)
                                    val c = h.c ?: return@ResultRow
                                    val now = System.currentTimeMillis() / 1000
                                    if (h.p.startEpoch <= now && h.p.stopEpoch > now) { vm.tune(c); onWatch() }
                                    else if (h.p.stopEpoch <= now && c.archive) { vm.playCatchup(c, h.p.title, h.p.startEpoch, h.p.stopEpoch); onWatch() }
                                    else recordAsk = c to h.p
                                }
                                is Hit.Item -> {
                                    val fav = if (h.kind == "vod") h.id in favVod else h.id in favSeries
                                    ResultRow(h.title, (h.sub ?: "") + if (fav) "  ·  My list" else "", logo = null, kicker = if (h.kind == "vod") "MOVIE" else "SERIES",
                                        onLongClick = { vm.toggleItemFavourite(h.kind, h.id); vm.toast(if (fav) "Removed from My list: ${h.title}" else "Added to My list: ${h.title}") },
                                    ) { remember(h); onOpen(h.kind, h.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
    recordAsk?.let { (c, p) ->
        val future = p.startEpoch > System.currentTimeMillis() / 1000
        val remind = if (!future) emptyList() else listOf(if (vm.hasReminder(c.id, p.startEpoch)) "unremind" to "Cancel the reminder" else "remind" to "Remind me when it starts")
        ChoicePrompt(p.title.uppercase().take(28), remind + tv.sloopy.ui.record.RecordOptions.entry + ("no" to "Not now"), onCancel = { recordAsk = null },
            onPick = { k ->
                when (k) { "remind" -> vm.addReminder(c, p.title, p.startEpoch, p.stopEpoch); "unremind" -> vm.removeReminder(c, p.startEpoch); "record" -> recordStep = c to p }
                recordAsk = null; if (k != "record") runCatching { field.requestFocus() }
            })
    }
    recordStep?.let { (c, p) -> tv.sloopy.ui.record.RecordPrompt(vm, c, p) { recordStep = null; runCatching { field.requestFocus() } } }
}

@Composable
private fun Divider(text: String) {
    Box(Modifier.width(900.dp).padding(top = 14.dp, bottom = 4.dp).background(Sloopy.surface2).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text.uppercase(), style = Sloopy.labelBright)
    }
}

@Composable
private fun ResultRow(title: String, sub: String, logo: String?, kicker: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.surface2, contentColor = Sloopy.text, focusedContentColor = Color.White),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.width(900.dp).height(58.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(57.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(kicker, modifier = Modifier.width(70.dp))
                if (logo != null) { ChannelLogo(logo, title, 30.dp); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = Sloopy.listName.copy(color = Color.Unspecified), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = Sloopy.listSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Hairline(color = Sloopy.hair2)
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE")
private fun startLabel(p: ProgrammeEntity) = Instant.ofEpochSecond(p.startEpoch).atZone(ZoneId.systemDefault()).format(DAY) + " " + tv.sloopy.ui.common.TimeFmt.hhmm(p.startEpoch)
