package tv.sloopy.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.sloopy.ui.theme.Sloopy

/** A short list of choices over the screen. Back cancels (both halves of the key consumed). */
@Composable
fun ChoicePrompt(title: String, options: List<Pair<String, String>>, current: String? = null, onPick: (String) -> Unit, onCancel: () -> Unit) {
    // The prompt moves its own cursor: Up/Down pick the row, Left/Right are swallowed, so focus
    // can never wander into the screen behind it.
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    var idx by remember { mutableStateOf(0) }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(idx) { delay(30); runCatching { requesters[idx].requestFocus() } }
    // Screens underneath (the live picture) grab focus for themselves on their own schedule;
    // while the prompt is up it takes it back.
    LaunchedEffect(Unit) { while (true) { delay(250); if (!hasFocus) runCatching { requesters[idx].requestFocus() } } }
    // Phone remote apps keep repeating OK while the finger is down, so only a non-repeat press
    // after a short grace period counts.
    var okArmed by remember { mutableStateOf(false) }
    val openedAt = remember { System.currentTimeMillis() }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .onFocusChanged { hasFocus = it.hasFocus }
            .onPreviewKeyEvent {
                when {
                    it.inKeys(BACK_KEYS) -> { if (!it.isDown) onCancel(); true }
                    it.inKeys(OK_KEYS) -> {
                        val fresh = it.nativeKeyEvent.repeatCount == 0 && System.currentTimeMillis() - openedAt > 400
                        if (it.isDown) { if (fresh) okArmed = true; !fresh } else !okArmed
                    }
                    it.isKey(Key.DirectionUp) -> { if (it.isDown && idx > 0) idx--; true }
                    it.isKey(Key.DirectionDown) -> { if (it.isDown && idx < options.lastIndex) idx++; true }
                    it.isKey(Key.DirectionLeft, Key.DirectionRight) -> true
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.background(Sloopy.ground).padding(40.dp).width(520.dp)) {
            Text(title, style = Sloopy.titleBig)
            Spacer(Modifier.height(24.dp))
            options.forEachIndexed { i, (key, label) ->
                SloopyButton(label + (if (key == current) "  ·  now" else ""), onClick = { onPick(key) }, primary = key == current,
                    modifier = Modifier.focusRequester(requesters[i]).onFocusChanged { if (it.isFocused) idx = i })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
