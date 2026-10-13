package tv.tube.ui.settings

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
import tv.tube.TLog
import tv.tube.core.model.Account
import tv.tube.data.ProviderKind
import tv.tube.data.ProviderRecord
import tv.tube.data.ProviderStore
import tv.tube.ui.TubeViewModel
import tv.tube.ui.common.Hairline
import tv.tube.ui.common.Label
import tv.tube.ui.common.LiveDot
import tv.tube.ui.common.TubeButton
import tv.tube.ui.theme.Tube
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@UnstableApi
@Composable
fun ProviderScreen(vm: TubeViewModel, providerId: String?, onDone: () -> Unit, onBack: () -> Unit) {
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
                    val creds = tv.tube.core.xtream.XtreamCredentials.parse(url, user, pass)
                    ProviderRecord(id, ProviderKind.XTREAM, name.ifBlank { creds.baseUrl.substringAfter("://") }, creds.baseUrl, creds.username, creds.password, null, existing?.container ?: tv.tube.core.model.StreamContainer.TS)
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

    Row(Modifier.fillMaxSize().background(Tube.ground).padding(horizontal = 72.dp, vertical = 40.dp)) {
        Column(Modifier.width(600.dp).verticalScroll(rememberScrollState())) {
            Label("Settings · Providers · " + if (existing == null) "New" else "Edit")
            Text("ADD A PROVIDER", style = Tube.titleHuge.copy(fontSize = androidx.compose.ui.unit.TextUnit(60f, androidx.compose.ui.unit.TextUnitType.Sp)), modifier = Modifier.padding(top = 8.dp))

            Row(Modifier.padding(top = 14.dp)) {
                TubeButton("Xtream codes", onClick = { kind = ProviderKind.XTREAM }, primary = kind == ProviderKind.XTREAM, modifier = Modifier.focusRequester(firstField))
                Spacer(Modifier.width(10.dp))
                TubeButton("M3U playlist", onClick = { kind = ProviderKind.M3U }, primary = kind == ProviderKind.M3U)
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
                Text("Stored only in this device's secure keychain. Never written to logs, crash reports or screenshots, and never sent anywhere except your provider.", style = Tube.body.copy(color = Tube.muted), modifier = Modifier.width(520.dp))
            }

            Row(Modifier.padding(top = 20.dp)) {
                if (kind == ProviderKind.XTREAM) {
                    TubeButton(if (testing) "Testing…" else "Test connection", enabled = !testing, onClick = {
                        error = null; result = null
                        val rec = build() ?: return@TubeButton
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
                TubeButton("Save", primary = kind != ProviderKind.XTREAM, onClick = {
                    error = null
                    val rec = build() ?: return@TubeButton
                    scope.launch {
                        vm.saveProvider(rec)
                        vm.refresh(rec)
                        onDone()
                    }
                })
                if (hasProviders) { Spacer(Modifier.width(10.dp)); TubeButton("Cancel", onClick = onBack) }
            }
            if (error != null) Text(error!!, style = Tube.bodyBig.copy(color = Tube.amber), modifier = Modifier.padding(top = 18.dp))

            Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("No provider yet?", style = Tube.body.copy(color = Tube.muted))
                Spacer(Modifier.width(12.dp))
                TubeButton("Try the demo on public test streams", onClick = { vm.addDemo(); onDone() })
            }
        }

        Spacer(Modifier.width(80.dp))

        if (result != null) ResultPanel(result!!, plainHttp = url.trim().startsWith("http://", true))
    }
}

@Composable
private fun LockIcon() {
    androidx.compose.foundation.Canvas(Modifier.padding(top = 3.dp).width(16.dp).height(16.dp)) {
        val w = size.width; val h = size.height; val sw = 1.5.dp.toPx()
        drawRect(Tube.text, topLeft = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.45f), size = androidx.compose.ui.geometry.Size(w * 0.7f, h * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawArc(Tube.text, 180f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.05f), size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
    }
}

@Composable
private fun Warn(text: String) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(10.dp).background(Tube.amber))
        Spacer(Modifier.width(10.dp))
        Text(text, style = Tube.body.copy(color = Tube.amber))
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
    val style = (if (mono) Tube.inputMono else Tube.input)
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
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent, contentColor = Tube.text, focusedContentColor = Tube.text),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = modifier.fillMaxWidth().focusRequester(row).onFocusChanged { rowFocused = it.isFocused },
        ) {
            Box(Modifier.padding(top = 6.dp).fillMaxWidth()) {
                if (!editing) {
                    val shown = if (value.isEmpty()) placeholder else if (password) "\u2022".repeat(value.length) else value
                    Text(shown, style = style.copy(color = if (value.isEmpty()) Tube.dim else Tube.text), maxLines = 1, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    BasicTextField(
                        value = value,
                        onValueChange = onChange,
                        singleLine = true,
                        textStyle = style,
                        cursorBrush = SolidColor(Tube.amber),
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
        Hairline(color = if (rowFocused || editing) Tube.text else Color(0xFF2E2E32), modifier = Modifier.height(if (rowFocused || editing) 2.dp else 1.dp))
    }
}

@Composable
private fun ResultPanel(a: Account, plainHttp: Boolean) {
    val df = DateTimeFormatter.ofPattern("d MMM yyyy")
    Column(Modifier.width(560.dp).background(Tube.ground).padding(28.dp).then(Modifier)) {
        Row(verticalAlignment = Alignment.CenterVertically) { LiveDot(); Spacer(Modifier.width(10.dp)); Label("Connection test") }
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
        Text("The guide and catalogue are cached on this device and refreshed at most every six hours. Your provider sees a handful of requests a day, not one per screen.", style = Tube.body.copy(color = Tube.muted))
    }
}

@Composable
private fun KV(k: String, v: String, amber: Boolean = false) {
    Column {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(k, style = Tube.listName.copy(color = Tube.muted))
            Spacer(Modifier.weight(1f))
            Text(v, style = Tube.mono16.copy(color = if (amber) Tube.amber else Tube.text))
        }
        Hairline(color = Tube.hair2)
    }
}
