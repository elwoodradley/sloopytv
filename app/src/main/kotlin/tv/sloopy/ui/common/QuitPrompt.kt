package tv.sloopy.ui.common

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.sloopy.ui.theme.Sloopy

/** "Switch off?" Back or Stay tuned cancels. */
@Composable
fun QuitPrompt(onQuit: () -> Unit, onCancel: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .onPreviewKeyEvent { if (it.isDown && it.inKeys(BACK_KEYS)) { onCancel(); true } else false },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.background(Sloopy.ground).padding(40.dp)) {
            Text("SWITCH OFF?", style = Sloopy.titleBig)
            Spacer(Modifier.height(24.dp))
            Row {
                SloopyButton("Switch off", onClick = onQuit, primary = true, modifier = Modifier.focusRequester(first))
                Spacer(Modifier.width(12.dp))
                SloopyButton("Stay tuned", onClick = onCancel)
            }
        }
    }
}
