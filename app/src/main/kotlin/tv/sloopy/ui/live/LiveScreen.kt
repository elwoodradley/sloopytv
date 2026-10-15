package tv.sloopy.ui.live

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
import androidx.compose.ui.focus.onFocusChanged
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
import tv.sloopy.data.db.ChannelEntity
import tv.sloopy.data.db.ProgrammeEntity
import tv.sloopy.ui.Playing
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.BACK_KEYS
import tv.sloopy.ui.common.Clock
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.OK_KEYS
import tv.sloopy.ui.common.ProgressHairline
import tv.sloopy.ui.common.QuitPrompt
import tv.sloopy.ui.common.TopNav
import tv.sloopy.ui.common.SloopyButton
import tv.sloopy.ui.common.digit
import tv.sloopy.ui.common.isDown
import tv.sloopy.ui.common.isKey
import tv.sloopy.ui.common.inKeys
import tv.sloopy.ui.theme.Sloopy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Overlay { NONE, OSD, NUMBER, CHANNELS, DECK, QUIT, INFO }

@UnstableApi
@Composable
fun LiveScreen(vm: SloopyViewModel, go: (String) -> Unit, onQuit: () -> Unit, onBack: () -> Unit = { go("guide") }) {
    val playing by vm.playing.collectAsState()
    val pstate by vm.player.state.collectAsState()
    val current by vm.currentChannel.collectAsState()
    val osdSeconds by vm.osdSeconds.collectAsState()
    val channels by vm.channels.collectAsState()

    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var digits by remember { mutableStateOf("") }
    var osdTick by remember { mutableStateOf(0) }
    val rootFocus = remember { FocusRequester() }

    var rootFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.resumeLastChannel() }
    // The root must own focus for D-pad keys to arrive. Retry briefly: after the keyboard or a
    // navigation pop, the first request can land before the node is attached.
    LaunchedEffect(overlay) {
        if (overlay == Overlay.NONE || overlay == Overlay.OSD || overlay == Overlay.NUMBER) {
            repeat(8) { if (rootFocused || vm.modalUp) return@LaunchedEffect; runCatching { rootFocus.requestFocus() }; delay(60) }
        }
    }
    LaunchedEffect(channels.size) { if (current == null && channels.isNotEmpty()) vm.resumeLastChannel() }

    // Bare OSD on every channel change, then gone.
    LaunchedEffect(current?.id) { if (current != null && overlay == Overlay.NONE) { overlay = Overlay.OSD; osdTick++ } }
    LaunchedEffect(overlay, osdTick) {
        if (overlay == Overlay.OSD) { delay(osdSeconds * 1000L); if (overlay == Overlay.OSD) overlay = Overlay.NONE }
    }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1800)
            val n = digits.toIntOrNull()
            digits = ""
            overlay = Overlay.NONE
            if (n == null || !vm.tuneNumber(n)) vm.toast("No channel $n")
        }
    }

    // Back on the bare picture opens the guide; on any overlay it just closes the overlay.
    // Holding Back anywhere comes back here with everything closed (see MainActivity).
    BackHandler {
        digits = ""
        if (overlay == Overlay.NONE) onBack() else overlay = Overlay.NONE
    }
    LaunchedEffect(Unit) { vm.homeEvents.collect { overlay = Overlay.NONE; digits = "" } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (overlay == Overlay.CHANNELS || overlay == Overlay.DECK || overlay == Overlay.QUIT || overlay == Overlay.INFO) return@onPreviewKeyEvent false
                // OK opens the info overlay on release, so a held button (and the repeats a phone
                // remote sends while held) cannot land on the overlay and switch channels.
                if (ev.inKeys(OK_KEYS)) { if (!ev.isDown) overlay = Overlay.INFO; return@onPreviewKeyEvent true }
                if (!ev.isDown) return@onPreviewKeyEvent false
                val d = ev.digit
                val catchup = playing is Playing.Catchup
                when {
                    d != null -> { digits = (digits + d).takeLast(4); overlay = Overlay.NUMBER; true }
                    ev.isKey(Key.DirectionUp, Key.ChannelUp) -> { vm.step(-1); true }
                    ev.isKey(Key.DirectionDown, Key.ChannelDown) -> { vm.step(1); true }
                    // Catch-up: Left/Right skip 30 s, rewind/fast-forward skip 2 min; the badge shows where you are.
                    catchup && ev.isKey(Key.DirectionLeft, Key.MediaRewind) -> { val d = if (ev.isKey(Key.DirectionLeft)) -30_000L else -120_000L; vm.seekBy(d); vm.noteSeek(d); overlay = Overlay.OSD; osdTick++; true }
                    catchup && ev.isKey(Key.DirectionRight, Key.MediaFastForward) -> { val d = if (ev.isKey(Key.DirectionRight)) 30_000L else 120_000L; vm.seekBy(d); vm.noteSeek(d); overlay = Overlay.OSD; osdTick++; true }
                    ev.isKey(Key.DirectionLeft) -> { overlay = Overlay.CHANNELS; true }
                    ev.isKey(Key.DirectionRight, Key.Menu) -> { overlay = Overlay.DECK; true }
                    ev.isKey(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause) -> {
                        val p = vm.player.player; if (p.isPlaying) p.pause() else p.play(); true
                    }
                    ev.isKey(Key.Guide) -> { go("guide"); true }
                    else -> false
                }
            }
    ) {
        PlayerSurface(vm)

        if (pstate.error != null && overlay != Overlay.INFO) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 56.dp)) {
                Text("CAN'T PLAY", style = Sloopy.meta)
                Spacer(Modifier.height(6.dp))
                Text(pstate.error!!, style = Sloopy.bodyBig.copy(color = Sloopy.text))
            }
        } else if (pstate.reconnecting || pstate.isBuffering) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 72.dp, bottom = 56.dp)) {
                Text(if (pstate.reconnecting) "HANG ON SLOOPY" else "HANG ON", style = Sloopy.label)
            }
        }

        when (overlay) {
            Overlay.OSD -> current?.let { Osd(vm, it, playing, pstate.quality) }
            Overlay.INFO -> current?.let { InfoOverlay(vm, it, playing, pstate.quality, onClose = { overlay = Overlay.NONE }, go = go, openChannels = { overlay = Overlay.CHANNELS }) }
            Overlay.NUMBER -> NumberEntry(digits, channels.firstOrNull { it.number == digits.toIntOrNull() })
            Overlay.CHANNELS -> ChannelsOverlay(vm, onClose = { overlay = Overlay.NONE })
            Overlay.DECK -> Deck(go = { overlay = Overlay.NONE; go(it) }, onClose = { overlay = Overlay.NONE })
            Overlay.QUIT -> QuitPrompt(onQuit = onQuit, onCancel = { overlay = Overlay.NONE })
            Overlay.NONE -> {}
        }
    }
}

@UnstableApi
@Composable
private fun PlayerSurface(vm: SloopyViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(false)
                player = vm.player.player
            }
        },
        update = { it.player = vm.player.player },
    )
}

@UnstableApi
@Composable
internal fun Osd(vm: SloopyViewModel, channel: ChannelEntity, playing: Playing?, quality: String?) {
    var nowNext by remember(channel.id) { mutableStateOf<List<ProgrammeEntity>>(emptyList()) }
    val provider by vm.active.collectAsState()
    LaunchedEffect(channel.id) {
        val p = provider ?: return@LaunchedEffect
        val epg = channel.epgId ?: return@LaunchedEffect
        nowNext = runCatching { vm.catalog.nowAndNext(p.id, epg, System.currentTimeMillis() / 1000) }.getOrDefault(emptyList())
    }
    val now = System.currentTimeMillis() / 1000
    val cur = (playing as? Playing.Catchup)?.let { ProgrammeEntity(0, "", 0, "", it.startEpoch, it.stopEpoch, it.title, null, null, null, null) }
        ?: nowNext.firstOrNull { it.startEpoch <= now && it.stopEpoch > now }
    val next = nowNext.firstOrNull { it.startEpoch > now }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopStart).padding(start = 72.dp, top = 54.dp)) {
            Text(channel.number.toString(), style = Sloopy.mono16.copy(color = Color.White.copy(alpha = 0.72f), letterSpacing = androidx.compose.ui.unit.TextUnit(0.12f, androidx.compose.ui.unit.TextUnitType.Em)))
            Text(channel.name.uppercase(), style = Sloopy.osdName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).width(900.dp))
            if (cur != null && playing is Playing.Catchup) {
                // Catch-up: a position bar that follows the player, with the last skip shown beside it.
                val seek by vm.lastSeek.collectAsState()
                var posMs by remember { mutableStateOf(vm.player.position()) }
                LaunchedEffect(Unit) { while (true) { posMs = vm.player.position(); delay(500) } }
                val lenMs = (cur.stopEpoch - cur.startEpoch) * 1000L
                Text("CATCH-UP · " + cur.title + " · " + fmt(cur.startEpoch) + " – " + fmt(cur.stopEpoch), style = Sloopy.bodyBig.copy(color = Color.White.copy(alpha = 0.8f)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).width(900.dp))
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtMs(posMs), style = Sloopy.mono16.copy(color = Color.White))
                    Spacer(Modifier.width(14.dp))
                    ProgressHairline(fraction = posMs.toFloat() / lenMs.coerceAtLeast(1), modifier = Modifier.width(520.dp), thickness = 4.dp, fill = Sloopy.amber)
                    Spacer(Modifier.width(14.dp))
                    Text(fmtMs(lenMs), style = Sloopy.mono16.copy(color = Color.White.copy(alpha = 0.6f)))
                    if (seek != null) { Spacer(Modifier.width(18.dp)); Text((if (seek!! > 0) "+" else "−") + (kotlin.math.abs(seek!!) / 1000) + " s", style = Sloopy.labelBright.copy(color = Sloopy.amber)) }
                }
                Text("Left / Right 30 s · Rewind / Fast-forward 2 min", style = Sloopy.label.copy(color = Color.White.copy(alpha = 0.45f)), modifier = Modifier.padding(top = 8.dp))
            } else if (cur != null) {
                Text(
                    cur.title + " · " + fmt(cur.startEpoch) + " – " + fmt(cur.stopEpoch),
                    style = Sloopy.bodyBig.copy(color = Color.White.copy(alpha = 0.8f)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp).width(900.dp),
                )
                ProgressHairline(
                    fraction = ((now - cur.startEpoch).toFloat() / (cur.stopEpoch - cur.startEpoch).coerceAtLeast(1)),
                    modifier = Modifier.padding(top = 10.dp).width(240.dp),
                )
                if (next != null) Text("Next · " + next.title + " · " + fmt(next.startEpoch), style = Sloopy.listSub.copy(color = Color.White.copy(alpha = 0.55f)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).width(900.dp))
            }
            if (quality != null) Text(quality, style = Sloopy.mono14.copy(color = Color.White.copy(alpha = 0.6f)), modifier = Modifier.padding(top = 12.dp))
        }
        Row(Modifier.align(Alignment.TopEnd).padding(end = 72.dp, top = 62.dp), verticalAlignment = Alignment.CenterVertically) {
            if (playing is Playing.Live) { LiveDot(); Spacer(Modifier.width(9.dp)); Text("LIVE", style = Sloopy.labelBright.copy(color = Color.White.copy(alpha = 0.8f))) }
            Spacer(Modifier.width(20.dp))
            Clock(style = Sloopy.clock.copy(color = Color.White.copy(alpha = 0.72f)))
        }
    }
}

@Composable
private fun NumberEntry(digits: String, match: ChannelEntity?) {
    Column(Modifier.padding(start = 72.dp, top = 54.dp)) {
        Text(digits, style = Sloopy.osdName.copy(fontFamily = Sloopy.mono, fontSize = androidx.compose.ui.unit.TextUnit(48f, androidx.compose.ui.unit.TextUnitType.Sp)))
        Text(match?.name?.uppercase() ?: "", style = Sloopy.labelBright, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Deck(go: (String) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to Color(0xEE0B0B0C), 0.4f to Color.Transparent))
            .onPreviewKeyEvent { if (it.isDown && it.inKeys(BACK_KEYS)) { onClose(); true } else false }
    ) {
        Box(Modifier.focusRequester(first)) {
            TopNav(current = "live", onSelect = { if (it == "live") onClose() else go(it) }, modifier = Modifier.padding(top = 24.dp))
        }
    }
}

internal fun fmt(epoch: Long): String = tv.sloopy.ui.common.TimeFmt.hhmm(epoch)

private fun fmtMs(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
