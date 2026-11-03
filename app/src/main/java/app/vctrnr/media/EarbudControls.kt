package app.vctrnr.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import app.vctrnr.audio.OutputLine

/**
 * A media session that makes the earbuds' play/pause gesture (single tap on Galaxy Buds)
 * start and stop the trainer, exactly like the on-screen button.
 *
 * Only exists while the app is on screen. No media-button receiver is registered in the
 * manifest, so taps can never relaunch the app once it's closed.
 */
class EarbudControls(
    context: Context,
    handler: Handler,
    private val onToggle: () -> Unit,
    private val onStopRequested: () -> Unit,
) {
    private val session = MediaSession(context, "vctrnr")
    private var lastToggleAt = 0L

    init {
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        ?: return super.onMediaButtonEvent(mediaButtonIntent)
                    return when (event.keyCode) {
                        // Buds send PLAY or PAUSE depending on what they think the state is;
                        // other headsets send PLAY_PAUSE / HEADSETHOOK. All of them mean "tap".
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK,
                        -> {
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) tap()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_STOP -> {
                            if (event.action == KeyEvent.ACTION_DOWN) onStopRequested()
                            true
                        }
                        else -> super.onMediaButtonEvent(mediaButtonIntent)
                    }
                }

                // Some Bluetooth stacks / controllers use transport controls instead of keys.
                override fun onPlay() = tap()
                override fun onPause() = tap()
                override fun onStop() = onStopRequested()
            },
            handler,
        )
        session.setPlaybackToLocal(OutputLine.ATTRIBUTES)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "vctrnr")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Tap an earbud to start or stop")
                .build(),
        )
        setRunning(false)
        session.isActive = true
    }

    /**
     * Reported to the earbuds over Bluetooth (AVRCP): "playing" while the trainer is on, so
     * the next tap is sent as pause, and vice versa.
     */
    fun setRunning(running: Boolean) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (running) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (running) 1f else 0f,
                )
                .build(),
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    /** One physical tap can arrive twice on some stacks (key + transport call); debounce it. */
    private fun tap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleAt < DEBOUNCE_MS) return
        lastToggleAt = now
        onToggle()
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}
