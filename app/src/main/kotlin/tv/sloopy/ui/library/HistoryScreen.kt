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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import tv.sloopy.data.db.ProgressEntity
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.ProgressHairline
import tv.sloopy.ui.common.ScreenHeader
import tv.sloopy.ui.theme.Sloopy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Everything started and not finished, newest first. OK resumes; hold OK forgets it. */
@UnstableApi
@Composable
fun HistoryScreen(vm: SloopyViewModel, go: (String) -> Unit, onBack: () -> Unit, onPlay: () -> Unit) {
    val provider by vm.active.collectAsState()
    BackHandler { onBack() }
    val rows by produceState<List<ProgressEntity>>(emptyList(), provider?.id) {
        val p = provider ?: return@produceState
        vm.graph.db.progress().recent(p.id, 200).collect { value = it }
    }

    Column(Modifier.fillMaxSize().background(Sloopy.ground)) {
        ScreenHeader("History")
        Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 16.dp)) {
            Label("History · ${rows.size}")
            Text("WHERE YOU LEFT OFF", style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
            if (rows.isEmpty()) Text("Nothing on.", style = Sloopy.bodyBig)
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.kind + it.itemId }) { r ->
                    val pct = (r.positionMs * 100 / r.durationMs.coerceAtLeast(1)).toInt()
                    Surface(
                        onClick = {
                            vm.playVod(r.itemId, r.title, r.subtitle, r.image, r.container, r.kind, r.parentId, r.positionMs)
                            onPlay()
                        },
                        onLongClick = { vm.forgetProgress(r) },
                        shape = ClickableSurfaceDefaults.shape(RectangleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.surface2, contentColor = Sloopy.text, focusedContentColor = Color.White),
                        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                    ) {
                        Column {
                            Row(Modifier.fillMaxWidth().height(95.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(128.dp).height(72.dp).background(Sloopy.surface)) {
                                    if (r.image != null) AsyncImage(model = r.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    ProgressHairline(pct / 100f, Modifier.fillMaxWidth().align(Alignment.BottomCenter), fill = Sloopy.amber, thickness = 3.dp)
                                }
                                Spacer(Modifier.width(20.dp))
                                Column(Modifier.weight(1f)) {
                                    Label(if (r.kind == "episode") "Episode" else "Movie")
                                    Text(r.title, style = Sloopy.listName.copy(color = Color.Unspecified), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                    Text(listOfNotNull(r.subtitle, "$pct% watched", tv.sloopy.ui.common.TimeFmt.dateTime(r.updatedAt)).joinToString("  ·  "), style = Sloopy.listSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("RESUME", style = Sloopy.label)
                            }
                            Hairline(color = Sloopy.hair2)
                        }
                    }
                }
            }
        }
    }
}
