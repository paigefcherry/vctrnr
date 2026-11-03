package app.vctrnr

import android.content.Context
import app.vctrnr.audio.EarbudPlayback
import app.vctrnr.audio.Mic

data class TrainerSettings(
    /** How long a pause (ms) ends a take and triggers playback. */
    val pauseMs: Int = 1000,
    /** 0..1, see [app.vctrnr.audio.SpeechDetector.Config.sensitivity]. */
    val sensitivity: Float = 0.5f,
    /** Earbuds = hands-free, walk around. Phone = best quality, phone in front of you. */
    val mic: Mic = Mic.Earbuds,
    /** With the earbud mic: stay in call mode (instant) or flip to music mode per playback. */
    val earbudPlayback: EarbudPlayback = EarbudPlayback.Instant,
) {
    companion object {
        const val MIN_PAUSE_MS = 400
        const val MAX_PAUSE_MS = 4000
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = TrainerSettings(
        pauseMs = prefs.getInt(KEY_PAUSE, 1000).coerceIn(TrainerSettings.MIN_PAUSE_MS, TrainerSettings.MAX_PAUSE_MS),
        sensitivity = prefs.getFloat(KEY_SENSITIVITY, 0.5f).coerceIn(0f, 1f),
        mic = Mic.entries.firstOrNull { it.name == prefs.getString(KEY_MIC, null) } ?: Mic.Earbuds,
        earbudPlayback = EarbudPlayback.entries.firstOrNull { it.name == prefs.getString(KEY_EARBUD_PLAYBACK, null) }
            ?: EarbudPlayback.Instant,
    )

    fun save(s: TrainerSettings) {
        prefs.edit()
            .putInt(KEY_PAUSE, s.pauseMs)
            .putFloat(KEY_SENSITIVITY, s.sensitivity)
            .putString(KEY_MIC, s.mic.name)
            .putString(KEY_EARBUD_PLAYBACK, s.earbudPlayback.name)
            .apply()
    }

    private companion object {
        const val KEY_PAUSE = "pause_ms"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_MIC = "mic"
        const val KEY_EARBUD_PLAYBACK = "earbud_playback"
    }
}
