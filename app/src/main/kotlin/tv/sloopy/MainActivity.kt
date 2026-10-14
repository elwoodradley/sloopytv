package tv.sloopy

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import tv.sloopy.ui.SloopyRoot
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.theme.SloopyTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    private val vm: SloopyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Credentials are typed on the provider screen; keep them out of the recents thumbnail and screen capture.
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            SloopyTheme {
                SloopyRoot(vm = vm, onQuit = { finishAndRemoveTask() })
            }
        }
        // Refresh-rate matching: ask the display for a mode whose rate is a multiple of the stream's
        // frame rate (50 fps European channels on a 60 Hz TV judder otherwise). Opt-in: the TV
        // blanks for a moment on every switch.
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(vm.player.state.map { it.frameRate }, vm.frameRateMatch) { fps, on -> fps to on }
                .distinctUntilChanged().collect { (fps, on) -> matchRefreshRate(if (on) fps else 0f) }
        }
        // The updater's system screens are started from here, the foreground Activity.
        lifecycleScope.launch {
            vm.updater.launches.collect { runCatching { startActivity(it) }.onFailure { e -> TLog.w { "could not open the system screen: ${TLog.userMessage(e)}" } } }
        }
    }

    private fun matchRefreshRate(fps: Float) {
        val lp = window.attributes
        if (fps <= 0f) {
            if (lp.preferredDisplayModeId != 0) { lp.preferredDisplayModeId = 0; window.attributes = lp }
            return
        }
        @Suppress("DEPRECATION")
        val display = (if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        val cur = display.mode
        // Lower is better: exact rate beats a multiple, same resolution beats a change.
        fun score(m: android.view.Display.Mode): Double? {
            for (k in 1..4) if (kotlin.math.abs(m.refreshRate - fps * k) < 0.2f) return k.toDouble() + (if (m.physicalWidth == cur.physicalWidth && m.physicalHeight == cur.physicalHeight) 0.0 else 10.0)
            return null
        }
        val best = display.supportedModes.mapNotNull { m -> score(m)?.let { it to m } }.minByOrNull { it.first }?.second ?: return
        if (best.modeId != cur.modeId && lp.preferredDisplayModeId != best.modeId) {
            TLog.d { "display mode ${cur.refreshRate} Hz -> ${best.refreshRate} Hz for $fps fps" }
            lp.preferredDisplayModeId = best.modeId; window.attributes = lp
        }
    }

    private var backHeld = false

    /** Holding Back for a moment closes whatever is open and returns to live TV. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
                if (!backHeld) { backHeld = true; vm.goHome() }
                return true
            }
            if (event.action == android.view.KeyEvent.ACTION_UP && backHeld) {
                backHeld = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        vm.saveProgress()
        vm.player.player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (vm.playing.value != null) vm.player.player.play()
    }
}
