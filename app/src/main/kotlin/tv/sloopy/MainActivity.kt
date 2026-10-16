package tv.sloopy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
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
        androidx.core.content.ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
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
        lifecycleScope.launch {
            vm.externalLaunches.collect { runCatching { startActivity(it) }.onFailure { vm.toast("Could not open VLC") } }
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

    // Leaving the app (Home, the box going to sleep, another app taking over) switches it off:
    // sound stops at once and the task is gone from the launcher. The only time the app merely
    // pauses is while one of its own child windows is up (the speech recogniser, the folder
    // picker, VLC, the installer), so coming back from those lands where you left.
    private var childLaunchAt = 0L
    private val screenOff = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { shutDown() } }
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) { childLaunchAt = SystemClock.uptimeMillis(); super.startActivityForResult(intent, requestCode, options) }
    override fun startActivity(intent: Intent?, options: Bundle?) { childLaunchAt = SystemClock.uptimeMillis(); super.startActivity(intent, options) }
    private fun shutDown() {
        if (isFinishing) return
        vm.saveProgress()
        vm.stopPlayback()
        finishAndRemoveTask()
    }

    override fun onStop() {
        super.onStop()
        vm.saveProgress()
        if (SystemClock.uptimeMillis() - childLaunchAt < 4_000 && !isFinishing) { vm.player.player.pause(); return }
        shutDown()
    }

    override fun onStart() {
        super.onStart()
        if (vm.playing.value != null) vm.player.player.play()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }
}
