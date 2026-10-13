package tv.tube.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.tube.BuildConfig
import tv.tube.core.model.StreamContainer
import tv.tube.data.ProviderKind
import tv.tube.data.RefreshState
import tv.tube.ui.TubeViewModel
import tv.tube.ui.common.Hairline
import tv.tube.ui.common.Label
import tv.tube.ui.common.LiveDot
import tv.tube.ui.common.TopNav
import tv.tube.ui.common.TubeButton
import tv.tube.ui.theme.Tube

@UnstableApi
@Composable
fun SettingsScreen(vm: TubeViewModel, go: (String) -> Unit, onBack: () -> Unit, onEditProvider: (String) -> Unit) {
    val providers by vm.providerList.collectAsState()
    val active by vm.active.collectAsState()
    val intro by vm.introEnabled.collectAsState()
    val osd by vm.osdSeconds.collectAsState()
    val refresh by vm.refreshState.collectAsState()
    val hasPin by vm.hasPin.collectAsState()
    val categories by vm.liveCategories.collectAsState()
    val locked by vm.lockedGroups.collectAsState()
    val scope = rememberCoroutineScope()
    var pinEntry by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(Tube.ground)) {
        TopNav(current = "settings", onSelect = go, modifier = Modifier.padding(top = 10.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 72.dp, vertical = 20.dp)) {
            Section("Providers")
            providers.forEach { p ->
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (p.id == active?.id) LiveDot() else Spacer(Modifier.width(7.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.width(420.dp)) {
                        Text(p.name, style = Tube.listName)
                        Text((if (p.kind == ProviderKind.DEMO) "Demo" else p.kind.name + " · " + p.displayHost()) + if (p.isPlainHttp && p.kind != ProviderKind.DEMO) " · HTTP" else "", style = Tube.listSub)
                    }
                    Spacer(Modifier.weight(1f))
                    if (p.id != active?.id) { TubeButton("Use", onClick = { vm.setActive(p.id) }); Spacer(Modifier.width(8.dp)) }
                    if (p.kind == ProviderKind.XTREAM) {
                        TubeButton(if (p.container == StreamContainer.TS) "Stream: TS" else "Stream: HLS", onClick = {
                            scope.launch { vm.saveProvider(p.copy(container = if (p.container == StreamContainer.TS) StreamContainer.HLS else StreamContainer.TS), makeActive = false) }
                        })
                        Spacer(Modifier.width(8.dp))
                    }
                    if (p.kind != ProviderKind.DEMO) { TubeButton("Edit", onClick = { onEditProvider(p.id) }); Spacer(Modifier.width(8.dp)) }
                    TubeButton("Remove", onClick = { vm.removeProvider(p.id) })
                }
                Hairline(color = Tube.hair2)
            }
            Row(Modifier.padding(top = 14.dp)) {
                TubeButton("Add provider", onClick = { onEditProvider("new") }, primary = true)
                Spacer(Modifier.width(8.dp))
                TubeButton("Refresh channels and guide", onClick = { vm.refresh(force = true) })
                Spacer(Modifier.width(16.dp))
                Text(
                    when (val r = refresh) {
                        is RefreshState.Running -> "Refreshing: ${r.step}" + if (r.count > 0) " (${r.count})" else ""
                        is RefreshState.Failed -> "Failed: ${r.message}"
                        is RefreshState.Done -> "Last refresh: ${r.channels} channels, ${r.programmes} programmes"
                        RefreshState.Idle -> ""
                    },
                    style = Tube.body.copy(color = if (refresh is RefreshState.Failed) Tube.amber else Tube.muted),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            Section("Look and feel")
            SettingRow("Tube power-on at launch", if (intro) "ON" else "OFF") { scope.launch { vm.prefs.setIntroEnabled(!intro) } }
            SettingRow("Channel badge stays for", "$osd S") { scope.launch { vm.prefs.setOsdSeconds(if (osd >= 8) 2 else osd + 1) } }

            Section("Parental control")
            if (!hasPin) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("No PIN set. Set one to hide groups until unlocked.", style = Tube.body)
                    Spacer(Modifier.width(16.dp))
                    TubeButton("Set PIN", onClick = { showPin = true })
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PIN is set. Locked groups are hidden until unlocked for the session.", style = Tube.body)
                    Spacer(Modifier.width(16.dp))
                    TubeButton("Remove PIN", onClick = { scope.launch { vm.setPin(null) } })
                    Spacer(Modifier.width(8.dp))
                    TubeButton("Lock again", onClick = { vm.relock() })
                }
                Spacer(Modifier.height(10.dp))
                categories.forEach { c ->
                    SettingRow(c.name, if (c.id in locked) "LOCKED" else "OPEN") { vm.setLocked(c.id, c.id !in locked) }
                }
            }
            if (showPin) {
                Field("New PIN (4 to 6 digits)", pinEntry, { pinEntry = it.filter { ch -> ch.isDigit() }.take(6) }, mono = true, password = true, keyboard = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                Row(Modifier.padding(top = 10.dp)) {
                    TubeButton("Save PIN", primary = true, enabled = pinEntry.length in 4..6, onClick = { scope.launch { vm.setPin(pinEntry); pinEntry = ""; showPin = false } })
                    Spacer(Modifier.width(8.dp))
                    TubeButton("Cancel", onClick = { showPin = false; pinEntry = "" })
                }
            }

            Section("About")
            Text("Tube ${BuildConfig.VERSION_NAME}. A player only: it ships with no channels and no providers. Credentials stay in this device's keystore. No analytics, no telemetry.", style = Tube.body.copy(color = Tube.muted), modifier = Modifier.width(800.dp))
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Label(title, modifier = Modifier.padding(top = 30.dp, bottom = 10.dp))
}

@Composable
private fun SettingRow(label: String, value: String, onToggle: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Tube.listName, modifier = Modifier.width(520.dp))
            TubeButton(value, onClick = onToggle)
        }
        Hairline(color = Tube.hair2)
    }
}
