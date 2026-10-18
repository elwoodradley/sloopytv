package tv.sloopy.ui.scores

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.sloopy.core.sports.Game
import tv.sloopy.core.sports.Scores
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.ChoicePrompt
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.NavStrip
import tv.sloopy.ui.common.RAIL_W
import tv.sloopy.ui.common.RailHeader
import tv.sloopy.ui.common.RailItem
import tv.sloopy.ui.common.ScreenHeader
import tv.sloopy.ui.common.SloopyButton
import tv.sloopy.ui.common.TimeFmt
import tv.sloopy.ui.common.isDown
import tv.sloopy.ui.common.isKey
import tv.sloopy.ui.theme.Sloopy
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val COLS = 4

/**
 * Sports, in the app's rail shape: days, My teams and standings down the left; on the right the
 * game the cursor rests on as a card, then every game of the day as tiles grouped by league,
 * each with the channel it is on in the viewer's own lineup. OK tunes; hold OK for more; the
 * remote's Menu key hides the scores so a recording is not spoiled.
 */
@UnstableApi
@Composable
/** [onWatch] opens the picture with this screen kept beneath it, so Back from the game returns here. */
fun ScoresScreen(vm: SloopyViewModel, go: (String) -> Unit, onBack: () -> Unit, onWatch: () -> Unit = { vm.goHome() }) {
    val rows by vm.games.collectAsState()
    val busy by vm.scoresBusy.collectAsState()
    val day by vm.scoresDay.collectAsState()
    val followed by vm.followedTeams.collectAsState()
    val hide by vm.hideScores.collectAsState()
    val scope = rememberCoroutineScope()
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("live") }   // survives a trip to the picture and back
    var pickLeague by remember { mutableStateOf<String?>(null) }   // the team picker's league
    var leagueMenu by remember { mutableStateOf(false) }
    var gameMenu by remember { mutableStateOf<SloopyViewModel.GameRow?>(null) }
    var netPick by remember { mutableStateOf<SloopyViewModel.GameRow?>(null) }
    var focusedRow by remember { mutableStateOf<SloopyViewModel.GameRow?>(null) }
    val railFocus = remember { FocusRequester() }
    val stripFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var inContent by remember { mutableStateOf(false) }
    // OK or Right on a day before its games have arrived: hold the cursor on the rail and move it
    // over when the tiles exist. Asking for focus on tiles that are not there yet sent the cursor
    // off to the strip.
    var wantContent by remember { mutableStateOf(false) }
    BackHandler { when { pickLeague != null -> { pickLeague = null; scope.launch { delay(100); runCatching { railFocus.requestFocus() } } }; inContent -> runCatching { railFocus.requestFocus() }; else -> onBack() } }

    val today = remember { LocalDate.now() }
    // Leagues in their postseason right now get a Playoffs row; found once per visit.
    val playoffLeagues by produceState(emptyList<String>()) { value = Scores.LEAGUES.filter { runCatching { vm.inPlayoffs(it) }.getOrDefault(false) } }
    val railOrder by vm.sportsRailOrder.collectAsState()
    var railMenu by remember { mutableStateOf<String?>(null) }   // hold OK on a rail row: move it
    val pages = remember(today, playoffLeagues, railOrder) {
        val base = buildList {
            add("live" to "Live now"); add("d0" to "Today"); add("d1" to "Tomorrow"); add("d-1" to "Yesterday")
            for (i in 2..6) add("d$i" to today.plusDays(i.toLong()).format(DateTimeFormatter.ofPattern("EEEE")))
            add("teams" to "My teams")
            playoffLeagues.forEach { add("po:$it" to "Playoffs · " + Scores.leagueName(it)) }
            Scores.LEAGUES.forEach { add("st:$it" to "Standings · " + Scores.leagueName(it)) }
        }
        // The viewer's order first (My teams at the top, say), then whatever it does not name.
        val rank = railOrder.withIndex().associate { (i, k) -> k to i }
        base.sortedWith(compareBy({ rank[it.first] ?: Int.MAX_VALUE }, { base.indexOf(it) }))
    }
    // The day a page needs: the live page is today's feed filtered to games in play.
    fun dayOf(p: String): Int = if (p.startsWith("d")) p.drop(1).toIntOrNull() ?: 0 else 0
    // Resting on a day for a moment fetches it; flicking down the rail does not fetch every day on the way.
    LaunchedEffect(page) { if (page == "live" || page.startsWith("d") || page == "teams") { delay(250); vm.setScoresDay(dayOf(page)) } }
    LaunchedEffect(Unit) { vm.refreshChannelsIfStale() }
    LaunchedEffect(Unit) { vm.refreshScores(); delay(300); runCatching { railFocus.requestFocus() }; while (true) { delay(45_000); if (vm.scoresDay.value == 0) vm.refreshScores() } }

    val shown: List<SloopyViewModel.GameRow> = remember(rows, page, followed) {
        when {
            page == "live" -> rows.filter { it.game.state == "in" }
            page == "teams" -> rows.filter { r -> followed.any { r.game.has(it.league, it.id) } }
            else -> rows
        }
    }
    val card = focusedRow?.takeIf { f -> shown.any { it.game.id == f.game.id } } ?: shown.firstOrNull()
    fun enterContent() {
        val ready = page == "teams" || page.startsWith("st:") || (!busy && shown.isNotEmpty())
        if (ready) scope.launch { repeat(10) { delay(40); if (runCatching { contentFocus.requestFocus() }.getOrDefault(false)) return@launch } }
        else wantContent = true
    }
    LaunchedEffect(busy, shown.size, page) {
        if (!wantContent || busy) return@LaunchedEffect
        wantContent = false
        if (shown.isNotEmpty()) repeat(15) { delay(60); if (runCatching { contentFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
    }

    Column(
        Modifier.fillMaxSize().background(Sloopy.ground).onPreviewKeyEvent { ev ->
            if (ev.isDown && ev.isKey(Key.Menu) && gameMenu == null && netPick == null && !leagueMenu) { vm.setHideScores(!hide); true } else false
        },
    ) {
        ScreenHeader("Sports")
        Row(Modifier.fillMaxSize().padding(start = 72.dp)) {
            NavStrip("scores", go = go, requester = stripFocus)
            Spacer(Modifier.width(16.dp))
            // Rail rows cancel Right themselves; the screen decides: Right or OK goes into the content.
            Column(Modifier.width(RAIL_W).fillMaxHeight().onFocusChanged { if (it.hasFocus) inContent = false }
                .onPreviewKeyEvent { ev -> if (ev.isDown && ev.isKey(Key.DirectionRight)) { enterContent(); true } else false }) {
                RailHeader("Sports hub")
                val state = rememberLazyListState()
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(pages, key = { _, p -> p.first }) { i, (key, name) ->
                        // Landing on a different rail row changes the page and drops an open picker; the
                        // cursor merely resting back on the same row leaves the picker up.
                        // A rail row taking focus changes the page; the team picker is dropped only when the
                        // cursor really leaves My teams (focus passing through a neighbour while a prompt
                        // closes used to wipe it: "I click MLS and it pops me back out").
                        // A rail row taking focus changes the page. It never drops the team picker: as the
                        // league prompt closes, focus passes through a neighbouring row for a frame, and
                        // that used to wipe the picker ("I click MLS and it pops me back out"). The picker
                        // shows while the page is My teams; Back or Not now closes it.
                        Box(Modifier.onFocusChanged { if (it.hasFocus && page != key && pickLeague == null) page = key }) {
                            RailItem(name + (if (key == "live" && rows.any { it.game.state == "in" }) "  ·  ${rows.count { it.game.state == "in" }}" else ""), selected = key == page, requester = if (key == page) railFocus else null, first = i == 0, last = i == pages.lastIndex, leftTarget = stripFocus, onLongClick = { railMenu = key }) {
                                // OK fetches the day at once (no resting delay) and moves over when it is in.
                                // A deliberate OK on a rail row is what closes the team picker (resting never does).
                                if (pickLeague != null && key != "teams") pickLeague = null
                                page = key
                                if (key == "live" || key.startsWith("d") || key == "teams") vm.setScoresDay(dayOf(key))
                                enterContent()
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.fillMaxSize().padding(end = 72.dp, top = 8.dp).onFocusChanged { if (it.hasFocus) inContent = true }) {
                when {
                    pickLeague != null -> TeamPicker(vm, pickLeague!!, contentFocus, railFocus, onDone = { pickLeague = null; scope.launch { delay(100); runCatching { railFocus.requestFocus() } } })
                    page.startsWith("st:") -> StandingsPage(vm, page.removePrefix("st:"), contentFocus, railFocus)
                    page.startsWith("po:") -> PlayoffsPage(vm, page.removePrefix("po:"), hide, contentFocus, railFocus, onFocus = { focusedRow = it }, onOpen = { r -> r.channel?.let { vm.tune(it); onWatch() } ?: run { gameMenu = r } }, onHold = { gameMenu = it })
                    else -> {
                        val title = when (page) { "live" -> "LIVE NOW"; "teams" -> "MY TEAMS"; "d0" -> "TODAY"; "d1" -> "TOMORROW"; "d-1" -> "YESTERDAY"; else -> pages.firstOrNull { it.first == page }?.second?.uppercase() ?: "GAMES" }
                        Label(when { busy -> "Fetching scores…"; hide -> "Scores hidden · Menu shows them"; else -> "OK watch · hold OK for more · Menu hides scores" })
                        Text(title, style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                        if (page == "teams") {
                            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                SloopyButton("Add a team", primary = followed.isEmpty(), modifier = Modifier.focusRequester(contentFocus).focusProperties { left = railFocus }, onClick = { leagueMenu = true })
                                Spacer(Modifier.width(14.dp))
                                Text(if (followed.isEmpty()) "Follow teams and their games gather here." else followed.joinToString("  ·  ") { it.name }, style = Sloopy.body.copy(color = Sloopy.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (card != null) GameCard(card, hide)
                        if (shown.isEmpty() && !busy) Text(
                            when (page) { "live" -> "Nothing in play right now."; "teams" -> if (followed.isEmpty()) "" else "No games for your teams on this day."; else -> "No games on this day, or the scores feed is unreachable." },
                            style = Sloopy.bodyBig, modifier = Modifier.padding(top = 10.dp),
                        )
                        GameGrid(shown, hide, if (page == "teams") null else contentFocus, railFocus, onFocus = { focusedRow = it }, onOpen = { r -> r.channel?.let { vm.tune(it); onWatch() } ?: run { gameMenu = r } }, onHold = { gameMenu = it })
                    }
                }
            }
        }
    }

    // While the team list downloads there is nothing in the content to focus, so the rail holds
    // the cursor; the picker hands it to its first row when the list arrives.
    railMenu?.let { key ->
        val name = pages.firstOrNull { it.first == key }?.second ?: key
        ChoicePrompt(name.uppercase().take(28), listOf("top" to "Move to the top", "up" to "Move up", "down" to "Move down", "reset" to "Put everything back in order", "no" to "Not now"), onCancel = { railMenu = null }, onPick = { k ->
            val ids = pages.map { it.first }.toMutableList(); val i = ids.indexOf(key)
            when (k) {
                "top" -> { ids.removeAt(i); ids.add(0, key); vm.setSportsRailOrder(ids) }
                "up" -> if (i > 0) { ids.removeAt(i); ids.add(i - 1, key); vm.setSportsRailOrder(ids) }
                "down" -> if (i < ids.lastIndex) { ids.removeAt(i); ids.add(i + 1, key); vm.setSportsRailOrder(ids) }
                "reset" -> vm.setSportsRailOrder(emptyList())
            }
            railMenu = null
            scope.launch { delay(150); runCatching { railFocus.requestFocus() } }
        })
    }
    if (leagueMenu) ChoicePrompt("WHICH LEAGUE?", Scores.LEAGUES.map { it to Scores.leagueName(it) } + ("no" to "Not now"), onCancel = { leagueMenu = false }, onPick = { k -> leagueMenu = false; if (k != "no") { page = "teams"; pickLeague = k; scope.launch { repeat(15) { delay(60); if (runCatching { contentFocus.requestFocus() }.getOrDefault(false)) return@launch } } } })
    gameMenu?.let { r ->
        val g = r.game
        val now = System.currentTimeMillis() / 1000
        val opts = buildList {
            if (r.channel != null) add("watch" to "Watch · " + tv.sloopy.core.model.Names.clean(r.channel.name).take(30))
            if (g.state == "pre" && r.channel != null) add("remind" to "Remind me when it starts")
            add("fa" to (if (vm.follows(g.league, g.awayId)) "Unfollow the ${g.away}" else "Follow the ${g.away}"))
            add("fh" to (if (vm.follows(g.league, g.homeId)) "Unfollow the ${g.home}" else "Follow the ${g.home}"))
            if (g.networks.isNotEmpty()) add("net" to "Pick the channel for ${g.networks.first()}")
            add("hide" to (if (hide) "Show scores" else "Hide scores"))
            add("no" to "Not now")
        }
        ChoicePrompt("${g.awayShort} AT ${g.homeShort}".take(28), opts, onCancel = { gameMenu = null }, onPick = { k ->
            when (k) {
                "watch" -> r.channel?.let { vm.tune(it); onWatch() }
                "remind" -> r.channel?.let { vm.addReminder(it, "${g.away} at ${g.home}", g.startEpoch, g.startEpoch + 3 * 3600) }
                "fa" -> vm.toggleFollow(SloopyViewModel.FollowedTeam(g.league, g.awayId, g.away, g.awayShort, g.awayLogo))
                "fh" -> vm.toggleFollow(SloopyViewModel.FollowedTeam(g.league, g.homeId, g.home, g.homeShort, g.homeLogo))
                "net" -> netPick = r
                "hide" -> vm.setHideScores(!hide)
            }
            gameMenu = null
        })
    }
    netPick?.let { r ->
        val net = r.game.networks.firstOrNull() ?: ""
        val cands = vm.channelsForNetwork(net)
        val opts = cands.take(10).map { "ch:${it.id}" to ("${it.number}  " + tv.sloopy.core.model.Names.clean(it.name).take(30)) } + ("clear" to "Forget my pick for $net") + ("no" to "Not now")
        ChoicePrompt(if (net.isBlank()) "NO NETWORK LISTED" else "WHICH CHANNEL IS $net?", opts, current = r.channel?.let { "ch:${it.id}" }, onCancel = { netPick = null }, onPick = { k ->
            when {
                k.startsWith("ch:") -> cands.firstOrNull { it.id == k.removePrefix("ch:").toLongOrNull() }?.let { vm.setNetworkChannel(net, it) }
                k == "clear" -> vm.setNetworkChannel(net, null)
            }
            netPick = null
        })
    }
}

/** The game the cursor rests on: both sides large, the status, and what OK will do. */
@Composable
private fun GameCard(r: SloopyViewModel.GameRow, hide: Boolean) {
    val g = r.game
    Row(Modifier.fillMaxWidth().height(150.dp).background(Sloopy.surface).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(Scores.leagueName(g.league), bright = false)
                Spacer(Modifier.width(14.dp))
                if (g.state == "in") { LiveDot(); Spacer(Modifier.width(6.dp)) }
                Text(statusText(g, hide), style = Sloopy.mono14.copy(color = if (g.redZone && !hide) Sloopy.amber else Sloopy.textSoft))
                if (g.redZone && !hide) Text("  ·  RED ZONE" + (g.possession?.let { " · $it" } ?: ""), style = Sloopy.mono14.copy(color = Sloopy.amber))
            }
            if (g.note.isNotBlank() || g.series.isNotBlank()) Text(listOf(g.note, if (hide) "" else g.series).filter { it.isNotBlank() }.joinToString("  ·  "), style = Sloopy.mono14.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 2.dp))
            TeamLine(g.awayLogo, g.away, g.awayRecord, if (hide || g.state == "pre") "" else g.awayScore.toString(), big = true)
            TeamLine(g.homeLogo, g.home, g.homeRecord, if (hide || g.state == "pre") "" else g.homeScore.toString(), big = true)
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.width(480.dp), horizontalAlignment = Alignment.End) {
            if (r.channel != null) {
                Text("OK to watch", style = Sloopy.titleRow)
                Text("On " + tv.sloopy.core.model.Names.clean(r.channel.name), style = Sloopy.body.copy(color = Sloopy.textSoft), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(420.dp))
                val why = when {
                    r.via == "guide" -> "Matched in the guide"
                    r.via == "name" -> "Matched by channel name"
                    r.via == "package" -> "From the package · this game by name"
                    r.via.startsWith("map:") -> "Your pick for " + r.via.removePrefix("map:")
                    r.via.startsWith("guide:") -> r.via.removePrefix("guide:") + " · matched in the guide"
                    r.via.startsWith("name:") -> r.via.removePrefix("name:") + " · matched by channel name"
                    r.via.startsWith("guess:") -> r.via.removePrefix("guess:") + " · best guess · hold OK to confirm"
                    else -> null
                }
                if (why != null) Text(why, style = Sloopy.label.copy(color = Sloopy.dim))
            } else {
                Text("No channel found", style = Sloopy.titleRow.copy(color = Sloopy.muted))
                Text(if (g.networks.isEmpty()) "No network listed for this game" else "Hold OK to pick the channel for " + g.networks.first(), style = Sloopy.body.copy(color = Sloopy.muted))
            }
            if (g.networks.isNotEmpty()) Text(g.networks.joinToString(" / "), style = Sloopy.label.copy(color = Sloopy.dim), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun TeamLine(logo: String?, name: String, record: String, score: String, big: Boolean) {
    val nameStyle = if (big) Sloopy.listName.copy(fontSize = 26.sp, color = Sloopy.text) else Sloopy.listName.copy(color = Color.Unspecified)
    Row(Modifier.height(if (big) 44.dp else 30.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (logo != null) AsyncImage(model = logo, contentDescription = null, modifier = Modifier.size(if (big) 28.dp else 18.dp), contentScale = ContentScale.Fit)
        else Box(Modifier.size(if (big) 28.dp else 18.dp).background(Sloopy.surface2))
        Spacer(Modifier.width(10.dp))
        Text(name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (record.isNotBlank()) Text(record, style = Sloopy.mono14.copy(color = Sloopy.muted), maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp).widthIn(min = if (big) 70.dp else 50.dp))
        if (score.isNotBlank()) Text(score, style = nameStyle, modifier = Modifier.padding(start = 10.dp).width(if (big) 60.dp else 36.dp))
    }
}

/** "ALCS - Game 4" → "ALCS G4"; "AFC Wild Card - Bills at Dolphins" → "AFC Wild Card". Fits a tile's corner. */
private fun roundShort(note: String): String {
    val head = note.substringBefore(" - ").trim()
    val game = Regex("Game (\\d+)").find(note)?.groupValues?.get(1)
    return (if (game != null) "$head G$game" else head).take(18)
}

private fun statusText(g: Game, hide: Boolean): String = when (g.state) {
    "pre" -> TimeFmt.hhmm(g.startEpoch)
    "in" -> if (hide) "LIVE" else g.detail
    else -> if (hide) "FINAL" else g.detail
}

/** Games of the day as tiles, a header per league. */
@UnstableApi
@Composable
private fun GameGrid(rows: List<SloopyViewModel.GameRow>, hide: Boolean, first: FocusRequester?, railFocus: FocusRequester, onFocus: (SloopyViewModel.GameRow) -> Unit, onOpen: (SloopyViewModel.GameRow) -> Unit, onHold: (SloopyViewModel.GameRow) -> Unit) {
    // Cells in league order: a header (full width) then that league's games.
    val cells = remember(rows) {
        buildList<Any> {
            rows.groupBy { it.game.league }.toSortedMap(compareBy { Scores.LEAGUES.indexOf(it) }).forEach { (league, games) -> add(league); addAll(games) }
        }
    }
    var firstTileIndex = -1
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLS), modifier = Modifier.fillMaxSize().padding(top = 12.dp).focusRestorer().focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        itemsIndexed(cells, key = { _, c -> if (c is String) "h:$c" else (c as SloopyViewModel.GameRow).game.id }, span = { _, c -> GridItemSpan(if (c is String) maxLineSpan else 1) }) { i, c ->
            if (c is String) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Label(Scores.leagueName(c) + "  ·  " + cells.count { it is SloopyViewModel.GameRow && it.game.league == c }) }
            } else {
                val r = c as SloopyViewModel.GameRow
                val isFirst = firstTileIndex < 0 || firstTileIndex == i
                if (firstTileIndex < 0) firstTileIndex = i
                val g = r.game
                Surface(
                    onClick = { onOpen(r) }, onLongClick = { onHold(r) },
                    shape = ClickableSurfaceDefaults.shape(RectangleShape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Sloopy.surface, focusedContainerColor = Sloopy.focus, contentColor = Sloopy.text, focusedContentColor = Color.White),
                    border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)), shape = RectangleShape)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.height(126.dp).fillMaxWidth()
                        .then(if (isFirst && first != null) Modifier.focusRequester(first) else Modifier)
                        .focusProperties { if (cells.take(i).count { it is SloopyViewModel.GameRow } % COLS == 0) left = railFocus }
                        .onFocusChanged { if (it.isFocused) onFocus(r) },
                ) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (g.state == "in") { LiveDot(); Spacer(Modifier.width(6.dp)) }
                            Text(statusText(g, hide), style = Sloopy.mono14.copy(color = if (g.redZone && !hide) Sloopy.amber else Sloopy.muted), maxLines = 1)
                            Spacer(Modifier.weight(1f))
                            if (g.redZone && !hide) Text("RZ", style = Sloopy.label.copy(color = Sloopy.amber))
                            else if (g.note.isNotBlank()) Text(roundShort(g.note), style = Sloopy.label.copy(color = Sloopy.amber), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                        }
                        TeamLine(g.awayLogo, g.away, g.awayRecord, if (hide || g.state == "pre") "" else g.awayScore.toString(), big = false)
                        TeamLine(g.homeLogo, g.home, g.homeRecord, if (hide || g.state == "pre") "" else g.homeScore.toString(), big = false)
                        Spacer(Modifier.weight(1f))
                        Text(r.channel?.let { tv.sloopy.core.model.Names.clean(it.name) } ?: (if (g.networks.isEmpty()) "" else g.networks.first() + " · no channel"), style = Sloopy.label.copy(color = if (r.channel != null) Sloopy.textSoft else Sloopy.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Every team in a league; OK follows or unfollows. */
@Composable
private fun TeamPicker(vm: SloopyViewModel, league: String, first: FocusRequester, railFocus: FocusRequester, onDone: () -> Unit) {
    val teams by produceState<List<tv.sloopy.core.sports.Team>?>(null, league) { value = vm.teamsFor(league) }
    val followed by vm.followedTeams.collectAsState()
    // The list composes a frame or two after it arrives: keep asking until the first row takes focus.
    LaunchedEffect(teams) { if (!teams.isNullOrEmpty()) repeat(25) { delay(80); if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect } }
    Label("Follow a team · OK follows, OK again unfollows · Back when done")
    Text(Scores.leagueName(league).uppercase(), style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
    when {
        // While the list loads, the notice itself holds focus (so the cursor has a home in the
        // content and cannot wander back to the rail); the first row takes over when it lands.
        teams == null -> Box(Modifier.focusRequester(first).focusProperties { left = railFocus }.focusable()) { Text("Fetching the team list…", style = Sloopy.bodyBig) }
        teams!!.isEmpty() -> Box(Modifier.focusRequester(first).focusProperties { left = railFocus }.focusable()) { Text("Could not fetch the team list. Check the connection and try again.", style = Sloopy.bodyBig.copy(color = Sloopy.amber)) }
        else -> LazyColumn(Modifier.fillMaxSize().focusRestorer().focusGroup(), contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(teams!!, key = { _, t -> t.id }) { i, t ->
                val on = followed.any { it.league == league && it.id == t.id }
                Surface(
                    onClick = { vm.toggleFollow(SloopyViewModel.FollowedTeam.of(t)) },
                    shape = ClickableSurfaceDefaults.shape(RectangleShape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.surface2, contentColor = Sloopy.text, focusedContentColor = Color.White),
                    border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    modifier = Modifier.fillMaxWidth().height(54.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier).focusProperties { left = railFocus },
                ) {
                    Column {
                        Row(Modifier.fillMaxWidth().height(53.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(if (on) Sloopy.amber else Color.Transparent))
                            Spacer(Modifier.width(14.dp))
                            if (t.logo != null) { AsyncImage(model = t.logo, contentDescription = null, modifier = Modifier.size(26.dp), contentScale = ContentScale.Fit); Spacer(Modifier.width(12.dp)) }
                            Text(t.name, style = Sloopy.listName.copy(color = Color.Unspecified), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.abbr, style = Sloopy.mono14.copy(color = Sloopy.muted), modifier = Modifier.width(60.dp))
                            Text(if (on) "FOLLOWING" else "FOLLOW", style = Sloopy.label.copy(color = if (on) Sloopy.amber else Sloopy.muted))
                        }
                        Hairline(color = Sloopy.hair2)
                    }
                }
            }
        }
    }
}

/** A league's table, one block per conference or division. */
/** Every postseason game ESPN lists for the league, a month either side of today, newest round first; the round and series ride each tile. */
@Composable
private fun PlayoffsPage(vm: SloopyViewModel, league: String, hide: Boolean, first: FocusRequester, railFocus: FocusRequester, onFocus: (SloopyViewModel.GameRow) -> Unit, onOpen: (SloopyViewModel.GameRow) -> Unit, onHold: (SloopyViewModel.GameRow) -> Unit) {
    var rows by remember(league) { mutableStateOf<List<SloopyViewModel.GameRow>?>(null) }
    LaunchedEffect(league) { rows = vm.playoffsFor(league) }
    Label("Playoffs · from ESPN · hold OK on a game for more")
    Text(("Playoffs · " + Scores.leagueName(league)).uppercase(), style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
    when {
        rows == null -> Text("Fetching the bracket…", style = Sloopy.bodyBig)
        rows!!.isEmpty() -> Text("No playoff games listed.", style = Sloopy.bodyBig.copy(color = Sloopy.amber))
        else -> {
            val now = System.currentTimeMillis() / 1000
            // In play first, then upcoming by kickoff, then finished newest first.
            val sorted = remember(rows) { rows!!.sortedWith(compareBy({ when (it.game.state) { "in" -> 0; "pre" -> 1; else -> 2 } }, { if (it.game.state == "post") -it.game.startEpoch else it.game.startEpoch })) }
            GameGrid(sorted, hide, first, railFocus, onFocus = onFocus, onOpen = onOpen, onHold = onHold)
        }
    }
}

@Composable
private fun StandingsPage(vm: SloopyViewModel, league: String, first: FocusRequester, railFocus: FocusRequester) {
    // Keyed remember, not produceState: produceState keeps the old league's rows while the new
    // ones load, so the heading flipped a beat before the table did.
    var all by remember(league) { mutableStateOf<List<tv.sloopy.core.sports.StandingsGroup>?>(null) }
    LaunchedEffect(league) { all = vm.standingsFor(league) }
    val followed by vm.followedTeams.collectAsState()
    // Several conferences or divisions: a grid of cards, each with the top of its table; OK opens
    // the whole table and Back returns to the grid. One table (the Premier League): straight in.
    var open by remember(league) { mutableStateOf<String?>(null) }
    val groups = remember(all, open) { all?.let { list -> if (open == null) list else list.filter { it.name == open } } }
    androidx.activity.compose.BackHandler(enabled = open != null) { open = null }
    // The table is composed a frame after OK on a card, and the grid a frame after Back: ask for
    // the first row or card until it takes. Not on the page's first composition, which would
    // pull the cursor off the rail while it merely rests on the Standings row.
    var lastOpen by remember(league) { mutableStateOf<String?>(null) }
    LaunchedEffect(open) {
        val from = lastOpen; lastOpen = open
        if (open != null || from != null) repeat(12) { delay(60); if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
    }
    Label("Standings · from ESPN · teams you follow are marked" + if (open != null) " · Back for every table" else "")
    Text((if (open != null) open!! else Scores.leagueName(league)).uppercase(), style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    when {
        all == null -> Text("Fetching the table…", style = Sloopy.bodyBig)
        all!!.isEmpty() -> Text("No standings right now, or the feed is unreachable.", style = Sloopy.bodyBig.copy(color = Sloopy.amber))
        open == null && all!!.size > 1 -> {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().focusRestorer().focusGroup(), contentPadding = PaddingValues(bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(all!!, key = { _, g -> g.name }) { i, g ->
                    Surface(
                        onClick = { open = g.name },
                        shape = ClickableSurfaceDefaults.shape(RectangleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Sloopy.surface, focusedContainerColor = Sloopy.surface2, contentColor = Sloopy.text, focusedContentColor = Color.White),
                        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier).focusProperties { if (i % 3 == 0) left = railFocus },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Label(g.name.take(34), bright = true)
                            Spacer(Modifier.height(6.dp))
                            g.rows.take(6).forEachIndexed { k, r ->
                                val on = followed.any { it.league == league && it.id == r.teamId }
                                Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text((k + 1).toString(), style = Sloopy.mono14.copy(color = Sloopy.muted), modifier = Modifier.width(24.dp))
                                    Box(Modifier.size(5.dp).background(if (on) Sloopy.amber else Color.Transparent)); Spacer(Modifier.width(8.dp))
                                    Text(r.abbr.ifBlank { r.team }, style = Sloopy.listName.copy(color = Color.Unspecified), modifier = Modifier.weight(1f), maxLines = 1)
                                    Text(r.record, style = Sloopy.mono14.copy(color = Color.Unspecified))
                                }
                            }
                            if (g.rows.size > 6) Text("OK for all ${g.rows.size}", style = Sloopy.label.copy(color = Sloopy.dim), modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        else -> {
            val lines = remember(groups) { buildList<Any> { groups!!.forEach { g -> if (groups!!.size > 1) add(g.name); g.rows.forEachIndexed { i, r -> add(i + 1 to r) } } } }
            LazyColumn(Modifier.fillMaxSize().focusRestorer().focusGroup(), contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(lines) { i, l ->
                    if (l is String) Label(l, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                    else {
                        val pair = l as Pair<*, *>
                        val rank = pair.first as Int; val r = pair.second as tv.sloopy.core.sports.StandingsRow
                        val on = followed.any { it.league == league && it.id == r.teamId }
                        Surface(
                            onClick = {},
                            shape = ClickableSurfaceDefaults.shape(RectangleShape),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.surface2, contentColor = Sloopy.text, focusedContentColor = Color.White),
                            border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            modifier = Modifier.fillMaxWidth().height(44.dp).then(if (i == lines.indexOfFirst { it !is String }) Modifier.focusRequester(first) else Modifier).focusProperties { left = railFocus },
                        ) {
                            Column {
                                Row(Modifier.fillMaxWidth().height(43.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(rank.toString(), style = Sloopy.mono14.copy(color = Sloopy.muted), modifier = Modifier.width(36.dp))
                                    Box(Modifier.size(6.dp).background(if (on) Sloopy.amber else Color.Transparent)); Spacer(Modifier.width(10.dp))
                                    Text(r.team, style = Sloopy.listName.copy(color = Color.Unspecified), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(r.record, style = Sloopy.mono16.copy(color = Color.Unspecified), modifier = Modifier.width(110.dp))
                                    Text(r.note, style = Sloopy.mono14.copy(color = Sloopy.muted), modifier = Modifier.width(110.dp))
                                }
                                Hairline(color = Sloopy.hair2)
                            }
                        }
                    }
                }
            }
        }
    }
}
