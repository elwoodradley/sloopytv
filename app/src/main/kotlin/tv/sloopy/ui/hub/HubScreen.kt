package tv.sloopy.ui.hub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.Clock
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.QuitPrompt
import tv.sloopy.ui.theme.Sloopy

private val ITEMS = listOf(
    "live" to "Live TV", "guide" to "Guide", "movies" to "Movies", "series" to "Series",
    "history" to "History", "search" to "Search", "settings" to "Settings",
)

/**
 * The main menu: Back from the guide lands here, Back from here asks to switch off.
 * The picture keeps playing in the preview so nothing is lost by coming here.
 */
@UnstableApi
@Composable
fun HubScreen(vm: SloopyViewModel, go: (String) -> Unit, onQuit: () -> Unit) {
    val current by vm.currentChannel.collectAsState()
    val pstate by vm.player.state.collectAsState()
    var quit by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    BackHandler { quit = true }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Sloopy.ground)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp)) {
            Column(Modifier.width(560.dp).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("SloopyTV")
                    Spacer(Modifier.weight(1f))
                    Clock()
                }
                Spacer(Modifier.height(28.dp))
                ITEMS.forEachIndexed { i, (route, label) ->
                    var focused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { go(route) },
                        shape = ClickableSurfaceDefaults.shape(RectangleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent, contentColor = Sloopy.muted, focusedContentColor = Color.White),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.fillMaxWidth().height(84.dp)
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { focused = it.isFocused },
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(6.dp).height(44.dp).background(if (focused) Color.White else Color.Transparent))
                                Spacer(Modifier.width(22.dp))
                                Text(label.uppercase(), style = Sloopy.titleBig.copy(fontSize = Sloopy.titleHuge.fontSize, color = if (focused) Color.White else Sloopy.muted), maxLines = 1)
                            }
                            Hairline(color = Sloopy.hair2)
                        }
                    }
                }
            }
            Spacer(Modifier.width(72.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Box(Modifier.fillMaxWidth().height(380.dp).background(Color.Black).drawBehind { drawRect(Sloopy.hair, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())) }) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> PlayerView(ctx).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK); subtitleView?.visibility = android.view.View.GONE; player = vm.player.player } },
                        update = { it.player = vm.player.player },
                    )
                }
                if (current != null) {
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiveDot(); Spacer(Modifier.width(10.dp))
                        Text("${current!!.number}  ${current!!.name.uppercase()}", style = Sloopy.labelBright, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (pstate.quality != null) Text(pstate.quality!!, style = Sloopy.mono14, modifier = Modifier.padding(top = 6.dp))
                }
                Text("Back again switches off. Hold Back anywhere to return to the picture.", style = Sloopy.body.copy(color = Sloopy.dim), modifier = Modifier.padding(top = 22.dp))
            }
        }
        if (quit) QuitPrompt(onQuit = onQuit, onCancel = { quit = false })
    }
}
