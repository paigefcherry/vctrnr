package app.vctrnr.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import app.vctrnr.telecom.TrainerCall

/** Which microphone a session listens with. */
enum class Mic { Earbuds, Phone }

/** How takes are played back when listening through the earbud mic. */
enum class EarbudPlayback {
    /** Stay in call mode all session: playback starts right away, at call quality. */
    Instant,

    /** Flip the buds to music mode for each playback: full quality, ~1–2 s slower each way. */
    FullQuality,
}

/**
 * Puts the earbuds into "call mode" (their microphones are live) and back to normal
 * "music mode" (full-quality playback).
 *
 * Bluetooth earbuds can only send their microphone audio to the phone over the call channel
 * (HFP/SCO, or the LE Audio equivalent), which is noise-suppressed and lower bandwidth in
 * both directions.
 *
 * Two ways in:
 *  - [enterAsCall]: registers the session as a Telecom call, so the earbuds' hang-up
 *    gesture reaches the app. Used for whole-session call mode ([EarbudPlayback.Instant]).
 *  - [enter]: just switches the audio mode. Quick to undo, so it's used when flipping back and
 *    forth for [EarbudPlayback.FullQuality]. The hang-up gesture doesn't reach the app here.
 *
 * All methods block briefly and are meant to be called from the audio thread.
 */
class EarbudMic(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private enum class Via { None, Telecom, Direct }

    @Volatile private var via = Via.None

    /** The connected headset that can carry call audio, preferring LE Audio when available. */
    private fun headset(): AudioDeviceInfo? {
        val devices = audioManager.availableCommunicationDevices
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    /** The earbuds' microphone as an input device (only present while connected). */
    fun input(): AudioDeviceInfo? = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun callAudioOnEarbuds(): Boolean = when (audioManager.communicationDevice?.type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
        else -> false
    }

    /**
     * Call mode for a whole session, as a Telecom call (so "hang up" on the earbuds stops it).
     * Falls back to [enter] if Telecom won't place the call or won't route it to the earbuds.
     */
    fun enterAsCall(isCancelled: () -> Boolean): Boolean {
        if (headset() == null) return false
        if (TrainerCall.start(appContext)) {
            waitFor(CALL_START_TIMEOUT_MS, isCancelled) { TrainerCall.state != TrainerCall.State.Connecting }
            if (TrainerCall.state == TrainerCall.State.Active) {
                via = Via.Telecom
                // Telecom normally routes calls to connected earbuds by itself; nudge it if not.
                if (!waitFor(CONNECT_TIMEOUT_MS / 2, isCancelled, ::callAudioOnEarbuds)) {
                    TrainerCall.routeToBluetooth()
                    waitFor(CONNECT_TIMEOUT_MS / 2, isCancelled, ::callAudioOnEarbuds)
                }
                if (callAudioOnEarbuds()) {
                    pause(CALL_SETTLE_MS, isCancelled)
                    if (!isCancelled()) return true
                }
            }
            exit()
        }
        if (isCancelled()) return false
        return enter(isCancelled)
    }

    /**
     * Switches the earbuds into call mode directly and waits (up to a few seconds) for the
     * link to come up. Returns false, leaving everything in music mode, if it couldn't.
     */
    fun enter(isCancelled: () -> Boolean): Boolean {
        val device = headset() ?: return false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        via = Via.Direct
        if (!audioManager.setCommunicationDevice(device)) {
            exit()
            return false
        }
        if (waitFor(CONNECT_TIMEOUT_MS, isCancelled) { audioManager.communicationDevice?.id == device.id }) {
            pause(CALL_SETTLE_MS, isCancelled)
            if (!isCancelled()) return true
        }
        exit()
        return false
    }

    /** Back to music mode. Safe to call when not in call mode. */
    fun exit() {
        when (via) {
            Via.Telecom -> TrainerCall.end()
            Via.Direct -> {
                runCatching { audioManager.clearCommunicationDevice() }
                if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
            }
            Via.None -> {}
        }
        via = Via.None
    }

    /**
     * After [exit], wait until media audio is flowing to the earbuds again so the start of the
     * playback isn't swallowed while the Bluetooth link switches over.
     */
    fun waitForMusicMode(output: OutputLine, isCancelled: () -> Boolean) {
        waitFor(SWITCH_TIMEOUT_MS, isCancelled) { output.routedDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        pause(MEDIA_SETTLE_MS, isCancelled)
    }

    /** Polls [done] until it's true (returns true), or the timeout/cancel (returns false). */
    private fun waitFor(timeoutMs: Long, isCancelled: () -> Boolean, done: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!isCancelled() && SystemClock.elapsedRealtime() < deadline) {
            if (done()) return true
            SystemClock.sleep(POLL_MS)
        }
        return done()
    }

    private fun pause(ms: Long, isCancelled: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!isCancelled() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(POLL_MS)
    }

    private companion object {
        const val POLL_MS = 40L
        const val CALL_START_TIMEOUT_MS = 3000L
        const val CONNECT_TIMEOUT_MS = 4000L
        const val SWITCH_TIMEOUT_MS = 2500L
        const val CALL_SETTLE_MS = 300L // let the call link's audio actually start
        const val MEDIA_SETTLE_MS = 900L // the buds take a while to un-mute music after a call
    }
}
