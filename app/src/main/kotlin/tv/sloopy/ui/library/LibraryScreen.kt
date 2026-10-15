package tv.sloopy.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.sloopy.data.db.CategoryEntity
import tv.sloopy.data.db.ProgressEntity
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.BACK_KEYS
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.NavStrip
import tv.sloopy.ui.common.ProgressHairline
import tv.sloopy.ui.common.RAIL_W
import tv.sloopy.ui.common.RailHeader
import tv.sloopy.ui.common.RailItem
import tv.sloopy.ui.common.ScreenHeader
import tv.sloopy.ui.common.inKeys
import tv.sloopy.ui.common.isDown
import tv.sloopy.ui.common.isKey
import tv.sloopy.ui.theme.Sloopy

/** One poster's worth of data, whatever table it came from. */
data class Tile(val id: Long, val kind: String, val title: String, val image: String?, val sub: String?, val progress: Float? = null, val container: String = "mp4", val parentId: Long? = null, val resumeMs: Long = 0)

private const val COLS = 5
private val POSTER_W = 170.dp
private val POSTER_H = 255.dp

/**
 * Movies and series, TiviMate-style: categories down the left, the chosen category's posters in
 * a grid on the right. Highlighting a category shows it; OK or Right goes into the posters; Left
 * off the first column or Back comes back out; Back on the rail leaves the screen.
 */
@UnstableApi
@Composable
fun LibraryScreen(vm: SloopyViewModel, kind: String, go: (String) -> Unit, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val provider by vm.active.collectAsState()
    val refresh by vm.refreshState.collectAsState()
    val scope = rememberCoroutineScope()
    var focusedTile by remember { mutableStateOf<Tile?>(null) }
    val railFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    var inRail by remember { mutableStateOf(true) }
    var gridHasFocus by remember { mutableStateOf(false) }
    val lastTile = remember { mutableStateOf<FocusRequester?>(null) }
    val stripFocus = remember { FocusRequester() }
    var inStrip by remember { mutableStateOf(false) }

    val categories by produceState<List<CategoryEntity>>(emptyList(), provider?.id, kind) {
        val p = provider ?: return@produceState
        value = vm.graph.db.categories().byKindOnce(p.id, kind)
    }
    val progress by produceState<List<ProgressEntity>>(emptyList(), provider?.id) {
        val p = provider ?: return@produceState
        vm.graph.db.progress().recent(p.id).collect { value = it }
    }
    val continueTiles = remember(progress, kind) {
        progress.filter { if (kind == "vod") it.kind == "vod" else it.kind == "episode" }
            .map { Tile(if (it.kind == "episode") (it.parentId ?: it.itemId) else it.itemId, if (it.kind == "episode") "series" else "vod", it.title, it.image, it.subtitle, it.positionMs.toFloat() / it.durationMs.coerceAtLeast(1), it.container, it.parentId, it.positionMs) }
    }
    val favIds by (if (kind == "vod") vm.favouriteVodIds else vm.favouriteSeriesIds).collectAsState()
    val total by produceState(0, provider?.id, kind) {
        val p = provider ?: return@produceState
        value = if (kind == "vod") vm.graph.db.vod().count(p.id) else vm.graph.db.series().count(p.id)
    }

    // Rail rows: the shelves first, then the provider's categories.
    val rows = remember(categories, favIds.isNotEmpty(), continueTiles.isNotEmpty()) {
        buildList {
            if (favIds.isNotEmpty()) add("mylist" to "My list")
            if (continueTiles.isNotEmpty()) add("continue" to "Continue watching")
            add("recent" to "Recently added")
            categories.forEach { add("cat-" + it.id to it.name) }
        }
    }
    var selected by remember(rows) { mutableStateOf(vm.librarySelection[kind]?.takeIf { k -> rows.any { it.first == k } } ?: rows.firstOrNull()?.first ?: "recent") }
    LaunchedEffect(selected) { vm.librarySelection[kind] = selected }

    val tiles by produceState<List<Tile>>(emptyList(), selected, provider?.id, kind, favIds, continueTiles) {
        val p = provider ?: return@produceState
        value = when {
            selected == "mylist" -> {
                val ids = favIds.toList()
                if (kind == "vod") vm.graph.db.vod().byIds(p.id, ids).sortedBy { ids.indexOf(it.id) }.map { Tile(it.id, "vod", it.name, it.poster, it.year, container = it.container) }
                else vm.graph.db.series().byIds(p.id, ids).sortedBy { ids.indexOf(it.id) }.map { Tile(it.id, "series", it.name, it.cover, it.releaseDate?.take(4)) }
            }
            selected == "continue" -> continueTiles
            selected == "recent" -> if (kind == "vod") vm.graph.db.vod().recent(p.id, 100).map { Tile(it.id, "vod", it.name, it.poster, it.year, container = it.container) }
                else vm.graph.db.series().recent(p.id, 100).map { Tile(it.id, "series", it.name, it.cover, it.releaseDate?.take(4)) }
            else -> {
                val cat = selected.removePrefix("cat-")
                if (kind == "vod") vm.graph.db.vod().byCategory(p.id, cat).map { Tile(it.id, "vod", it.name, it.poster, it.year, container = it.container) }
                else vm.graph.db.series().byCategory(p.id, cat).map { Tile(it.id, "series", it.name, it.cover, it.releaseDate?.take(4)) }
            }
        }
    }

    suspend fun focusRail() { repeat(20) { if (runCatching { railFocus.requestFocus() }.getOrDefault(false)) return; delay(50) } }
    suspend fun focusGrid() { repeat(20) { if (runCatching { gridFocus.requestFocus() }.getOrDefault(false)) return; delay(50) } }
    LaunchedEffect(Unit) { delay(60); focusRail() }
    BackHandler { if (inRail) onBack() else scope.launch { focusRail() } }

    Column(
        Modifier.fillMaxSize().background(Sloopy.ground)
            .onPreviewKeyEvent { ev ->
                // Back is taken here on key-up, same reason as the guide: a poster swallows the key-down.
                if (ev.inKeys(BACK_KEYS)) { if (!ev.isDown) { if (inRail) onBack() else scope.launch { focusRail() } }; return@onPreviewKeyEvent true }
                if (!ev.isDown) return@onPreviewKeyEvent false
                when {
                    inRail && ev.isKey(Key.DirectionRight) -> { if (tiles.isNotEmpty()) scope.launch { focusGrid() }; true }
                    !inRail && !inStrip && !gridHasFocus && lastTile.value != null && ev.isKey(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight) -> {
                        // Safety net: the highlight went nowhere; put it back on the last poster.
                        runCatching { lastTile.value?.requestFocus() }; true
                    }
                    else -> false
                }
            }
    ) {
        ScreenHeader(if (kind == "vod") "Movies" else "Series")

        // What is highlighted: a poster, or the category itself while on the rail.
        Row(Modifier.fillMaxWidth().height(216.dp).padding(horizontal = 72.dp, vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                val rowName = rows.firstOrNull { it.first == selected }?.second ?: ""
                if (inRail || focusedTile == null) {
                    Label(if (total == 0) "" else "$total titles")
                    Text(rowName.uppercase().ifEmpty { if (refresh is tv.sloopy.data.RefreshState.Running) "LOADING THE SHELF." else "NOTHING ON THE SHELF." }, style = Sloopy.titleHuge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text(if (tiles.isEmpty()) "" else "${tiles.size} titles  ·  Right or OK to browse", style = Sloopy.meta, modifier = Modifier.padding(top = 12.dp))
                    if (total == 0) Text(if (provider?.kind == tv.sloopy.data.ProviderKind.XTREAM) "The library loads after the first full refresh. Check Settings for progress." else "Movies and series come from Xtream providers only.", style = Sloopy.bodyBig, modifier = Modifier.padding(top = 10.dp))
                } else {
                    Label(rowName)
                    Text(focusedTile!!.title, style = Sloopy.titleHuge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text(focusedTile!!.sub ?: "", style = Sloopy.meta, modifier = Modifier.padding(top = 12.dp))
                }
            }
            Spacer(Modifier.width(48.dp))
            Box(Modifier.width(356.dp).height(200.dp).background(Sloopy.surface)) {
                val img = if (!inRail) focusedTile?.image else tiles.firstOrNull()?.image
                if (img != null) AsyncImage(model = img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }

        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp)) {
            NavStrip(if (kind == "vod") "movies" else "series", go = go, requester = stripFocus, onFocusChange = { inStrip = it })
            Spacer(Modifier.width(16.dp))
            Column(Modifier.width(RAIL_W).fillMaxHeight().onFocusChanged { if (it.hasFocus) inRail = true }) {
                RailHeader(if (kind == "vod") "Categories" else "Categories")
                val state = rememberLazyListState()
                val sel = rows.indexOfFirst { it.first == selected }.coerceAtLeast(0)
                LaunchedEffect(rows) { state.scrollToItem((sel - 4).coerceAtLeast(0)) }
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, r -> r.first }) { i, (key, name) ->
                        Box(Modifier.onFocusChanged { if (it.isFocused) selected = key }) {
                            RailItem(name, selected = key == selected, requester = if (key == selected) railFocus else null, first = i == 0, last = i == rows.lastIndex, leftTarget = stripFocus) {
                                if (tiles.isNotEmpty()) scope.launch { focusGrid() }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            val gridState = rememberLazyGridState()
            LaunchedEffect(selected) { gridState.scrollToItem(0) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLS),
                state = gridState,
                modifier = Modifier.fillMaxSize()
                    .focusRequester(gridFocus).focusRestorer().focusGroup()
                    .onFocusChanged { gridHasFocus = it.hasFocus; if (it.hasFocus) inRail = false },
                contentPadding = PaddingValues(top = 10.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(tiles, key = { _, t -> t.kind + t.id }) { i, t ->
                    Box(Modifier.onPreviewKeyEvent { ev ->
                        if (ev.isDown && ev.isKey(Key.DirectionLeft) && i % COLS == 0) { scope.launch { focusRail() }; true } else false
                    }) {
                        Poster(t, onFocus = { focusedTile = t }, onClick = { onOpen(t.id) }, lastTile = lastTile)
                    }
                }
            }
        }
    }
}

@Composable
fun Poster(t: Tile, onFocus: () -> Unit, onClick: () -> Unit, lastTile: androidx.compose.runtime.MutableState<FocusRequester?>? = null) {
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Sloopy.surface2, focusedContainerColor = Sloopy.surface2),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.75f)), shape = RectangleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = Modifier.size(POSTER_W, POSTER_H).focusRequester(fr).onFocusChanged { focused = it.isFocused; if (it.isFocused) { onFocus(); lastTile?.value = fr } },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (t.image != null) AsyncImage(model = t.image, contentDescription = t.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(t.title.uppercase(), style = Sloopy.titleBig.copy(fontSize = Sloopy.titleRow.fontSize), modifier = Modifier.align(Alignment.BottomStart).padding(12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (t.progress != null) ProgressHairline(t.progress, Modifier.fillMaxWidth().align(Alignment.BottomCenter), fill = Sloopy.amber, thickness = 3.dp)
        }
    }
}
