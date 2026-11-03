package app.vctrnr.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock

/** Which microphone a session listens with. */
enum class Mic { Earbuds, Phone }

/**
 * Switches the earbuds between "call mode" (their microphones are live) and normal "music
 * mode" (full-quality playback).
 *
 * Bluetooth earbuds can only send their microphone audio to the phone over the call channel
 * (HFP/SCO, or the LE Audio equivalent). That channel is noise-suppressed and lower bandwidth
 * in both directions, so vctrnr listens in call mode, then flips back to music mode to play
 * each take at full quality.
 *
 * All methods block briefly and are meant to be called from the audio thread.
 */
class EarbudMic(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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

    /**
     * Puts the earbuds into call mode and waits (up to a few seconds) for the link to come up.
     * Returns false, leaving everything back in music mode, if it couldn't.
     */
    fun enter(isCancelled: () -> Boolean): Boolean {
        val device = headset() ?: return false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!audioManager.setCommunicationDevice(device)) {
            exit()
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
        while (!isCancelled() && SystemClock.elapsedRealtime() < deadline) {
            if (audioManager.communicationDevice?.id == device.id) {
                pause(CALL_SETTLE_MS, isCancelled)
                if (isCancelled()) break
                return true
            }
            SystemClock.sleep(POLL_MS)
        }
        exit()
        return false
    }

    /** Back to music mode. Safe to call when not in call mode. */
    fun exit() {
        runCatching { audioManager.clearCommunicationDevice() }
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    /**
     * After [exit], wait until media audio is flowing to the earbuds again so the start of the
     * playback isn't swallowed while the Bluetooth link switches over.
     */
    fun waitForMusicMode(output: OutputLine, isCancelled: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + SWITCH_TIMEOUT_MS
        while (!isCancelled() && SystemClock.elapsedRealtime() < deadline &&
            output.routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            SystemClock.sleep(POLL_MS)
        }
        pause(MEDIA_SETTLE_MS, isCancelled)
    }

    private fun pause(ms: Long, isCancelled: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!isCancelled() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(POLL_MS)
    }

    private companion object {
        const val POLL_MS = 40L
        const val CONNECT_TIMEOUT_MS = 4000L
        const val SWITCH_TIMEOUT_MS = 2500L
        const val CALL_SETTLE_MS = 300L // let the call link's audio actually start
        const val MEDIA_SETTLE_MS = 600L // let the buds wake their music stream
    }
}
