package tv.sloopy.ui.scores

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.ChoicePrompt
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.NavStrip
import tv.sloopy.ui.common.ScreenHeader
import tv.sloopy.ui.common.TimeFmt
import tv.sloopy.ui.theme.Sloopy

/** Every game on now or soon, with the channel it is on. OK tunes. Hold picks the channel for a network. */
@UnstableApi
@Composable
fun ScoresScreen(vm: SloopyViewModel, go: (String) -> Unit, onBack: () -> Unit) {
    val rows by vm.games.collectAsState()
    var pick by remember { mutableStateOf<SloopyViewModel.GameRow?>(null) }
    val first = remember { FocusRequester() }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { vm.refreshScores(); delay(400); runCatching { first.requestFocus() }; while (true) { delay(45_000); vm.refreshScores() } }

    Column(Modifier.fillMaxSize().background(Sloopy.ground)) {
        ScreenHeader("Scores")
        Row(Modifier.fillMaxSize().padding(start = 72.dp)) {
            NavStrip("scores", go = go)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.fillMaxSize().padding(end = 72.dp, top = 8.dp)) {
                Label("Scores · NFL, college football, NBA, NHL, MLB · OK tunes · hold OK to pick the channel")
                Text("ON NOW", style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
                if (rows.isEmpty()) Text("No games on right now, or the scores feed is unreachable.", style = Sloopy.bodyBig)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(rows, key = { it.game.id }) { r ->
                        val g = r.game
                        Surface(
                            onClick = { r.channel?.let { vm.tune(it); vm.goHome() } ?: run { pick = r } },
                            onLongClick = { pick = r },
                            shape = ClickableSurfaceDefaults.shape(RectangleShape),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.surface2, contentColor = Sloopy.text, focusedContentColor = Color.White),
                            border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            modifier = Modifier.fillMaxWidth().height(76.dp).then(if (rows.firstOrNull() == r) Modifier.focusRequester(first) else Modifier),
                        ) {
                            Column {
                                Row(Modifier.fillMaxWidth().height(75.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.width(120.dp)) {
                                        Label(g.league.uppercase().replace("COLLEGE-FOOTBALL", "NCAAF"))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (g.state == "in") { LiveDot(); Spacer(Modifier.width(6.dp)) }
                                            Text(if (g.state == "pre") TimeFmt.hhmm(g.startEpoch) else g.detail, style = Sloopy.mono14.copy(color = if (g.redZone) Sloopy.amber else Sloopy.textSoft))
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(g.away + "  @  " + g.home, style = Sloopy.listName.copy(color = Color.Unspecified), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(if (g.redZone) "RED ZONE" + (g.possession?.let { " · $it" } ?: "") else g.possession?.let { "Ball: $it" }, g.networks.joinToString("/").ifBlank { null }).joinToString("  ·  "), style = Sloopy.listSub.copy(color = if (g.redZone) Sloopy.amber else Sloopy.muted), maxLines = 1)
                                    }
                                    if (g.state != "pre") Text("${g.awayScore}  –  ${g.homeScore}", style = Sloopy.titleRow.copy(color = Color.Unspecified), modifier = Modifier.padding(horizontal = 18.dp))
                                    Column(Modifier.width(300.dp), horizontalAlignment = Alignment.End) {
                                        Text(r.channel?.let { tv.sloopy.core.model.Names.clean(it.name) } ?: "No channel found · hold OK", style = Sloopy.mono14.copy(color = if (r.channel != null) Sloopy.textSoft else Sloopy.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (r.via.endsWith("?")) Text("guessed from the name · hold OK to confirm", style = Sloopy.label.copy(color = Sloopy.dim))
                                    }
                                }
                                Hairline(color = Sloopy.hair2)
                            }
                        }
                    }
                }
            }
        }
    }
    pick?.let { r ->
        val net = r.game.networks.firstOrNull() ?: ""
        val cands = vm.channelsForNetwork(net)
        val opts = cands.take(10).map { "ch:${it.id}" to ("${it.number}  " + tv.sloopy.core.model.Names.clean(it.name).take(30)) } + ("clear" to "Forget my pick for $net") + ("no" to "Not now")
        ChoicePrompt(if (net.isBlank()) "NO NETWORK LISTED" else "WHICH CHANNEL IS $net?", opts, current = r.channel?.let { "ch:${it.id}" }, onCancel = { pick = null }, onPick = { k ->
            when {
                k.startsWith("ch:") -> cands.firstOrNull { it.id == k.removePrefix("ch:").toLongOrNull() }?.let { vm.setNetworkChannel(net, it); vm.tune(it); vm.goHome() }
                k == "clear" -> vm.setNetworkChannel(net, null)
            }
            pick = null
        })
    }
}
