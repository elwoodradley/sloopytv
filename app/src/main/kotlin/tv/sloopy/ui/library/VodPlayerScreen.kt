package tv.sloopy.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.sloopy.ui.Playing
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.OK_KEYS
import tv.sloopy.ui.common.ProgressHairline
import tv.sloopy.ui.common.isDown
import tv.sloopy.ui.common.isKey
import tv.sloopy.ui.common.inKeys
import tv.sloopy.ui.theme.Sloopy

/** Movies, episodes and catch-up: a bare transport. OK pauses, left/right seek, back leaves. */
@UnstableApi
@Composable
fun VodPlayerScreen(vm: SloopyViewModel, onBack: () -> Unit) {
    val playing by vm.playing.collectAsState()
    val state by vm.player.state.collectAsState()
    var showBar by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    // Series: when an episode ends, offer the next one with an eight-second countdown.
    val v = playing as? Playing.Vod
    val next = remember(v?.id) { if (v?.kind == "episode") vm.nextEpisodeAfter(v.id) else null }
    var countdown by remember { mutableStateOf(-1) }
    val playNext: () -> Unit = {
        countdown = -1
        val series = vm.episodeQueueSeries
        if (next != null && series != null) {
            vm.playVod(next.id, series.second, "S${next.season} E${next.number} · ${next.title}", next.image ?: vm.episodeQueueImage, next.container, "episode", series.first, 0)
            showBar = true; tick++
        }
    }
    LaunchedEffect(v?.id) { countdown = -1 }
    LaunchedEffect(state.ended, v?.id) {
        if (state.ended && next != null) {
            vm.saveProgress()
            countdown = 8
            while (countdown > 0) { delay(1000); if (countdown > 0) countdown-- }
            if (countdown == 0) playNext()
        }
    }
    LaunchedEffect(Unit) { while (true) { pos = vm.player.position(); dur = vm.player.duration(); delay(500) } }
    // Counts four seconds of actual playback before hiding, so a bar shown while the stream was
    // still buffering (or paused) still goes away once it plays, instead of staying up for the film.
    LaunchedEffect(tick, showBar) {
        if (!showBar) return@LaunchedEffect
        var played = 0
        while (played < 4000) { delay(250); if (vm.player.player.isPlaying) played += 250 }
        showBar = false
    }

    val leave = {
        vm.saveProgress()
        vm.stopPlayback()
        onBack()
    }
    BackHandler { leave() }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (!ev.isDown) return@onPreviewKeyEvent false
                val p = vm.player.player
                fun seek(ms: Long) { p.seekTo((p.currentPosition + ms).coerceIn(0, dur.coerceAtLeast(0))); showBar = true; tick++ }
                when {
                    countdown >= 0 && ev.inKeys(OK_KEYS) -> { playNext(); true }
                    countdown >= 0 && ev.inKeys(tv.sloopy.ui.common.BACK_KEYS) -> { countdown = -1; true }
                    (ev.inKeys(OK_KEYS) || ev.isKey(Key.MediaPlayPause)) -> { if (p.isPlaying) p.pause() else p.play(); showBar = true; tick++; true }
                    ev.isKey(Key.MediaPlay) -> { p.play(); true }
                    ev.isKey(Key.MediaPause) -> { p.pause(); showBar = true; true }
                    ev.isKey(Key.DirectionLeft, Key.MediaRewind) -> { seek(-10_000); true }
                    ev.isKey(Key.DirectionRight, Key.MediaFastForward) -> { seek(10_000); true }
                    ev.isKey(Key.DirectionDown) -> { seek(-60_000); true }
                    ev.isKey(Key.DirectionUp) -> { seek(60_000); true }
                    ev.isKey(Key.MediaStop) -> { leave(); true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> PlayerView(ctx).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK); player = vm.player.player } },
            update = { it.player = vm.player.player },
        )
        if (countdown >= 0 && next != null) {
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 72.dp, bottom = 150.dp).background(Color(0xE60B0B0C)).padding(24.dp)) {
                Text("NEXT UP", style = Sloopy.label)
                Text("S${next.season} E${next.number} · ${next.title}", style = Sloopy.listName.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).width(520.dp))
                Text("Playing in $countdown", style = Sloopy.titleBig.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 8.dp))
                Text("OK plays now · Back cancels", style = Sloopy.label.copy(color = Sloopy.dim), modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (showBar || !state.isPlaying) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC000000)))) {
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 72.dp, vertical = 48.dp).fillMaxWidth()) {
                    Text(v?.title?.uppercase() ?: "", style = Sloopy.osdName.copy(fontSize = Sloopy.titleBig.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (v?.subtitle != null) Text(v.subtitle, style = Sloopy.bodyBig.copy(color = Color.White.copy(alpha = 0.8f)), modifier = Modifier.padding(top = 6.dp))
                    if (state.quality != null) Text(state.quality!!, style = Sloopy.mono14.copy(color = Color.White.copy(alpha = 0.6f)), modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtMs(pos), style = Sloopy.mono16.copy(color = Color.White))
                        Spacer(Modifier.width(16.dp))
                        ProgressHairline(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.weight(1f), fill = Color.White, track = Color(0x40FFFFFF), thickness = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(fmtMs(dur), style = Sloopy.mono16.copy(color = Color.White.copy(alpha = 0.7f)))
                    }
                    Text(if (!state.isPlaying && !state.isBuffering) "PAUSED" else if (state.reconnecting) "HANG ON SLOOPY" else if (state.isBuffering) "HANG ON" else "", style = Sloopy.label.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 10.dp).height(16.dp))
                    if (state.error != null) Text(state.error!!, style = Sloopy.bodyBig.copy(color = Sloopy.amber))
                }
            }
        }
    }
}
