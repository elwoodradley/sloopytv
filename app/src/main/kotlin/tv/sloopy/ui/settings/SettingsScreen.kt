package tv.sloopy.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.delay
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
import tv.sloopy.ui.common.BACK_KEYS
import tv.sloopy.ui.common.RAIL_W
import tv.sloopy.ui.common.RailHeader
import tv.sloopy.ui.common.RailItem
import tv.sloopy.ui.common.ScreenHeader
import tv.sloopy.ui.common.inKeys
import tv.sloopy.ui.common.isDown
import tv.sloopy.ui.common.isKey
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
    val codeServerSaved by vm.prefs.codeServer.collectAsState(initial = null)
    var codeServerEdit by remember(codeServerSaved) { mutableStateOf(codeServerSaved ?: "") }

    val railFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var inRail by remember { mutableStateOf(true) }
    var section by remember { mutableStateOf(vm.settingsSection) }
    LaunchedEffect(section) { vm.settingsSection = section }
    suspend fun focusRail() { repeat(20) { if (runCatching { railFocus.requestFocus() }.getOrDefault(false)) return; delay(50) } }
    suspend fun focusContent() { repeat(20) { if (runCatching { contentFocus.requestFocus() }.getOrDefault(false)) return; delay(50) } }
    LaunchedEffect(Unit) { delay(60); focusRail() }
    BackHandler { if (inRail) onBack() else scope.launch { focusRail() } }

    Column(
        Modifier.fillMaxSize().background(Sloopy.ground)
            .onPreviewKeyEvent { ev ->
                if (ev.inKeys(BACK_KEYS)) { if (!ev.isDown) { if (inRail) onBack() else scope.launch { focusRail() } }; return@onPreviewKeyEvent true }
                if (ev.isDown && inRail && ev.isKey(Key.DirectionRight)) { scope.launch { focusContent() }; return@onPreviewKeyEvent true }
                false
            }
    ) {
        ScreenHeader("Settings")
        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp)) {
            Column(Modifier.width(RAIL_W).fillMaxHeight().onFocusChanged { if (it.hasFocus) inRail = true }) {
                RailHeader("Settings")
                SECTIONS.forEachIndexed { i, (key, name) ->
                    Box(Modifier.onFocusChanged { if (it.isFocused) section = key }) {
                        RailItem(name, selected = key == section, requester = if (key == section) railFocus else null, first = i == 0, last = i == SECTIONS.lastIndex) { scope.launch { focusContent() } }
                    }
                }
            }
            Spacer(Modifier.width(48.dp))
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 40.dp)
                    .focusRequester(contentFocus).focusRestorer().focusGroup()
                    // Left out of the page goes back to the rail, on the section already selected.
                    .focusProperties { exit = { dir -> if (dir == FocusDirection.Left) railFocus else FocusRequester.Default } }
                    .onFocusChanged { if (it.hasFocus) inRail = false },
            ) {
                when (section) {
                    "providers" -> {
                        Section("Providers")
                        providers.forEach { p ->
                            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (p.id == active?.id) LiveDot() else Spacer(Modifier.width(7.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.width(380.dp)) {
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
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.width(520.dp)) {
                                Field("Login code server (optional)", codeServerEdit, { codeServerEdit = it }, mono = true, placeholder = if (BuildConfig.CODE_SERVER.isBlank()) "https://…" else BuildConfig.CODE_SERVER, keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
                            }
                            Spacer(Modifier.width(12.dp))
                            SloopyButton("Save", onClick = { scope.launch { vm.prefs.setCodeServer(codeServerEdit) } }, modifier = Modifier.padding(bottom = 2.dp))
                        }
                    }
                    "listings" -> {
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
                        Text("To reorder channel groups: open the guide, press Back for the groups, hold OK on a group, move it with Up and Down, press OK to drop it.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 24.dp).width(760.dp))
                    }
                    "picture" -> {
                        Section("Picture")
                        val theme by vm.theme.collectAsState()
                        val clock12 by vm.clock12.collectAsState()
                        val frm by vm.frameRateMatch.collectAsState()
                        SettingRow("Theme", tv.sloopy.ui.theme.Palettes.byId(theme).name.uppercase()) {
                            val all = tv.sloopy.ui.theme.Palettes.all; val i = all.indexOfFirst { it.id == theme }
                            scope.launch { vm.prefs.setTheme(all[(i + 1) % all.size].id) }
                        }
                        SettingRow("Clock", if (clock12) "12 HOUR" else "24 HOUR") { scope.launch { vm.prefs.setClock12(!clock12) } }
                        SettingRow("Match the TV's refresh rate to the stream", if (frm) "ON" else "OFF") { scope.launch { vm.prefs.setFrameRateMatch(!frm) } }
                        Text("Stops the judder on 50 fps channels when the TV runs at 60. The picture goes black for a moment when the rate changes.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 4.dp, bottom = 10.dp).width(760.dp))
                        SettingRow("Tube power-on at launch", if (intro) "ON" else "OFF") { scope.launch { vm.prefs.setIntroEnabled(!intro) } }
                        SettingRow("Channel badge stays for", "$osd S") { scope.launch { vm.prefs.setOsdSeconds(if (osd >= 8) 2 else osd + 1) } }
                    }
                    "parental" -> {
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
                    }
                    else -> {
                        Section("About")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (val u = update) {
                                is UpdateState.Available -> { SloopyButton("Install ${u.info.version}", primary = true, onClick = { vm.installUpdate() }); Spacer(Modifier.width(16.dp)); Text("A newer SloopyTV is ready. The Fire TV will ask you to confirm.", style = Sloopy.body) }
                                is UpdateState.NeedsPermission -> { SloopyButton("Install ${u.info.version}", primary = true, onClick = { vm.installUpdate() }); Spacer(Modifier.width(16.dp)); Text("One-time step: on the screen that opened, turn on \"Allow from this source\" for SloopyTV, press Back, then press Install again.", style = Sloopy.body.copy(color = Sloopy.amber), modifier = Modifier.width(760.dp)) }
                                is UpdateState.Downloading -> { SloopyButton("Downloading ${u.percent}%", enabled = false, onClick = {}); Spacer(Modifier.width(16.dp)); Text("Hang on: downloading ${u.info.version}.", style = Sloopy.body.copy(color = Sloopy.amber)) }
                                is UpdateState.Ready -> { SloopyButton("Show the install screen", primary = true, onClick = { vm.installUpdate() }); Spacer(Modifier.width(16.dp)); Text("Downloaded ${u.info.version}. The box asks you to confirm. If nothing appeared, press the button again.", style = Sloopy.body.copy(color = Sloopy.amber), modifier = Modifier.width(700.dp)) }
                                is UpdateState.Failed -> { SloopyButton("Check for updates", onClick = { vm.checkForUpdate(force = true) }); Spacer(Modifier.width(16.dp)); Text(u.message, style = Sloopy.body.copy(color = Sloopy.amber)) }
                                UpdateState.Checking -> Text("Checking…", style = Sloopy.body)
                                UpdateState.UpToDate -> { SloopyButton("Check for updates", onClick = { vm.checkForUpdate(force = true) }); Spacer(Modifier.width(16.dp)); Text("This is the latest version.", style = Sloopy.body) }
                                UpdateState.Idle -> SloopyButton("Check for updates", onClick = { vm.checkForUpdate(force = true) })
                            }
                        }
                        Text("SloopyTV ${BuildConfig.VERSION_NAME}. A player only: it ships with no channels and no providers. Credentials stay in this device's keystore. No analytics, no telemetry.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 20.dp).width(760.dp))
                    }
                }
            }
        }
    }
}

private val SECTIONS = listOf("providers" to "Providers", "listings" to "Listings", "picture" to "Picture", "parental" to "Parental", "about" to "About")

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
