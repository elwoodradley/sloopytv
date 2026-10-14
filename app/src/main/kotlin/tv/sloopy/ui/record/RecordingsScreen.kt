package tv.sloopy.ui.record

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.sloopy.data.db.RecordingEntity
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.ChoicePrompt
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.ScreenHeader
import tv.sloopy.ui.common.TimeFmt
import tv.sloopy.ui.theme.Sloopy

/** Scheduled, running and finished recordings. OK opens the choices for one. */
@UnstableApi
@Composable
fun RecordingsScreen(vm: SloopyViewModel, onBack: () -> Unit, onPlay: () -> Unit) {
    val rows by vm.recordings.collectAsState()
    val folder by vm.recordFolder.collectAsState()
    var chosen by remember { mutableStateOf<RecordingEntity?>(null) }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(Sloopy.ground)) {
        ScreenHeader("Recordings")
        Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 16.dp)) {
            Label("Recordings · ${rows.size}  ·  saved to: " + tv.sloopy.record.Recorder.describeFolder(folder))
            Text("RECORDED", style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
            if (rows.isEmpty()) Text("Nothing recorded yet. In the guide, OK on a programme that has not started offers Record. On the picture, OK then Record takes the programme that is on.", style = Sloopy.bodyBig, modifier = Modifier.width(900.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { r ->
                    Surface(
                        onClick = { chosen = r },
                        shape = ClickableSurfaceDefaults.shape(RectangleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.surface2, contentColor = Sloopy.text, focusedContentColor = Color.White),
                        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.fillMaxWidth().height(84.dp),
                    ) {
                        Column {
                            Row(Modifier.fillMaxWidth().height(83.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.width(150.dp)) {
                                    Label(when (r.status) { "scheduled" -> "Scheduled"; "recording" -> "Recording"; "done" -> "Recorded"; else -> "Failed" }, bright = r.status == "recording")
                                    if (r.status == "recording") tv.sloopy.ui.common.LiveDot(Modifier.padding(top = 6.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(r.title, style = Sloopy.listName.copy(color = Color.Unspecified), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(r.channelName, TimeFmt.dateTime(r.startEpoch * 1000) + " – " + TimeFmt.hhmm(r.stopEpoch), if (r.bytes > 0) size(r.bytes) else null, r.error).joinToString("  ·  "), style = Sloopy.listSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(when (r.status) { "scheduled" -> "CANCEL"; "recording" -> "PLAY · STOP"; "done" -> "PLAY"; else -> "REMOVE" }, style = Sloopy.label)
                            }
                            Hairline(color = Sloopy.hair2)
                        }
                    }
                }
            }
        }
    }
    chosen?.let { r ->
        val options = buildList {
            if (r.uri != null && (r.status == "done" || r.status == "recording")) add("play" to "Play")
            if (r.status == "recording") add("stop" to "Stop recording")
            if (r.status == "scheduled") add("cancel" to "Cancel the recording")
            add("delete" to (if (r.status == "done") "Delete the file" else "Remove"))
        }
        ChoicePrompt(r.title.uppercase().take(28), options, onCancel = { chosen = null }, onPick = { k ->
            when (k) { "play" -> { vm.playRecording(r); onPlay() }; "stop" -> vm.stopRecording(r); "cancel", "delete" -> vm.deleteRecording(r) }
            chosen = null
        })
    }
}

private fun size(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%d MB".format(b / 1_000_000)
    else -> "%d KB".format(b / 1000)
}
