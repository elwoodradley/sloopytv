package tv.sloopy.ui.search

import androidx.activity.compose.BackHandler
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
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
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
fun SearchScreen(vm: SloopyViewModel, go: (String) -> Unit, onBack: () -> Unit, onOpen: (String, Long) -> Unit) {
    val provider by vm.active.collectAsState()
    val channels by vm.channels.collectAsState()
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    val field = remember { FocusRequester() }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    LaunchedEffect(q, provider?.id) {
        val p = provider ?: return@LaunchedEffect
        if (q.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(250)
        val text = q.trim()
        val now = System.currentTimeMillis() / 1000
        val byEpg = channels.filter { it.epgId != null }.associateBy { it.epgId!! }
        val out = ArrayList<Hit>()
        runCatching {
            vm.graph.db.channels().search(p.id, text, 30).forEach { out.add(Hit.Chan(it)) }
            vm.catalog.searchProgrammes(p.id, text, now).take(40).forEach { out.add(Hit.Prog(it, byEpg[it.epgId])) }
            vm.graph.db.vod().search(p.id, text, 30).forEach { out.add(Hit.Item("vod", it.id, it.name, "Movie" + (it.year?.let { y -> " · $y" } ?: ""))) }
            vm.graph.db.series().search(p.id, text, 30).forEach { out.add(Hit.Item("series", it.id, it.name, "Series" + (it.releaseDate?.take(4)?.let { y -> " · $y" } ?: ""))) }
        }
        hits = out
    }

    Column(Modifier.fillMaxSize().background(Sloopy.ground)) {
        ScreenHeader("Search")
        Column(Modifier.fillMaxSize().padding(horizontal = 72.dp)) {
            Box(Modifier.width(720.dp)) {
                Field("Search channels, programmes, movies and series", q, { q = it }, modifier = Modifier.focusRequester(field), placeholder = "Type to search", backLeaves = true)
            }
            Spacer(Modifier.height(20.dp))
            if (q.trim().length >= 2 && hits.isEmpty()) Text("Nothing on.", style = Sloopy.bodyBig)
            LazyColumn(Modifier.fillMaxSize()) {
                items(hits) { h ->
                    when (h) {
                        is Hit.Chan -> ResultRow(h.c.name, "Channel ${h.c.number}", logo = h.c.logo, kicker = "LIVE") { vm.tune(h.c); go("live") }
                        is Hit.Prog -> ResultRow(h.p.title, (h.c?.name ?: h.p.epgId) + " · " + startLabel(h.p), logo = h.c?.logo, kicker = "GUIDE") {
                            val c = h.c ?: return@ResultRow
                            val now = System.currentTimeMillis() / 1000
                            if (h.p.startEpoch <= now && h.p.stopEpoch > now) { vm.tune(c); go("live") }
                            else if (h.p.stopEpoch <= now && c.archive) { vm.playCatchup(c, h.p.title, h.p.startEpoch, h.p.stopEpoch); go("live") }
                            else go("guide")
                        }
                        is Hit.Item -> ResultRow(h.title, h.sub ?: "", logo = null, kicker = if (h.kind == "vod") "MOVIE" else "SERIES") { onOpen(h.kind, h.id) }
                    }
                }
            }
        }
    }
}

private val DT = DateTimeFormatter.ofPattern("EEE HH:mm")
private fun startLabel(p: ProgrammeEntity) = Instant.ofEpochSecond(p.startEpoch).atZone(ZoneId.systemDefault()).format(DT)

@Composable
private fun ResultRow(title: String, sub: String, logo: String?, kicker: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
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
