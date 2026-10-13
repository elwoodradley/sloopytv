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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.sloopy.data.db.CategoryEntity
import tv.sloopy.data.db.ProgressEntity
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.ProgressHairline
import tv.sloopy.ui.common.TopNav
import tv.sloopy.ui.theme.Sloopy

/** One poster's worth of data, whatever table it came from. */
data class Tile(val id: Long, val kind: String, val title: String, val image: String?, val sub: String?, val progress: Float? = null, val container: String = "mp4", val parentId: Long? = null, val resumeMs: Long = 0)

@UnstableApi
@Composable
fun LibraryScreen(vm: SloopyViewModel, kind: String, go: (String) -> Unit, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val provider by vm.active.collectAsState()
    val refresh by vm.refreshState.collectAsState()
    var focusedTile by remember { mutableStateOf<Tile?>(null) }
    var focusedRow by remember { mutableStateOf("") }

    BackHandler { onBack() }

    val categories by produceState<List<CategoryEntity>>(emptyList(), provider?.id, kind) {
        val p = provider ?: return@produceState
        value = vm.graph.db.categories().byKindOnce(p.id, kind)
    }
    val recent by produceState<List<Tile>>(emptyList(), provider?.id, kind) {
        val p = provider ?: return@produceState
        value = if (kind == "vod") vm.graph.db.vod().recent(p.id).map { Tile(it.id, "vod", it.name, it.poster, it.year, container = it.container) }
        else vm.graph.db.series().recent(p.id).map { Tile(it.id, "series", it.name, it.cover, it.releaseDate?.take(4)) }
    }
    val progress by vm.graph.db.progress().let { dao ->
        produceState<List<ProgressEntity>>(emptyList(), provider?.id) {
            val p = provider ?: return@produceState
            dao.recent(p.id).collect { value = it }
        }
    }
    val continueTiles = remember(progress, kind) {
        progress.filter { if (kind == "vod") it.kind == "vod" else it.kind == "episode" }
            .map { Tile(if (it.kind == "episode") (it.parentId ?: it.itemId) else it.itemId, if (it.kind == "episode") "series" else "vod", it.title, it.image, it.subtitle, it.positionMs.toFloat() / it.durationMs.coerceAtLeast(1), it.container, it.parentId, it.positionMs) }
    }
    val total by produceState(0, provider?.id, kind) {
        val p = provider ?: return@produceState
        value = if (kind == "vod") vm.graph.db.vod().count(p.id) else vm.graph.db.series().count(p.id)
    }

    Column(Modifier.fillMaxSize().background(Sloopy.ground)) {
        TopNav(current = if (kind == "vod") "movies" else "series", onSelect = go, modifier = Modifier.padding(top = 10.dp))

        // Detail block for the focused poster
        Row(Modifier.fillMaxWidth().height(225.dp).padding(horizontal = 72.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Label(focusedRow)
                Text(focusedTile?.title ?: (if (total == 0) (if (refresh is tv.sloopy.data.RefreshState.Running) "Loading the shelf." else "Nothing on the shelf.") else ""), style = Sloopy.titleHuge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                Text(focusedTile?.sub ?: "", style = Sloopy.meta, modifier = Modifier.padding(top = 12.dp))
                if (total == 0) Text(if (provider?.kind == tv.sloopy.data.ProviderKind.XTREAM) "The library loads after the first full refresh. Check Settings for progress." else "Movies and series come from Xtream providers only.", style = Sloopy.bodyBig, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.width(48.dp))
            Box(Modifier.width(384.dp).height(216.dp).background(Sloopy.surface)) {
                if (focusedTile?.image != null) AsyncImage(model = focusedTile!!.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
            if (continueTiles.isNotEmpty()) item("continue") {
                TileRow("Continue watching", continueTiles, wide = true, onFocus = { focusedTile = it; focusedRow = "Continue watching" }, onOpen = { onOpen(it.id) })
            }
            if (recent.isNotEmpty()) item("recent") {
                TileRow("Recently added", recent, onFocus = { focusedTile = it; focusedRow = "Recently added · $total titles" }, onOpen = { onOpen(it.id) })
            }
            items(categories, key = { "cat-" + it.id }) { cat ->
                CategoryRow(vm, kind, cat, onFocus = { focusedTile = it; focusedRow = cat.name }, onOpen = { onOpen(it.id) })
            }
        }
    }
}

@UnstableApi
@Composable
private fun CategoryRow(vm: SloopyViewModel, kind: String, cat: CategoryEntity, onFocus: (Tile) -> Unit, onOpen: (Tile) -> Unit) {
    val provider by vm.active.collectAsState()
    val tiles by produceState<List<Tile>>(emptyList(), cat.id) {
        val p = provider ?: return@produceState
        value = if (kind == "vod") vm.graph.db.vod().byCategory(p.id, cat.id, 60).map { Tile(it.id, "vod", it.name, it.poster, it.year, container = it.container) }
        else vm.graph.db.series().byCategory(p.id, cat.id, 60).map { Tile(it.id, "series", it.name, it.cover, it.releaseDate?.take(4)) }
    }
    if (tiles.isNotEmpty()) TileRow(cat.name, tiles, onFocus = onFocus, onOpen = onOpen)
}

@Composable
fun TileRow(title: String, tiles: List<Tile>, wide: Boolean = false, onFocus: (Tile) -> Unit, onOpen: (Tile) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(Modifier.padding(start = 72.dp).height(36.dp), verticalAlignment = Alignment.Bottom) {
            Text(title.uppercase(), style = Sloopy.titleRow)
            Spacer(Modifier.width(14.dp))
            Label("${tiles.size}")
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 72.dp, vertical = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
            items(tiles, key = { it.kind + it.id }) { t ->
                Poster(t, wide = wide, onFocus = { onFocus(t) }, onClick = { onOpen(t) })
            }
        }
    }
}

@Composable
fun Poster(t: Tile, wide: Boolean = false, onFocus: () -> Unit, onClick: () -> Unit) {
    val w = if (wide) 246.dp else 158.dp
    val h = if (wide) 138.dp else 237.dp
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Sloopy.surface2, focusedContainerColor = Sloopy.surface2),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.75f)), shape = RectangleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = Modifier.size(w, h).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (t.image != null) AsyncImage(model = t.image, contentDescription = t.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(t.title.uppercase(), style = Sloopy.titleBig.copy(fontSize = Sloopy.titleRow.fontSize), modifier = Modifier.align(Alignment.BottomStart).padding(12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (t.progress != null) ProgressHairline(t.progress, Modifier.fillMaxWidth().align(Alignment.BottomCenter), fill = Sloopy.amber, thickness = 3.dp)
        }
    }
}
