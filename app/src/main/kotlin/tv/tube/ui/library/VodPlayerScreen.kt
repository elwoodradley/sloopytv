package tv.tube.ui.library

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
import tv.tube.ui.Playing
import tv.tube.ui.TubeViewModel
import tv.tube.ui.common.OK_KEYS
import tv.tube.ui.common.ProgressHairline
import tv.tube.ui.common.isDown
import tv.tube.ui.common.isKey
import tv.tube.ui.common.inKeys
import tv.tube.ui.theme.Tube

/** Movies, episodes and catch-up: a bare transport. OK pauses, left/right seek, back leaves. */
@UnstableApi
@Composable
fun VodPlayerScreen(vm: TubeViewModel, onBack: () -> Unit) {
    val playing by vm.playing.collectAsState()
    val state by vm.player.state.collectAsState()
    var showBar by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(Unit) { while (true) { pos = vm.player.position(); dur = vm.player.duration(); delay(500) } }
    LaunchedEffect(tick, showBar) { if (showBar && vm.player.player.isPlaying) { delay(4000); showBar = false } }

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
        if (showBar || !state.isPlaying) {
            val v = playing as? Playing.Vod
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC000000)))) {
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 72.dp, vertical = 48.dp).fillMaxWidth()) {
                    Text(v?.title?.uppercase() ?: "", style = Tube.osdName.copy(fontSize = Tube.titleBig.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (v?.subtitle != null) Text(v.subtitle, style = Tube.bodyBig.copy(color = Color.White.copy(alpha = 0.8f)), modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtMs(pos), style = Tube.mono16.copy(color = Color.White))
                        Spacer(Modifier.width(16.dp))
                        ProgressHairline(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.weight(1f), fill = Color.White, track = Color(0x40FFFFFF), thickness = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(fmtMs(dur), style = Tube.mono16.copy(color = Color.White.copy(alpha = 0.7f)))
                    }
                    Text(if (!state.isPlaying && !state.isBuffering) "PAUSED" else if (state.isBuffering) "BUFFERING" else "", style = Tube.label.copy(color = Tube.amber), modifier = Modifier.padding(top = 10.dp).height(16.dp))
                    if (state.error != null) Text(state.error!!, style = Tube.bodyBig.copy(color = Tube.amber))
                }
            }
        }
    }
}
