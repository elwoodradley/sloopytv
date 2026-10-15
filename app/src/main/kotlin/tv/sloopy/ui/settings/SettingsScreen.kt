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
import androidx.compose.ui.graphics.Color
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
import tv.sloopy.ui.common.NavStrip
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
    val stripFocus = remember { FocusRequester() }
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
            NavStrip("settings", go = go, requester = stripFocus)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.width(RAIL_W).fillMaxHeight().onFocusChanged { if (it.hasFocus) inRail = true }) {
                RailHeader("Settings")
                val activeProfile by vm.activeProfile.collectAsState()
                val sections = if (activeProfile != null) KID_SECTIONS else SECTIONS
                LaunchedEffect(activeProfile) { if (activeProfile != null) section = "parental" }
                sections.forEachIndexed { i, (key, name) ->
                    Box(Modifier.onFocusChanged { if (it.isFocused) section = key }) {
                        RailItem(name, selected = key == section, requester = if (key == section) railFocus else null, first = i == 0, last = i == sections.lastIndex, leftTarget = stripFocus) { scope.launch { focusContent() } }
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
                        val epgH by vm.epgIntervalHours.collectAsState()
                        Spacer(Modifier.height(16.dp))
                        SettingRow("Guide refreshes every", "$epgH H") { scope.launch { vm.prefs.setEpgIntervalHours(when (epgH) { 1 -> 2; 2 -> 3; 3 -> 6; 6 -> 12; 12 -> 24; else -> 1 }) } }
                        val collapse by vm.collapseDupes.collectAsState()
                        SettingRow("Collapse duplicate channels", if (collapse) "ON" else "OFF") { scope.launch { vm.prefs.setCollapseDupes(!collapse) } }
                        Text("Copies of a channel (HD, FHD, 4K, backup) show as one row. Only names that match exactly once the tags are stripped are treated as copies; hold OK on a channel to pick a copy or split them.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 4.dp, bottom = 10.dp).width(760.dp))
                        val dupes by vm.dupes.collectAsState()
                        if (dupes.possible.isNotEmpty()) {
                            Section("Possible duplicates · ${dupes.possible.size}")
                            Text("Same guide listing, different names. Nothing is merged until you say so.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(bottom = 6.dp))
                            dupes.possible.take(20).forEach { (a, b) ->
                                Column {
                                    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(tv.sloopy.core.model.Names.clean(a.name) + "   ↔   " + tv.sloopy.core.model.Names.clean(b.name), style = Sloopy.listName, modifier = Modifier.width(620.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        SloopyButton("Same channel", onClick = { vm.mergePossible(a, b, true) })
                                        Spacer(Modifier.width(8.dp))
                                        SloopyButton("Different", onClick = { vm.mergePossible(a, b, false) })
                                    }
                                    Hairline(color = Sloopy.hair2)
                                }
                            }
                        }
                        val split by vm.dupSplit.collectAsState()
                        if (split.isNotEmpty()) {
                            Section("Shown as separate channels")
                            val allCh by vm.channels.collectAsState()
                            split.forEach { k ->
                                val names = allCh.filter { tv.sloopy.core.model.Dupes.key(it.name) == k }.joinToString("  ·  ") { tv.sloopy.core.model.Names.clean(it.name) }
                                SettingRow(names.ifBlank { k }.take(60), "MERGE AGAIN") { vm.unsplitCluster(k) }
                            }
                        }
                        Text("To reorder or hide channel groups: open the guide, press Back for the groups, hold OK on a group.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 24.dp).width(760.dp))
                        val hidden by vm.hiddenGroups.collectAsState()
                        val hiddenCats = categories.filter { it.id in hidden }
                        if (hiddenCats.isNotEmpty()) {
                            Section("Hidden groups")
                            hiddenCats.forEach { c -> SettingRow(tv.sloopy.core.model.Names.clean(c.name), "SHOW") { vm.setHidden(c.id, false) } }
                        }
                        val hiddenCh by vm.hiddenChannels.collectAsState()
                        val allCh by vm.channels.collectAsState()
                        val hiddenChans = allCh.filter { it.id in hiddenCh }
                        if (hiddenChans.isNotEmpty()) {
                            Section("Hidden channels")
                            hiddenChans.forEach { c -> SettingRow("${c.number}  ${tv.sloopy.core.model.Names.clean(c.name)}", "SHOW") { vm.setChannelHidden(c.id, false) } }
                        }
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
                        val buffer by vm.bufferSize.collectAsState()
                        SettingRow("Buffer", buffer.uppercase()) { scope.launch { vm.prefs.setBufferSize(when (buffer) { "small" -> "medium"; "medium" -> "large"; else -> "small" }) } }
                        Text("How much of the stream is kept ahead. Large rides out a shaky connection; small starts faster.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 4.dp, bottom = 10.dp).width(760.dp))
                        val ext by vm.externalPlayer.collectAsState()
                        val vlc = vm.vlcInstalled()
                        SettingRow("Player", if (ext == "vlc") "VLC" else "SLOOPYTV") {
                            if (ext == "vlc") scope.launch { vm.prefs.setExternalPlayer("builtin") }
                            else if (vlc) scope.launch { vm.prefs.setExternalPlayer("vlc") }
                            else vm.toast("VLC is not installed on this box")
                        }
                        Text(if (vlc) "With VLC, channels and films open in VLC; Back returns here. The badge, recent channels and resume are VLC's own." else "Install VLC for Android TV to use it as the player, or for Open in VLC on a film.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 4.dp, bottom = 10.dp).width(760.dp))
                        SettingRow("Match the TV's refresh rate to the stream", if (frm) "ON" else "OFF") { scope.launch { vm.prefs.setFrameRateMatch(!frm) } }
                        Text("Stops the judder on 50 fps channels when the TV runs at 60. The picture goes black for a moment when the rate changes.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 4.dp, bottom = 10.dp).width(760.dp))
                        SettingRow("Tube power-on at launch", if (intro) "ON" else "OFF") { scope.launch { vm.prefs.setIntroEnabled(!intro) } }
                        val tSport by vm.tonightSportsAnywhere.collectAsState()
                        val tVod by vm.tonightNewOnDemand.collectAsState()
                        SettingRow("Tonight: include sport on any channel", if (tSport) "ON" else "OFF") { scope.launch { vm.prefs.setTonightSportsAnywhere(!tSport) } }
                        SettingRow("Tonight: include new episodes on demand", if (tVod) "ON" else "OFF") { scope.launch { vm.prefs.setTonightNewOnDemand(!tVod) } }
                        SettingRow("Channel badge stays for", "$osd S") { scope.launch { vm.prefs.setOsdSeconds(if (osd >= 8) 2 else osd + 1) } }
                    }
                    "recordings" -> {
                        Section("Recordings")
                        val folder by vm.recordFolder.collectAsState()
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
                            if (uri != null) {
                                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                                vm.setRecordFolder(uri.toString())
                            }
                        }
                        Text("Where recordings are saved: " + tv.sloopy.record.Recorder.describeFolder(folder), style = Sloopy.body, modifier = Modifier.width(760.dp))
                        Row(Modifier.padding(top = 14.dp)) {
                            SloopyButton("Choose a folder or USB drive", primary = true, onClick = { runCatching { pick.launch(null) }.onFailure { vm.toast("This box has no folder picker") } })
                            if (folder != null) { Spacer(Modifier.width(8.dp)); SloopyButton("Use this stick's storage", onClick = { vm.setRecordFolder(null) }) }
                        }
                        Text("Recording the channel you are watching rides the same stream, so it costs no second connection. Recording another channel while you watch needs a second connection on your account.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 16.dp).width(760.dp))
                        Section("Season passes")
                        val passes by vm.seasonPasses.collectAsState()
                        val unsure by vm.passUnsure.collectAsState()
                        var team by remember { mutableStateOf("") }
                        Text("Every game of a team, booked from the guide only when ESPN's schedule agrees on the kickoff. Anything it is not sure about waits below for you.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.width(760.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.width(420.dp)) { Field("Team", team, { team = it }, placeholder = "Bengals") }
                            Spacer(Modifier.width(12.dp))
                            SloopyButton("Add season pass", primary = true, enabled = team.isNotBlank(), modifier = Modifier.padding(bottom = 2.dp), onClick = { vm.addSeasonPass(team); team = "" })
                        }
                        passes.forEach { t -> SettingRow(t, "REMOVE") { vm.removeSeasonPass(t) } }
                        if (unsure.isNotEmpty()) {
                            Section("Needs a look · ${unsure.size}")
                            unsure.take(12).forEach { e ->
                                val parts = e.split('|')
                                Column {
                                    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text((parts.getOrNull(3) ?: e) + "  ·  " + (parts.getOrNull(1)?.toLongOrNull()?.let { tv.sloopy.ui.common.TimeFmt.dateTime(it * 1000) } ?: ""), style = Sloopy.listName, modifier = Modifier.width(620.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        SloopyButton("Record", onClick = { vm.resolveUnsure(e, true) })
                                        Spacer(Modifier.width(8.dp))
                                        SloopyButton("Skip", onClick = { vm.resolveUnsure(e, false) })
                                    }
                                    Hairline(color = Sloopy.hair2)
                                }
                            }
                        }
                    }
                    "friends" -> {
                        Section("Friends")
                        LaunchedEffect(Unit) { vm.ensureFriendCode(); vm.refreshPresence() }
                        val code by vm.friendCode.collectAsState()
                        val nick by vm.friendNick.collectAsState()
                        val share by vm.shareWatching.collectAsState()
                        val fl by vm.friends.collectAsState()
                        val pres by vm.presence.collectAsState()
                        var nickEdit by remember(nick) { mutableStateOf(nick) }
                        var addCode by remember { mutableStateOf("") }
                        var addName by remember { mutableStateOf("") }
                        Text("Off by default. Turn sharing on and friends who have your code see which channel you are on; OK on a friend joins them. Hold OK on the picture › Send to friend puts a channel on their TV.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.width(760.dp))
                        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label("Your friend code"); Spacer(Modifier.width(16.dp))
                            Text(code?.let { tv.sloopy.sync.SyncClient.pretty(it) } ?: "…", style = Sloopy.titleRow.copy(fontFamily = Sloopy.mono))
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.width(360.dp)) { Field("Your name, as friends see it", nickEdit, { nickEdit = it }, placeholder = "Luke") }
                            Spacer(Modifier.width(12.dp))
                            SloopyButton("Save", modifier = Modifier.padding(bottom = 2.dp), onClick = { scope.launch { vm.prefs.setFriendNick(nickEdit) } })
                        }
                        SettingRow("Share what I am watching", if (share) "ON" else "OFF") { scope.launch { vm.prefs.setShareWatching(!share) } }
                        Section("Add a friend")
                        Row(verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.width(260.dp)) { Field("Their code", addCode, { addCode = it.uppercase() }, mono = true, placeholder = "XXXX-XXXX") }
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.width(260.dp)) { Field("Name", addName, { addName = it }, placeholder = "Mike") }
                            Spacer(Modifier.width(12.dp))
                            SloopyButton("Add", primary = true, enabled = tv.sloopy.sync.SyncClient.normalise(addCode).length == 8, modifier = Modifier.padding(bottom = 2.dp), onClick = { vm.addFriend(addCode, addName); addCode = ""; addName = "" })
                        }
                        if (fl.isNotEmpty()) {
                            Section("Friends · ${fl.size}")
                            fl.forEach { (c, n) ->
                                val p = pres.firstOrNull { it.code == c }
                                val fresh = p != null && System.currentTimeMillis() / 1000 - p.at < 180 && p.channel != null
                                Column {
                                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.width(520.dp)) {
                                            Text(n, style = Sloopy.listName)
                                            Text(if (fresh) "Watching " + p!!.channel else "Not sharing right now", style = Sloopy.listSub.copy(color = if (fresh) Sloopy.amber else Sloopy.muted))
                                        }
                                        if (fresh) { SloopyButton("Join", onClick = { vm.joinFriend(p!!) }); Spacer(Modifier.width(8.dp)) }
                                        SloopyButton("Remove", onClick = { vm.removeFriend(c) })
                                    }
                                    Hairline(color = Sloopy.hair2)
                                }
                            }
                        }
                    }
                    "parental" -> {
                        Section("Profiles")
                        val profs by vm.profiles.collectAsState()
                        val activeP by vm.activeProfile.collectAsState()
                        val allCats by vm.allLiveCategories.collectAsState()
                        var editing by remember { mutableStateOf<SloopyViewModel.Profile?>(null) }
                        var pinAsk by remember { mutableStateOf(false) }
                        var pinText by remember { mutableStateOf("") }
                        if (activeP != null) {
                            Text("On the ${activeP!!.name} profile. Only its groups and categories show anywhere in the app.", style = Sloopy.body, modifier = Modifier.width(760.dp))
                            Row(Modifier.padding(top = 14.dp)) { SloopyButton("Leave this profile", primary = true, onClick = { if (hasPin) pinAsk = true else vm.switchProfile("") }) }
                            if (pinAsk) {
                                Field("PIN", pinText, { pinText = it.filter { ch -> ch.isDigit() }.take(6) }, mono = true, password = true, keyboard = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                                Row(Modifier.padding(top = 10.dp)) { SloopyButton("Unlock", primary = true, enabled = pinText.length >= 4, onClick = { scope.launch { if (vm.checkPin(pinText)) { pinAsk = false; pinText = ""; vm.switchProfile("") } else { vm.toast("Wrong PIN"); pinText = "" } } }) }
                            }
                        } else {
                            Text("A profile sees only the groups and categories you tick. Leaving it needs the PIN, so a kids profile stays a kids profile.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.width(760.dp))
                            if (!hasPin) Text("Set a PIN below first, or anyone can leave the profile.", style = Sloopy.body.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 6.dp))
                            profs.forEach { pr ->
                                Column {
                                    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(pr.name + "  ·  ${pr.groups.size} groups", style = Sloopy.listName, modifier = Modifier.width(520.dp))
                                        SloopyButton("Switch to", primary = true, onClick = { vm.switchProfile(pr.id) }); Spacer(Modifier.width(8.dp))
                                        SloopyButton("Edit", onClick = { editing = pr }); Spacer(Modifier.width(8.dp))
                                        SloopyButton("Delete", onClick = { vm.deleteProfile(pr.id) })
                                    }
                                    Hairline(color = Sloopy.hair2)
                                }
                            }
                            Row(Modifier.padding(top = 12.dp)) { SloopyButton("New profile", onClick = { editing = SloopyViewModel.Profile(java.util.UUID.randomUUID().toString().take(8), "Kids") }) }
                            editing?.let { pr ->
                                var name by remember(pr.id) { mutableStateOf(pr.name) }
                                var groups by remember(pr.id) { mutableStateOf(pr.groups.toSet()) }
                                Section("Editing ${pr.name}")
                                Box(Modifier.width(420.dp)) { Field("Name", name, { name = it }) }
                                Label("Groups this profile may watch", modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                                allCats.forEach { c -> SettingRow(tv.sloopy.core.model.Names.clean(c.name), if (c.id in groups) "ALLOWED" else "OFF") { groups = if (c.id in groups) groups - c.id else groups + c.id } }
                                Row(Modifier.padding(top = 14.dp)) {
                                    SloopyButton("Save profile", primary = true, enabled = name.isNotBlank() && groups.isNotEmpty(), onClick = { vm.saveProfile(pr.copy(name = name.trim(), groups = groups.toList())); editing = null })
                                    Spacer(Modifier.width(8.dp)); SloopyButton("Cancel", onClick = { editing = null })
                                }
                                Text("Movies and series: this profile sees none until you allow categories in a later update; the guide and channel list follow the groups above.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 10.dp).width(760.dp))
                            }
                        }
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
                    "transfer" -> {
                        Section("Transfer")
                        val code by vm.syncCode.collectAsState()
                        val syncedAt by vm.syncedAt.collectAsState()
                        val status by vm.syncStatus.collectAsState()
                        var restoreCode by remember { mutableStateOf("") }
                        var busy by remember { mutableStateOf(false) }
                        // The button that was pressed disappears when sync flips; keep the highlight on the page.
                        val syncFirst = remember { FocusRequester() }
                        var hadCode by remember { mutableStateOf(code != null) }
                        LaunchedEffect(code != null) { if ((code != null) != hadCode) { hadCode = code != null; delay(80); runCatching { syncFirst.requestFocus() } } }
                        if (code == null) {
                            Text("Turn on sync and this stick gets a code. Type that code on any other stick and it becomes a copy: provider login, favourites, group order, History, theme, everything. The code keeps working; the copy is always current.", style = Sloopy.body, modifier = Modifier.width(760.dp))
                            Row(Modifier.padding(top = 16.dp)) { SloopyButton("Turn on sync", primary = true, onClick = { vm.enableSync() }, modifier = Modifier.focusRequester(syncFirst)) }
                        } else {
                            Text("Type this code on another stick, under Transfer from another stick when it asks for a provider, or here under Restore.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.width(760.dp))
                            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.background(Color.White).padding(10.dp)) { tv.sloopy.ui.common.QrCode(tv.sloopy.sync.SyncClient.pretty(code!!), 150.dp) }
                                Spacer(Modifier.width(32.dp))
                                Column {
                                    Label("Your transfer code")
                                    Text(tv.sloopy.sync.SyncClient.pretty(code!!), style = Sloopy.titleHuge.copy(fontFamily = Sloopy.mono, letterSpacing = androidx.compose.ui.unit.TextUnit(0.12f, androidx.compose.ui.unit.TextUnitType.Em)))
                                    Text(if (syncedAt > 0) "Last uploaded " + tv.sloopy.ui.common.TimeFmt.dateTime(syncedAt) else "Not uploaded yet", style = Sloopy.listSub)
                                    Text("Anyone with this code gets your provider login. Keep it like a password.", style = Sloopy.listSub.copy(color = Sloopy.amber))
                                }
                            }
                            Row(Modifier.padding(top = 18.dp)) {
                                SloopyButton("Upload now", onClick = { vm.syncNow(announce = true) }, modifier = Modifier.focusRequester(syncFirst))
                                Spacer(Modifier.width(8.dp))
                                SloopyButton("New code", onClick = { vm.enableSync() })
                                Spacer(Modifier.width(8.dp))
                                SloopyButton("Turn off sync", onClick = { vm.disableSync() })
                            }
                        }
                        if (status != null) Text(status!!, style = Sloopy.body.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 12.dp).width(760.dp))
                        Section("Restore")
                        Text("Make this stick a copy of another one. Its code goes here. Nothing on this stick is removed; the other stick's choices are laid over it.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.width(760.dp))
                        // Right from the field is wired by hand: geometric search does not find the button.
                        val restoreBtn = remember { FocusRequester() }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.width(360.dp)) { Field("Code from the other stick", restoreCode, { restoreCode = it.uppercase() }, mono = true, placeholder = "XXXX-XXXX", modifier = Modifier.focusProperties { right = restoreBtn }) }
                            Spacer(Modifier.width(12.dp))
                            SloopyButton(if (busy) "Hang on…" else "Restore", enabled = !busy && tv.sloopy.sync.SyncClient.normalise(restoreCode).length == 8, modifier = Modifier.padding(bottom = 2.dp).focusRequester(restoreBtn), onClick = {
                                busy = true
                                scope.launch {
                                    runCatching { vm.restoreFromCode(restoreCode) }
                                        .onSuccess { vm.toast("Copied: $it"); restoreCode = "" }
                                        .onFailure { vm.toast(tv.sloopy.TLog.userMessage(it)) }
                                    busy = false
                                }
                            })
                        }
                    }
                    else -> {
                        Section("About")
                        // One button that changes label with the state, so the cursor never leaves it.
                        val aboutBtn = remember { FocusRequester() }
                        var aboutHad by remember { mutableStateOf(false) }
                        LaunchedEffect(update) { if (aboutHad) { delay(60); runCatching { aboutBtn.requestFocus() } } }
                        val (label, primary, enabled, action) = when (val u = update) {
                            is UpdateState.Available -> listOf("Install ${u.info.version}", true, true, { vm.installUpdate() })
                            is UpdateState.NeedsPermission -> listOf("Install ${u.info.version}", true, true, { vm.installUpdate() })
                            is UpdateState.Downloading -> listOf("Downloading ${u.percent}%", false, false, {})
                            is UpdateState.Ready -> listOf("Show the install screen", true, true, { vm.installUpdate() })
                            UpdateState.Checking -> listOf("Checking…", false, false, {})
                            else -> listOf("Check for updates", false, true, { vm.checkForUpdate(force = true) })
                        }
                        val note = when (val u = update) {
                            is UpdateState.Available -> "A newer SloopyTV is ready. The box will ask you to confirm."
                            is UpdateState.NeedsPermission -> "One-time step: on the screen that opened, turn on \"Allow from this source\" for SloopyTV, press Back, then press Install again."
                            is UpdateState.Downloading -> "Hang on: downloading ${u.info.version}."
                            is UpdateState.Ready -> "Downloaded ${u.info.version}. The box asks you to confirm. If nothing appeared, press the button again."
                            is UpdateState.Failed -> u.message
                            UpdateState.UpToDate -> "This is the latest version."
                            else -> ""
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            @Suppress("UNCHECKED_CAST")
                            SloopyButton(label as String, primary = primary as Boolean, enabled = enabled as Boolean, onClick = action as () -> Unit, modifier = Modifier.focusRequester(aboutBtn).onFocusChanged { if (it.isFocused) aboutHad = true })
                            Spacer(Modifier.width(16.dp))
                            Text(note, style = Sloopy.body.copy(color = if (update is UpdateState.Failed || update is UpdateState.NeedsPermission) Sloopy.amber else Sloopy.textSoft), modifier = Modifier.width(700.dp))
                        }
                        val context = androidx.compose.ui.platform.LocalContext.current
                        var crash by remember { mutableStateOf(tv.sloopy.CrashLog.read(context)) }
                        if (crash != null) {
                            Section("Last crash")
                            Text("The app closed by itself. Read this to whoever looks after SloopyTV.", style = Sloopy.body.copy(color = Sloopy.amber))
                            Text(crash!!.lineSequence().take(14).joinToString("\n"), style = Sloopy.mono14.copy(color = Sloopy.textSoft), modifier = Modifier.padding(top = 10.dp).width(900.dp))
                            Row(Modifier.padding(top = 12.dp)) { SloopyButton("Clear", onClick = { tv.sloopy.CrashLog.clear(context); crash = null }) }
                        }
                        Text("SloopyTV ${BuildConfig.VERSION_NAME}. A player only: it ships with no channels and no providers. Credentials stay in this device's keystore. No analytics, no telemetry.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 20.dp).width(760.dp))
                        val operatorName by vm.operatorName.collectAsState()
                        tv.sloopy.ui.common.StoneToadCredit(operator = operatorName.takeIf { it.isNotBlank() }, modifier = Modifier.padding(top = 36.dp))
                    }
                }
            }
        }
    }
}

private val SECTIONS = listOf("providers" to "Providers", "listings" to "Listings", "picture" to "Picture", "recordings" to "Recordings", "friends" to "Friends", "parental" to "Parental", "transfer" to "Transfer", "about" to "About")
private val KID_SECTIONS = listOf("parental" to "Profile")

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
