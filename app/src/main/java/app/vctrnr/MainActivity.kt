package app.vctrnr

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.vctrnr.ui.TrainerScreen
import app.vctrnr.ui.VctrnrTheme

/**
 * The whole app. On screen = running; off screen (home, recents, back, screen off) = off.
 * There is deliberately no service, so nothing ever records or plays in the background.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: TrainerController

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permanentlyDenied = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        controller.onMicPermissionResult(granted, permanentlyDenied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // The app only works while it's on screen, so don't let the screen time out on you
        // mid-session. Press the power button (or leave the app) to turn everything off.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Volume keys control media (playback) volume while the app is open.
        volumeControlStream = AudioManager.STREAM_MUSIC

        controller = TrainerController(this, requestMicPermission = ::requestMic)

        setContent {
            VctrnrTheme {
                val state by controller.state.collectAsState()
                TrainerScreen(
                    state = state,
                    onToggle = controller::toggle,
                    onOpenFolder = controller::openRecordingsFolder,
                    onSettingsChange = controller::updateSettings,
                    onRequestMic = ::requestMic,
                    onMessageShown = controller::dismissMessage,
                )
            }
        }

        if (savedInstanceState == null && !controller.hasMicPermission()) requestMic()
    }

    override fun onStart() {
        super.onStart()
        controller.open()
    }

    override fun onStop() {
        controller.close()
        super.onStop()
    }

    private fun requestMic() {
        if (controller.state.value.micPermanentlyDenied) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
