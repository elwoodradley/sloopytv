package tv.sloopy.ui.record

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.sloopy.data.db.RecordingEntity
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.ChoicePrompt
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.NavStrip
import tv.sloopy.ui.common.RAIL_W
import tv.sloopy.ui.common.RailHeader
import tv.sloopy.ui.common.RailItem
import tv.sloopy.ui.common.ScreenHeader
import tv.sloopy.ui.common.TimeFmt
import tv.sloopy.ui.theme.Sloopy

/**
 * Recordings, in the app's rail shape: Recorded (finished and failed) and Scheduled (booked,
 * running, and the season-pass games that need a look) down the left, the list on the right.
 * OK on a row opens its choices; a "needs a look" row offers Record or Skip.
 */
@UnstableApi
@Composable
fun RecordingsScreen(vm: SloopyViewModel, go: (String) -> Unit, onBack: () -> Unit, onPlay: () -> Unit) {
    val rows by vm.recordings.collectAsState()
    val unsure by vm.passUnsure.collectAsState()
    val folder by vm.recordFolder.collectAsState()
    var chosen by remember { mutableStateOf<RecordingEntity?>(null) }
    var chosenUnsure by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("recorded") }
    val scope = rememberCoroutineScope()
    val railFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val stripFocus = remember { FocusRequester() }
    var inList by remember { mutableStateOf(false) }
    BackHandler { if (inList) runCatching { railFocus.requestFocus() } else onBack() }

    val recorded = rows.filter { it.status == "done" || it.status == "failed" }
    val scheduled = rows.filter { it.status == "scheduled" || it.status == "recording" }.sortedBy { it.startEpoch }
    val shown = if (page == "recorded") recorded else scheduled
    val pages = listOf("recorded" to "Recorded · ${recorded.size}", "scheduled" to "Scheduled · ${scheduled.size + unsure.size}")
    LaunchedEffect(Unit) { delay(250); runCatching { railFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Sloopy.ground)) {
        ScreenHeader("Recordings")
        Row(Modifier.fillMaxSize().padding(start = 72.dp)) {
            NavStrip("recordings", go = go, requester = stripFocus)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.width(RAIL_W).fillMaxHeight().onFocusChanged { if (it.hasFocus) inList = false }) {
                RailHeader("Recordings")
                pages.forEachIndexed { i, (key, name) ->
                    Box(Modifier.onFocusChanged { if (it.hasFocus) page = key }) {
                        RailItem(name, selected = key == page, requester = if (key == page) railFocus else null, first = i == 0, last = i == pages.lastIndex, leftTarget = stripFocus) {
                            if (shown.isNotEmpty() || (page == "scheduled" && unsure.isNotEmpty())) scope.launch { runCatching { listFocus.requestFocus() } }
                        }
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.fillMaxSize().padding(end = 72.dp, top = 16.dp, bottom = 16.dp)) {
                Label(if (page == "recorded") "Saved to: " + tv.sloopy.record.Recorder.describeFolder(folder) else "Booked recordings and season-pass games waiting for a decision")
                Text(if (page == "recorded") "RECORDED" else "SCHEDULED", style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
                if (page == "recorded" && recorded.isEmpty()) Text("Nothing recorded yet. In the guide, hold OK on a programme to book it; on the picture, OK then Record takes the programme that is on.", style = Sloopy.bodyBig, modifier = Modifier.width(900.dp))
                if (page == "scheduled" && scheduled.isEmpty() && unsure.isEmpty()) Text("Nothing booked. Hold OK on a programme in the guide, or add a team under Settings › Recordings › Season passes.", style = Sloopy.bodyBig, modifier = Modifier.width(900.dp))
                LazyColumn(Modifier.fillMaxSize().focusRequester(listFocus).focusRestorer().focusGroup().onFocusChanged { if (it.hasFocus) inList = true }) {
                    items(shown, key = { "r" + it.id }) { r ->
                        RecordingRow(
                            status = when (r.status) { "scheduled" -> "Scheduled"; "recording" -> "Recording"; "done" -> "Recorded"; else -> "Failed" },
                            live = r.status == "recording",
                            title = r.title,
                            sub = listOfNotNull(r.channelName, TimeFmt.dateTime(r.startEpoch * 1000) + " – " + TimeFmt.hhmm(r.stopEpoch), if (r.bytes > 0) size(r.bytes) else null, r.error).joinToString("  ·  "),
                            action = when (r.status) { "scheduled" -> "CANCEL"; "recording" -> "PLAY · STOP"; "done" -> "PLAY"; else -> "REMOVE" },
                            leftTarget = railFocus,
                            onClick = { chosen = r },
                        )
                    }
                    if (page == "scheduled") items(unsure.take(30), key = { "u$it" }) { e ->
                        val parts = e.split('|')
                        RecordingRow(
                            status = "Needs a look", live = false, amber = true,
                            title = parts.getOrNull(3) ?: e,
                            sub = "Season pass · " + (parts.getOrNull(1)?.toLongOrNull()?.let { TimeFmt.dateTime(it * 1000) } ?: "") + "  ·  ESPN's schedule did not agree on the kickoff",
                            action = "RECORD · SKIP",
                            leftTarget = railFocus,
                            onClick = { chosenUnsure = e },
                        )
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
    chosenUnsure?.let { e ->
        val name = e.split('|').getOrNull(3) ?: "This game"
        ChoicePrompt(name.uppercase().take(28), listOf("rec" to "Record it", "skip" to "Skip it", "no" to "Decide later"), onCancel = { chosenUnsure = null }, onPick = { k ->
            when (k) { "rec" -> vm.resolveUnsure(e, true); "skip" -> vm.resolveUnsure(e, false) }
            chosenUnsure = null
        })
    }
}

@Composable
private fun RecordingRow(status: String, live: Boolean, title: String, sub: String, action: String, leftTarget: FocusRequester, onClick: () -> Unit, amber: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Sloopy.surface2, contentColor = Sloopy.text, focusedContentColor = Color.White),
        border = ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)), shape = RectangleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth().height(84.dp).focusProperties { left = leftTarget },
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(83.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(150.dp)) {
                    Label(status, bright = live || amber)
                    if (live) tv.sloopy.ui.common.LiveDot(Modifier.padding(top = 6.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = Sloopy.listName.copy(color = Color.Unspecified), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = Sloopy.listSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                Text(action, style = Sloopy.label)
            }
            Hairline(color = Sloopy.hair2)
        }
    }
}

private fun size(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%d MB".format(b / 1_000_000)
    else -> "%d KB".format(b / 1000)
}
