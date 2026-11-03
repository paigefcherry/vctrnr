package app.vctrnr

import android.content.Context

data class TrainerSettings(
    /** How long a pause (ms) ends a take and triggers playback. */
    val pauseMs: Int = 1000,
    /** 0..1, see [app.vctrnr.audio.SpeechDetector.Config.sensitivity]. */
    val sensitivity: Float = 0.5f,
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
    )

    fun save(s: TrainerSettings) {
        prefs.edit().putInt(KEY_PAUSE, s.pauseMs).putFloat(KEY_SENSITIVITY, s.sensitivity).apply()
    }

    private companion object {
        const val KEY_PAUSE = "pause_ms"
        const val KEY_SENSITIVITY = "sensitivity"
    }
}
