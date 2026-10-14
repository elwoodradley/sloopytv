package tv.sloopy

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
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
        // The updater's system screens are started from here, the foreground Activity.
        lifecycleScope.launch {
            vm.updater.launches.collect { runCatching { startActivity(it) }.onFailure { e -> TLog.w { "could not open the system screen: ${TLog.userMessage(e)}" } } }
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
