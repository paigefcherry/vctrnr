package app.vctrnr.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import app.vctrnr.R
import app.vctrnr.audio.OutputLine

/**
 * A media session that makes the earbuds' play/pause gesture (single tap on Galaxy Buds)
 * start and stop the trainer, exactly like the on-screen button. It also drives the player
 * shown in the notification shade and on the lock screen (play/pause + Close).
 *
 * Only exists while vctrnr is open. No media-button receiver is registered in the manifest,
 * so taps can never relaunch the app once it's closed.
 */
class EarbudControls(
    context: Context,
    handler: Handler,
    private val onToggle: () -> Unit,
    private val onStopRequested: () -> Unit,
    private val onCloseRequested: () -> Unit,
) {
    private val session = MediaSession(context, "vctrnr")
    private var lastToggleAt = 0L
    private var running = false

    val sessionToken: MediaSession.Token get() = session.sessionToken

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

                // Lock-screen / notification player buttons, and Bluetooth stacks that use
                // transport controls instead of key events.
                override fun onPlay() = tap()
                override fun onPause() = tap()
                override fun onStop() = onStopRequested()

                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == ACTION_CLOSE) onCloseRequested()
                }
            },
            handler,
        )
        session.setPlaybackToLocal(OutputLine.ATTRIBUTES)
        update(running = false, status = "Off")
        session.isActive = true
    }

    /**
     * [running] is reported to the earbuds over Bluetooth as "playing", so the next tap is
     * sent as pause, and vice versa. [status] is shown as the subtitle in the media player.
     */
    fun update(running: Boolean, status: String) {
        this.running = running
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
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(ACTION_CLOSE, "Close vctrnr", R.drawable.ic_close).build(),
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "vctrnr")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, status)
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
        const val ACTION_CLOSE = "app.vctrnr.CLOSE"
    }
}
