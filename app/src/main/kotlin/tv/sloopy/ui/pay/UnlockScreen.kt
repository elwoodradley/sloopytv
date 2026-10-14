package tv.sloopy.ui.pay

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.sloopy.TLog
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.QrCode
import tv.sloopy.ui.common.SloopyButton
import tv.sloopy.ui.settings.Field
import tv.sloopy.ui.theme.Sloopy

/** Shown instead of the app until a license key (or a transfer code carrying one) is typed. */
@UnstableApi
@Composable
fun UnlockScreen(vm: SloopyViewModel) {
    val cfg by vm.payConfig.collectAsState()
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var transfer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    Row(Modifier.fillMaxSize().background(Sloopy.ground).padding(horizontal = 72.dp, vertical = 48.dp)) {
        Column(Modifier.width(680.dp)) {
            Label("SloopyTV")
            Text("ONE-TIME UNLOCK", style = Sloopy.titleHuge, modifier = Modifier.padding(top = 8.dp))
            Text(
                (if (cfg?.price?.isNotBlank() == true) "SloopyTV costs ${cfg!!.price}, once. " else "") + "Buy it on the page to the right; the receipt has a license key. Type the key here and this stick is unlocked for good. Setting up another stick later? Its transfer code carries the key with it.",
                style = Sloopy.bodyBig, modifier = Modifier.padding(top = 18.dp),
            )
            if (!transfer) {
                Field("License key", key, { key = it }, mono = true, placeholder = "XXXXXXXX-XXXXXXXX-XXXXXXXX-XXXXXXXX", modifier = Modifier.focusRequester(first))
            } else {
                Field("Transfer code from another stick", key, { key = it.uppercase() }, mono = true, placeholder = "XXXX-XXXX", modifier = Modifier.focusRequester(first))
            }
            Row(Modifier.padding(top = 20.dp)) {
                SloopyButton(if (busy) "Hang on…" else if (transfer) "Transfer" else "Unlock", primary = true, enabled = !busy && key.trim().length >= 8, onClick = {
                    error = null; busy = true
                    scope.launch {
                        runCatching { if (transfer) vm.restoreFromCode(key) else vm.unlock(key) }
                            .onFailure { error = TLog.userMessage(it) }
                        busy = false
                    }
                })
                Spacer(Modifier.width(10.dp))
                SloopyButton(if (transfer) "I have a license key" else "I have a transfer code", onClick = { transfer = !transfer; key = ""; error = null })
            }
            if (error != null) Text(error!!, style = Sloopy.bodyBig.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 18.dp))
        }
        Spacer(Modifier.width(72.dp))
        if (cfg?.buyUrl?.isNotBlank() == true) Column {
            Label("Buy it here")
            Box(Modifier.padding(top = 14.dp).background(Color.White).padding(12.dp)) { QrCode(cfg!!.buyUrl, 220.dp) }
            Text(cfg!!.buyUrl.removePrefix("https://"), style = Sloopy.mono16, modifier = Modifier.padding(top = 14.dp))
            Text("Scan with your phone, or open the address in any browser.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 6.dp).width(420.dp))
        }
    }
}
