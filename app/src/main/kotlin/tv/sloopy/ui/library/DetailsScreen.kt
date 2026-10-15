package tv.sloopy.ui.library

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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.sloopy.TLog
import tv.sloopy.core.model.Episode
import tv.sloopy.core.model.SeriesInfo
import tv.sloopy.core.model.VodInfo
import tv.sloopy.core.xtream.XtreamApi
import tv.sloopy.data.AndroidHttp
import tv.sloopy.data.ProviderKind
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.SloopyButton
import tv.sloopy.ui.theme.Sloopy

@UnstableApi
@Composable
fun DetailsScreen(vm: SloopyViewModel, kind: String, id: Long, onBack: () -> Unit, onPlay: () -> Unit) {
    val provider by vm.active.collectAsState()
    val favVod by vm.favouriteVodIds.collectAsState()
    val favSeries by vm.favouriteSeriesIds.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val playFocus = remember { FocusRequester() }
    BackHandler { onBack() }

    val api = remember(provider?.id) { provider?.takeIf { it.kind == ProviderKind.XTREAM }?.let { XtreamApi(AndroidHttp.core, it.xtream()) } }

    if (kind == "vod") {
        val base by produceState<tv.sloopy.data.db.VodEntity?>(null, id) { value = provider?.let { vm.graph.db.vod().byId(it.id, id) } }
        val info by produceState<VodInfo?>(null, id, api) {
            value = runCatching { api?.vodInfo(id) }.onFailure { error = TLog.userMessage(it) }.getOrNull()
        }
        val resume by produceState<Long>(0L, id) { value = provider?.let { vm.graph.db.progress().get(it.id, "vod", id)?.positionMs } ?: 0L }
        val title = info?.name ?: base?.name ?: ""
        val image = info?.backdrop ?: info?.poster ?: base?.poster
        LaunchedEffect(base) { if (base != null) runCatching { playFocus.requestFocus() } }

        Backdrop(image) {
            Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp)) {
                Label("Movie" + (base?.year?.let { " · $it" } ?: ""))
                Text(title, style = Sloopy.titleHuge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).width(900.dp))
                val meta = listOfNotNull(
                    info?.durationSecs?.let { "${it / 3600} h ${(it % 3600) / 60} min" },
                    info?.genre, info?.releaseDate?.take(4), info?.rating?.let { "★ $it" },
                    base?.container?.uppercase(),
                ).joinToString("  ·  ")
                Text(meta, style = Sloopy.meta, modifier = Modifier.padding(top = 14.dp))
                Text(info?.plot ?: "", style = Sloopy.bodyBig, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp).width(820.dp))
                if (info?.cast != null) Text("Cast: ${info!!.cast}", style = Sloopy.body.copy(color = Sloopy.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).width(820.dp))
                if (error != null) Text(error!!, style = Sloopy.body.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(28.dp))
                Row {
                    val b = base
                    val onList = id in favVod
                    SloopyButton(if (resume > 0) "Resume · ${fmtMs(resume)}" else "Play", primary = true, enabled = b != null, modifier = Modifier.focusRequester(playFocus), onClick = {
                        if (b != null) { if (vm.playVod(b.id, title, b.year, image, info?.container ?: b.container, "vod", null, resume)) onPlay() }
                    })
                    if (resume > 0) {
                        Spacer(Modifier.width(10.dp))
                        SloopyButton("From the start", onClick = { val bb = base ?: return@SloopyButton; if (vm.playVod(bb.id, title, bb.year, image, info?.container ?: bb.container, "vod", null, 0)) onPlay() })
                    }
                    if (b != null && vm.vlcInstalled()) {
                        Spacer(Modifier.width(10.dp))
                        SloopyButton("Open in VLC", onClick = { vm.openVodInVlc(b.id, info?.container ?: b.container, "vod", title) })
                    }
                    Spacer(Modifier.width(10.dp))
                    SloopyButton(if (onList) "On your list" else "Add to list", onClick = { vm.toggleItemFavourite("vod", id) })
                }
            }
        }
    } else {
        val base by produceState<tv.sloopy.data.db.SeriesEntity?>(null, id) { value = provider?.let { vm.graph.db.series().byId(it.id, id) } }
        val info by produceState<SeriesInfo?>(null, id, api) {
            value = runCatching { api?.seriesInfo(id) }.onFailure { error = TLog.userMessage(it) }.getOrNull()
        }
        var season by remember { mutableStateOf<Int?>(null) }
        val latest by produceState<tv.sloopy.data.db.ProgressEntity?>(null, id) { value = provider?.let { vm.graph.db.progress().latestEpisode(it.id, id) } }
        LaunchedEffect(info) { if (season == null) season = info?.seasons?.firstOrNull() }
        val title = info?.series?.name ?: base?.name ?: ""
        val image = info?.series?.backdrop ?: base?.backdrop ?: base?.cover
        val episodes = season?.let { s -> info?.episodes?.get(s) } ?: emptyList()

        Backdrop(image) {
            Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp)) {
                Column(Modifier.width(720.dp)) {
                    Label("Series" + (base?.releaseDate?.take(4)?.let { " · $it" } ?: "") + (info?.seasons?.size?.let { " · $it seasons" } ?: ""))
                    Text(title, style = Sloopy.titleHuge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text(listOfNotNull(base?.genre, base?.rating?.let { "★ $it" }).joinToString("  ·  "), style = Sloopy.meta, modifier = Modifier.padding(top = 14.dp))
                    Text(info?.series?.plot ?: base?.plot ?: "", style = Sloopy.bodyBig, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                    if (error != null) Text(error!!, style = Sloopy.body.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(20.dp))
                    SloopyButton(if (id in favSeries) "On your list" else "Add to list", onClick = { vm.toggleItemFavourite("series", id) })
                    Spacer(Modifier.height(14.dp))
                    Row {
                        info?.seasons?.take(12)?.forEach { s ->
                            SloopyButton("S$s", primary = s == season, onClick = { season = s }, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                Spacer(Modifier.width(48.dp))
                LazyColumn(Modifier.weight(1f).fillMaxSize()) {
                    items(episodes, key = { it.id }) { ep ->
                        val watched = latest?.takeIf { it.itemId == ep.id }
                        EpisodeRow(ep, watched?.positionMs ?: 0, watched?.durationMs ?: 0, onClick = {
                            vm.setEpisodeQueue(id, title, image, info?.seasons?.flatMap { sn -> info?.episodes?.get(sn) ?: emptyList() } ?: emptyList())
                            if (vm.playVod(ep.id, title, "S${ep.season} E${ep.number} · ${ep.title}", ep.image ?: image, ep.container, "episode", id, watched?.positionMs ?: 0)) onPlay()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun Backdrop(image: String?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Sloopy.ground)) {
        if (image != null) AsyncImage(model = image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Sloopy.ground, 0.55f to Color(0xCC0B0B0C), 1f to Color(0x660B0B0C))))
        content()
    }
}

@Composable
private fun EpisodeRow(ep: Episode, posMs: Long, durMs: Long, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.surface2, contentColor = Sloopy.text, focusedContentColor = Color.White),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(63.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("E%02d".format(ep.number), style = Sloopy.mono16.copy(color = Sloopy.muted), modifier = Modifier.width(52.dp))
                Column(Modifier.weight(1f)) {
                    Text(ep.title, style = Sloopy.listName.copy(color = Color.Unspecified), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(ep.durationSecs?.let { "${it / 60} min" }, ep.airDate, if (posMs > 0 && durMs > 0) "${(posMs * 100 / durMs)}% watched" else null).joinToString("  ·  "), style = Sloopy.listSub)
                }
            }
            Hairline(color = Sloopy.hair2)
        }
    }
}

internal fun fmtMs(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
