package tv.tube

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.media3.common.util.UnstableApi
import tv.tube.ui.TubeRoot
import tv.tube.ui.TubeViewModel
import tv.tube.ui.theme.TubeTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    private val vm: TubeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Credentials are typed on the provider screen; keep them out of the recents thumbnail and screen capture.
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            TubeTheme {
                TubeRoot(vm = vm, onQuit = { finishAndRemoveTask() })
            }
        }
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
