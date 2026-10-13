package tv.sloopy.ui.settings

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
import tv.sloopy.BuildConfig
import tv.sloopy.core.model.StreamContainer
import tv.sloopy.data.ProviderKind
import tv.sloopy.data.RefreshState
import tv.sloopy.update.UpdateState
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.TopNav
import tv.sloopy.ui.common.SloopyButton
import tv.sloopy.ui.theme.Sloopy

@UnstableApi
@Composable
fun SettingsScreen(vm: SloopyViewModel, go: (String) -> Unit, onBack: () -> Unit, onEditProvider: (String) -> Unit) {
    val providers by vm.providerList.collectAsState()
    val active by vm.active.collectAsState()
    val intro by vm.introEnabled.collectAsState()
    val osd by vm.osdSeconds.collectAsState()
    val refresh by vm.refreshState.collectAsState()
    val hasPin by vm.hasPin.collectAsState()
    val categories by vm.liveCategories.collectAsState()
    val locked by vm.lockedGroups.collectAsState()
    val update by vm.updateState.collectAsState()
    val scope = rememberCoroutineScope()
    var pinEntry by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(Sloopy.ground)) {
        TopNav(current = "settings", onSelect = go, modifier = Modifier.padding(top = 10.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 72.dp, vertical = 20.dp)) {
            Section("Providers")
            providers.forEach { p ->
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (p.id == active?.id) LiveDot() else Spacer(Modifier.width(7.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.width(420.dp)) {
                        Text(p.name, style = Sloopy.listName)
                        Text((if (p.kind == ProviderKind.DEMO) "Demo" else p.kind.name + " · " + p.displayHost()) + if (p.isPlainHttp && p.kind != ProviderKind.DEMO) " · HTTP" else "", style = Sloopy.listSub)
                    }
                    Spacer(Modifier.weight(1f))
                    if (p.id != active?.id) { SloopyButton("Use", onClick = { vm.setActive(p.id) }); Spacer(Modifier.width(8.dp)) }
                    if (p.kind == ProviderKind.XTREAM) {
                        SloopyButton(if (p.container == StreamContainer.TS) "Stream: TS" else "Stream: HLS", onClick = {
                            scope.launch { vm.saveProvider(p.copy(container = if (p.container == StreamContainer.TS) StreamContainer.HLS else StreamContainer.TS), makeActive = false) }
                        })
                        Spacer(Modifier.width(8.dp))
                    }
                    if (p.kind != ProviderKind.DEMO) { SloopyButton("Edit", onClick = { onEditProvider(p.id) }); Spacer(Modifier.width(8.dp)) }
                    SloopyButton("Remove", onClick = { vm.removeProvider(p.id) })
                }
                Hairline(color = Sloopy.hair2)
            }
            Row(Modifier.padding(top = 14.dp)) {
                SloopyButton("Add provider", onClick = { onEditProvider("new") }, primary = true)
            }

            Section("Listings")
            Row {
                SloopyButton("Refresh channels and listings", onClick = { vm.refresh(force = true) })
                Spacer(Modifier.width(16.dp))
                Text(
                    when (val r = refresh) {
                        is RefreshState.Running -> "Hang on: ${r.step.lowercase()}" + if (r.count > 0) " (${r.count})" else ""
                        is RefreshState.Failed -> "Failed: ${r.message}"
                        is RefreshState.Done -> "Listings updated. ${r.channels} channels, ${r.programmes} programmes."
                        RefreshState.Idle -> ""
                    },
                    style = Sloopy.body.copy(color = if (refresh is RefreshState.Failed) Sloopy.amber else Sloopy.muted),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            Section("Picture")
            SettingRow("Tube power-on at launch", if (intro) "ON" else "OFF") { scope.launch { vm.prefs.setIntroEnabled(!intro) } }
            SettingRow("Channel badge stays for", "$osd S") { scope.launch { vm.prefs.setOsdSeconds(if (osd >= 8) 2 else osd + 1) } }

            Section("Parental")
            if (!hasPin) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("No PIN set. Set one to hide groups until unlocked.", style = Sloopy.body)
                    Spacer(Modifier.width(16.dp))
                    SloopyButton("Set PIN", onClick = { showPin = true })
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PIN is set. Locked groups are hidden until unlocked for the session.", style = Sloopy.body)
                    Spacer(Modifier.width(16.dp))
                    SloopyButton("Remove PIN", onClick = { scope.launch { vm.setPin(null) } })
                    Spacer(Modifier.width(8.dp))
                    SloopyButton("Lock again", onClick = { vm.relock() })
                }
                Spacer(Modifier.height(10.dp))
                categories.forEach { c ->
                    SettingRow(c.name, if (c.id in locked) "LOCKED" else "OPEN") { vm.setLocked(c.id, c.id !in locked) }
                }
            }
            if (showPin) {
                Field("New PIN (4 to 6 digits)", pinEntry, { pinEntry = it.filter { ch -> ch.isDigit() }.take(6) }, mono = true, password = true, keyboard = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                Row(Modifier.padding(top = 10.dp)) {
                    SloopyButton("Save PIN", primary = true, enabled = pinEntry.length in 4..6, onClick = { scope.launch { vm.setPin(pinEntry); pinEntry = ""; showPin = false } })
                    Spacer(Modifier.width(8.dp))
                    SloopyButton("Cancel", onClick = { showPin = false; pinEntry = "" })
                }
            }

            Section("About")
            Text("SloopyTV ${BuildConfig.VERSION_NAME}. A player only: it ships with no channels and no providers. Credentials stay in this device's keystore. No analytics, no telemetry.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.width(800.dp))
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                when (val u = update) {
                    is UpdateState.Available -> { SloopyButton("Install ${u.info.version}", primary = true, onClick = { vm.installUpdate() }); Spacer(Modifier.width(16.dp)); Text("A newer SloopyTV is ready. The Fire TV will ask you to confirm.", style = Sloopy.body) }
                    is UpdateState.NeedsPermission -> { SloopyButton("Install ${u.info.version}", primary = true, onClick = { vm.installUpdate() }); Spacer(Modifier.width(16.dp)); Text("One-time step: on the screen that opened, turn on \"Allow from this source\" for SloopyTV, press Back, then press Install again.", style = Sloopy.body.copy(color = Sloopy.amber), modifier = Modifier.width(900.dp)) }
                    is UpdateState.Downloading -> Text("Hang on: downloading ${u.info.version}, ${u.percent}%", style = Sloopy.body.copy(color = Sloopy.amber))
                    is UpdateState.Ready -> Text("Downloaded. Confirm the install on screen.", style = Sloopy.body.copy(color = Sloopy.amber))
                    is UpdateState.Failed -> { SloopyButton("Check for updates", onClick = { vm.checkForUpdate(force = true) }); Spacer(Modifier.width(16.dp)); Text(u.message, style = Sloopy.body.copy(color = Sloopy.amber)) }
                    UpdateState.Checking -> Text("Checking…", style = Sloopy.body)
                    UpdateState.UpToDate -> { SloopyButton("Check for updates", onClick = { vm.checkForUpdate(force = true) }); Spacer(Modifier.width(16.dp)); Text("This is the latest version.", style = Sloopy.body) }
                    UpdateState.Idle -> SloopyButton("Check for updates", onClick = { vm.checkForUpdate(force = true) })
                }
            }
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
            Text(label, style = Sloopy.listName, modifier = Modifier.width(520.dp))
            SloopyButton(value, onClick = onToggle)
        }
        Hairline(color = Sloopy.hair2)
    }
}
