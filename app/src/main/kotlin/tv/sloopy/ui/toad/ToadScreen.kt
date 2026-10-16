package tv.sloopy.ui.toad

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.NavStrip
import tv.sloopy.ui.common.QrCode
import tv.sloopy.ui.common.ScreenHeader
import tv.sloopy.ui.common.SloopyButton
import tv.sloopy.ui.settings.Field
import tv.sloopy.ui.theme.Sloopy

/**
 * Ask the Toad: say or type one request, see the plan it made, OK runs it. Nothing happens
 * without that OK. The phone's remote page has its own box that lands here.
 */
@UnstableApi
@Composable
fun ToadScreen(vm: SloopyViewModel, go: (String) -> Unit, onBack: () -> Unit) {
    val busy by vm.toadBusy.collectAsState()
    val plan by vm.toadPlan.collectAsState()
    val error by vm.toadError.collectAsState()
    val recent by vm.prefs.toadRecent.collectAsState(initial = emptyList())
    val remoteAddress by vm.remoteAddress.collectAsState()
    var typed by remember { mutableStateOf("") }
    val speakFocus = remember { FocusRequester() }
    val doFocus = remember { FocusRequester() }
    BackHandler { if (plan != null || error != null) vm.toadDismiss() else onBack() }
    LaunchedEffect(Unit) { delay(250); runCatching { speakFocus.requestFocus() } }
    LaunchedEffect(plan) { if (plan != null) { delay(150); runCatching { doFocus.requestFocus() } } }

    val context = LocalContext.current
    val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) { typed = said; vm.toadAsk(said) }
    }
    fun startSpeaking() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell the Toad what to do")
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        if (intent.resolveActivity(context.packageManager) == null) { vm.toast("This box has no speech recogniser the app can use. Type on your phone instead."); return }
        runCatching { speak.launch(intent) }.onFailure { vm.toast("Could not start the microphone") }
    }

    Column(Modifier.fillMaxSize().background(Sloopy.ground)) {
        ScreenHeader("Toad")
        Row(Modifier.fillMaxSize().padding(start = 72.dp)) {
            NavStrip("toad", go = go)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.fillMaxSize().padding(end = 72.dp, top = 8.dp)) {
                Label("Say it or type it · the Toad shows what it will do · OK runs it")
                Text("ASK THE TOAD", style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    SloopyButton("Speak", primary = true, enabled = !busy, onClick = { startSpeaking() }, modifier = Modifier.focusRequester(speakFocus).padding(bottom = 2.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.width(760.dp)) { Field("Or type here", typed, { typed = it }, placeholder = "Put ESPN, AMC and A&E in my favourites", onDone = { if (typed.isNotBlank()) vm.toadAsk(typed) }) }
                    Spacer(Modifier.width(12.dp))
                    SloopyButton("Ask", enabled = typed.isNotBlank() && !busy, onClick = { vm.toadAsk(typed) }, modifier = Modifier.padding(bottom = 2.dp))
                }
                if (remoteAddress != null) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(Color.White).padding(4.dp)) { QrCode(remoteAddress!!, 64.dp) }
                    Spacer(Modifier.width(14.dp))
                    Text("Scan with your phone to type there. Same Wi-Fi as this box.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.width(520.dp))
                }
                Spacer(Modifier.height(22.dp))
                when {
                    busy -> { Label("Hang on", bright = true); Text("The Toad is thinking about \"${typed.take(80)}\"", style = Sloopy.bodyBig, modifier = Modifier.padding(top = 6.dp)) }
                    error != null -> { Label("Hmm", bright = true); Text(error!!, style = Sloopy.bodyBig.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 6.dp).width(900.dp)) }
                    plan != null -> {
                        val p = plan!!
                        Label("You asked")
                        Text(p.asked, style = Sloopy.bodyBig, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp).width(900.dp))
                        Label(if (p.runnable) "The Toad will" else "The Toad says", bright = true)
                        if (p.say.isNotBlank() && !p.runnable) Text(p.say, style = Sloopy.bodyBig, modifier = Modifier.padding(top = 4.dp).width(900.dp))
                        p.lines.forEach { l -> Text(l, style = Sloopy.bodyBig.copy(color = if (l.contains(": not on") || l.contains(": no such") || l.contains(": nothing")) Sloopy.muted else Sloopy.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).width(1000.dp)) }
                        Row(Modifier.padding(top = 18.dp)) {
                            if (p.runnable) { SloopyButton("Do it", primary = true, modifier = Modifier.focusRequester(doFocus), onClick = { vm.toadRun() }); Spacer(Modifier.width(10.dp)) }
                            SloopyButton("Not now", modifier = if (p.runnable) Modifier else Modifier.focusRequester(doFocus), onClick = { vm.toadDismiss() })
                        }
                    }
                    else -> {
                        Text("Things the Toad can do: add or remove favourites, hide or show groups and channels, switch channel, remind you about a programme or a team, record one, or search.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.width(900.dp))
                        if (recent.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            Label("Recently asked")
                            Hairline(color = Sloopy.hair2)
                            recent.forEach { q ->
                                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SloopyButton(q.take(70), onClick = { typed = q; vm.toadAsk(q) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
