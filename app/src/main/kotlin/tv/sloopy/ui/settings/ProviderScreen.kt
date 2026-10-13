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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.sloopy.TLog
import tv.sloopy.core.model.Account
import tv.sloopy.data.ProviderKind
import tv.sloopy.data.ProviderRecord
import tv.sloopy.data.ProviderStore
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.Hairline
import tv.sloopy.ui.common.Label
import tv.sloopy.ui.common.LiveDot
import tv.sloopy.ui.common.SloopyButton
import tv.sloopy.ui.theme.Sloopy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@UnstableApi
@Composable
fun ProviderScreen(vm: SloopyViewModel, providerId: String?, onDone: () -> Unit, onBack: () -> Unit) {
    val existing = remember(providerId) { providerId?.let { vm.providers.get(it) } }
    var kind by remember { mutableStateOf(existing?.kind ?: ProviderKind.XTREAM) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var epgUrl by remember { mutableStateOf(existing?.epgUrl ?: "") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Account?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val firstField = remember { FocusRequester() }
    val providerList by vm.providerList.collectAsState()
    val hasProviders = providerList.isNotEmpty()

    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }
    BackHandler { onBack() }

    fun build(): ProviderRecord? {
        val id = existing?.id ?: ProviderStore.newId()
        return try {
            when (kind) {
                ProviderKind.XTREAM -> {
                    val creds = tv.sloopy.core.xtream.XtreamCredentials.parse(url, user, pass)
                    ProviderRecord(id, ProviderKind.XTREAM, name.ifBlank { creds.baseUrl.substringAfter("://") }, creds.baseUrl, creds.username, creds.password, null, existing?.container ?: tv.sloopy.core.model.StreamContainer.TS)
                }
                ProviderKind.M3U -> {
                    val u = url.trim()
                    if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) throw IllegalArgumentException("Playlist address must start with http:// or https://")
                    ProviderRecord(id, ProviderKind.M3U, name.ifBlank { "Playlist" }, u, "", "", epgUrl.trim().ifBlank { null })
                }
                ProviderKind.DEMO -> existing
            }
        } catch (e: Exception) { error = TLog.userMessage(e); null }
    }

    Row(Modifier.fillMaxSize().background(Sloopy.ground).padding(horizontal = 72.dp, vertical = 40.dp)) {
        Column(Modifier.width(600.dp).verticalScroll(rememberScrollState())) {
            Label("Settings · Providers · " + if (existing == null) "New" else "Edit")
            Text(if (!hasProviders && existing == null) "NOTHING'S PLUGGED IN YET" else if (existing == null) "ADD A PROVIDER" else "EDIT PROVIDER", style = Sloopy.titleHuge.copy(fontSize = androidx.compose.ui.unit.TextUnit(54f, androidx.compose.ui.unit.TextUnitType.Sp)), modifier = Modifier.padding(top = 8.dp), maxLines = 1)

            Row(Modifier.padding(top = 14.dp)) {
                SloopyButton("Xtream codes", onClick = { kind = ProviderKind.XTREAM }, primary = kind == ProviderKind.XTREAM, modifier = Modifier.focusRequester(firstField))
                Spacer(Modifier.width(10.dp))
                SloopyButton("M3U playlist", onClick = { kind = ProviderKind.M3U }, primary = kind == ProviderKind.M3U)
            }

            Field("Name", name, { name = it }, placeholder = "Home")
            if (kind == ProviderKind.XTREAM) {
                Field("Server", url, { url = it }, mono = true, placeholder = "http://host:port  or paste a get.php link", keyboard = KeyboardType.Uri)
                if (url.trim().startsWith("http://", true)) Warn("Plain HTTP. Your login travels unencrypted on this network. Use https if your provider offers it.")
                Field("Username", user, { user = it }, mono = true)
                Field("Password", pass, { pass = it }, mono = true, password = true)
            } else {
                Field("Playlist URL", url, { url = it }, mono = true, placeholder = "https://host/playlist.m3u", keyboard = KeyboardType.Uri)
                Field("Guide URL (XMLTV, optional)", epgUrl, { epgUrl = it }, mono = true, placeholder = "https://host/guide.xml", keyboard = KeyboardType.Uri)
            }

            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Top) {
                LockIcon()
                Spacer(Modifier.width(10.dp))
                Text("Stored only in this device's secure keychain. Never written to logs, crash reports or screenshots, and never sent anywhere except your provider.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.width(520.dp))
            }

            Row(Modifier.padding(top = 20.dp)) {
                if (kind == ProviderKind.XTREAM) {
                    SloopyButton(if (testing) "Testing…" else "Test connection", enabled = !testing, onClick = {
                        error = null; result = null
                        val rec = build() ?: return@SloopyButton
                        testing = true
                        scope.launch {
                            runCatching { vm.catalog.testXtream(rec) }
                                .onSuccess { result = it }
                                .onFailure { error = TLog.userMessage(it) }
                            testing = false
                        }
                    }, primary = true)
                    Spacer(Modifier.width(10.dp))
                }
                SloopyButton("Save", primary = kind != ProviderKind.XTREAM, onClick = {
                    error = null
                    val rec = build() ?: return@SloopyButton
                    scope.launch {
                        vm.saveProvider(rec)
                        vm.refresh(rec)
                        onDone()
                    }
                })
                if (hasProviders) { Spacer(Modifier.width(10.dp)); SloopyButton("Cancel", onClick = onBack) }
            }
            if (error != null) Text(error!!, style = Sloopy.bodyBig.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 18.dp))

            Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("No provider yet?", style = Sloopy.body.copy(color = Sloopy.muted))
                Spacer(Modifier.width(12.dp))
                SloopyButton("Try the demo on public test streams", onClick = { vm.addDemo(); onDone() })
            }
        }

        Spacer(Modifier.width(60.dp))

        Column(Modifier.width(560.dp)) {
            if (existing == null) PairPanel(onReceived = { p ->
                // Fill the form, then test and save without another press.
                error = null; result = null
                val looksM3u = p.user.isEmpty() && !p.url.contains("get.php") && !p.url.contains("player_api")
                kind = if (looksM3u) ProviderKind.M3U else ProviderKind.XTREAM
                name = p.name
                // A pasted get.php link carries the login in its query: split it out so the
                // password never sits in the Server field on screen.
                val split = if (!looksM3u) runCatching { tv.sloopy.core.xtream.XtreamCredentials.parse(p.url, p.user, p.pass) }.getOrNull() else null
                url = split?.baseUrl ?: p.url; user = split?.username ?: p.user; pass = split?.password ?: p.pass
                val rec = build() ?: return@PairPanel
                testing = true
                scope.launch {
                    if (rec.kind == ProviderKind.XTREAM) {
                        runCatching { vm.catalog.testXtream(rec) }
                            .onSuccess { result = it; vm.saveProvider(rec); vm.refresh(rec); onDone() }
                            .onFailure { error = TLog.userMessage(it) }
                    } else {
                        vm.saveProvider(rec); vm.refresh(rec); onDone()
                    }
                    testing = false
                }
            })
            if (result != null) ResultPanel(result!!, plainHttp = url.trim().startsWith("http://", true))
        }
    }
}

/** QR code plus address and code for signing in from a phone over the home network. */
@Composable
private fun PairPanel(onReceived: (tv.sloopy.pair.PairPayload) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var server by remember { mutableStateOf<tv.sloopy.pair.PairingServer?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val s = tv.sloopy.pair.PairingServer.start(context)
        server = s
        onDispose { runCatching { s?.stop() } }
    }
    val s = server
    val received by (s?.received ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    LaunchedEffect(received) { received?.let(onReceived) }

    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Label("Sign in from your phone")
        if (s == null) {
            Text("Couldn't start on this network.", style = Sloopy.body.copy(color = Sloopy.muted), modifier = Modifier.padding(top = 10.dp))
            return
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.background(Color.White).padding(8.dp)) { QrCode(s.qrText, 168.dp) }
            Spacer(Modifier.width(22.dp))
            Column {
                Text("Scan this with your phone's camera, paste your provider link or login on the page, done.", style = Sloopy.body, modifier = Modifier.width(330.dp))
                Spacer(Modifier.height(14.dp))
                Label("Or open")
                Text(s.address, style = Sloopy.inputMono.copy(fontSize = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)), modifier = Modifier.padding(top = 2.dp))
                Label("and enter the code", modifier = Modifier.padding(top = 10.dp))
                Text(s.code.chunked(3).joinToString(" "), style = Sloopy.titleBig.copy(fontFamily = Sloopy.mono, fontSize = androidx.compose.ui.unit.TextUnit(34f, androidx.compose.ui.unit.TextUnitType.Sp)), modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (received != null) Text("Received. Testing the connection…", style = Sloopy.body.copy(color = Sloopy.amber), modifier = Modifier.padding(top = 12.dp))
        Hairline(color = Sloopy.hair2, modifier = Modifier.padding(top = 22.dp))
    }
}

@Composable
private fun QrCode(text: String, size: androidx.compose.ui.unit.Dp) {
    val matrix = remember(text) {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 0,
        )
        com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints)
    }
    androidx.compose.foundation.Canvas(Modifier.width(size).height(size)) {
        val n = matrix.width
        val cell = this.size.width / n
        for (y in 0 until n) for (x in 0 until n) if (matrix[x, y]) {
            drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell), size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
private fun LockIcon() {
    androidx.compose.foundation.Canvas(Modifier.padding(top = 3.dp).width(16.dp).height(16.dp)) {
        val w = size.width; val h = size.height; val sw = 1.5.dp.toPx()
        drawRect(Sloopy.text, topLeft = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.45f), size = androidx.compose.ui.geometry.Size(w * 0.7f, h * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawArc(Sloopy.text, 180f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.05f), size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
    }
}

@Composable
private fun Warn(text: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(10.dp).background(Sloopy.amber))
        Spacer(Modifier.width(10.dp))
        Text(text, style = Sloopy.body.copy(color = Sloopy.amber))
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    password: Boolean = false,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    backLeaves: Boolean = false,
) {
    // Two-stage focus for the remote: the row takes D-pad focus like any other item; OK opens
    // the keyboard on the inner text field; Back or Next hands focus back to the row.
    var rowFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val inner = remember { FocusRequester() }
    val row = remember { FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val style = (if (mono) Sloopy.inputMono else Sloopy.input)
    val stopEditing = {
        editing = false
        keyboardController?.hide()
        runCatching { row.requestFocus() }
    }
    LaunchedEffect(editing) {
        if (editing) { runCatching { inner.requestFocus() }; keyboardController?.show() }
    }
    // The TV keyboard is a floating window: it swallows the Back press that closes it and reports
    // no inset, so there is no reliable "keyboard closed" signal. Instead: a D-pad move off the
    // text field ends editing, and a second Back ends it explicitly (and, when backLeaves is
    // set, also lets the screen handle Back).
    var innerHadFocus by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 14.dp).fillMaxWidth()) {
        Label(label)
        androidx.tv.material3.Surface(
            onClick = { editing = true },
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.ui.graphics.RectangleShape),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent, contentColor = Sloopy.text, focusedContentColor = Sloopy.text),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = modifier.fillMaxWidth().focusRequester(row).onFocusChanged { rowFocused = it.isFocused },
        ) {
            Box(Modifier.padding(top = 6.dp).fillMaxWidth()) {
                if (!editing) {
                    val shown = if (value.isEmpty()) placeholder else if (password) "\u2022".repeat(value.length) else value
                    Text(shown, style = style.copy(color = if (value.isEmpty()) Sloopy.dim else Sloopy.text), maxLines = 1, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    BasicTextField(
                        value = value,
                        onValueChange = onChange,
                        singleLine = true,
                        textStyle = style,
                        cursorBrush = SolidColor(Sloopy.amber),
                        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { stopEditing() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(inner).padding(vertical = 8.dp)
                            .onFocusChanged { st ->
                                if (st.isFocused) innerHadFocus = true
                                else if (innerHadFocus) { innerHadFocus = false; editing = false; keyboardController?.hide() }
                            }
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (ev.key == androidx.compose.ui.input.key.Key.Back || ev.key == androidx.compose.ui.input.key.Key.Escape)) { stopEditing(); !backLeaves } else false
                            },
                    )
                }
            }
        }
        Hairline(color = if (rowFocused || editing) Sloopy.text else Color(0xFF2E2E32), modifier = Modifier.height(if (rowFocused || editing) 2.dp else 1.dp))
    }
}

@Composable
private fun ResultPanel(a: Account, plainHttp: Boolean) {
    val df = DateTimeFormatter.ofPattern("d MMM yyyy")
    Column(Modifier.width(560.dp).background(Sloopy.ground).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { LiveDot(); Spacer(Modifier.width(10.dp)); Label(if (a.status.equals("Active", true)) "Picture's up" else "Connection test") }
        Spacer(Modifier.height(10.dp))
        val expires = a.expiresAtEpoch?.let { e ->
            val days = (e - System.currentTimeMillis() / 1000) / 86400
            Instant.ofEpochSecond(e).atZone(ZoneId.systemDefault()).format(df).uppercase() + " · $days DAYS"
        } ?: "UNLIMITED"
        KV("Status", a.status.uppercase() + if (a.isTrial) " · TRIAL" else "", amber = true)
        KV("Expires", expires)
        KV("Connections", "${a.activeConnections} OF ${a.maxConnections} IN USE")
        KV("Formats", a.allowedFormats.joinToString(" · ").uppercase().ifBlank { "TS" })
        KV("Server time", a.serverTimeEpoch?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) + " · " + (a.timezone ?: "") } ?: "?")
        KV("Transport", if (plainHttp) "HTTP · NOT ENCRYPTED" else "HTTPS", amber = plainHttp)
        Spacer(Modifier.height(16.dp))
        Text("The guide and catalogue are cached on this device and refreshed at most every six hours. Your provider sees a handful of requests a day, not one per screen.", style = Sloopy.body.copy(color = Sloopy.muted))
    }
}

@Composable
private fun KV(k: String, v: String, amber: Boolean = false) {
    Column {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(k, style = Sloopy.listName.copy(color = Sloopy.muted))
            Spacer(Modifier.weight(1f))
            Text(v, style = Sloopy.mono16.copy(color = if (amber) Sloopy.amber else Sloopy.text))
        }
        Hairline(color = Sloopy.hair2)
    }
}
