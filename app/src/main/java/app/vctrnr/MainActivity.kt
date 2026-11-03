package app.vctrnr

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.vctrnr.ui.TrainerScreen
import app.vctrnr.ui.VctrnrTheme

/**
 * The screen. The actual trainer lives in [TrainerService] so it keeps going with the screen
 * off; this activity starts it, shows its state and sends it your button presses.
 */
class MainActivity : ComponentActivity() {

    private var service: TrainerService? by mutableStateOf(null)
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as TrainerService.LocalBinder).service
            s.controller.onMicPermissionNeeded = ::requestMic
            // We're on screen, so this is the moment the service may (re)claim mic access.
            s.goForeground()
            service = s
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onMicResult(granted)
    }

    /** First launch: mic (required) + notifications (for the lock-screen controls), together. */
    private val firstRunPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        onMicResult(results[Manifest.permission.RECORD_AUDIO] == true)
    }

    private fun onMicResult(granted: Boolean) {
        val permanentlyDenied = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        service?.let {
            if (granted) it.goForeground() // add the microphone type now that it's allowed
            it.controller.onMicPermissionResult(granted, permanentlyDenied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Volume keys control media (playback) volume while the app is open.
        volumeControlStream = AudioManager.STREAM_MUSIC

        setContent {
            VctrnrTheme {
                val s = service
                if (s == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                } else {
                    val controller = s.controller
                    val state by controller.state.collectAsState()
                    LaunchedEffect(state.closed) {
                        if (state.closed) finishAndRemoveTask()
                    }
                    TrainerScreen(
                        state = state,
                        onToggle = controller::toggle,
                        onOpenFolder = controller::openRecordingsFolder,
                        onSettingsChange = controller::updateSettings,
                        onRequestMic = ::requestMic,
                        onMessageShown = controller::dismissMessage,
                        onClose = controller::requestClose,
                    )
                }
            }
        }

        if (savedInstanceState == null) {
            val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) firstRunPermissions.launch(missing.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, TrainerService::class.java)
        startForegroundService(intent)
        bound = bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        // The trainer keeps running; we just stop drawing it.
        service?.controller?.onMicPermissionNeeded = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        super.onStop()
    }

    private fun requestMic() {
        if (service?.controller?.state?.value?.micPermanentlyDenied == true) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
