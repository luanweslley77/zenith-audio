package com.zenith.audio.mic

import android.content.Context

/**
 * Single source of truth for the microphone preferences shared by the
 * streaming service and the dictation IME. Both capture paths must use the
 * same source, input device, gain and limiter configuration.
 */
object MicSettingsStore {
    private const val PREFS = "zenith_prefs"
    private const val KEY_SOURCE_MODE = "mic_source_mode"
    private const val KEY_INPUT_DEVICE_ID = "mic_input_device_id"
    private const val KEY_INPUT_DEVICE_ADDRESS = "mic_input_device_address"
    private const val KEY_GAIN_DB = "mic_gain_db"
    private const val KEY_NS_PERCENT = "mic_ns_percent"

    fun read(context: Context): MicSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MicSettings(
            sourceMode = MicSourceMode.fromStored(prefs.getString(KEY_SOURCE_MODE, null)),
            inputDeviceId = prefs.getInt(KEY_INPUT_DEVICE_ID, MicSettings.DEFAULT_INPUT_DEVICE),
            gainDb = prefs.getFloat(KEY_GAIN_DB, MicSettings.DEFAULT_GAIN_DB),
            noiseSuppressionPercent = prefs.getInt(
                KEY_NS_PERCENT,
                MicSettings.DEFAULT_NOISE_SUPPRESSION_PERCENT
            ),
            inputDeviceAddress = prefs.getString(KEY_INPUT_DEVICE_ADDRESS, "") ?: ""
        )
    }

    fun write(context: Context, settings: MicSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCE_MODE, settings.sourceMode.name)
            .putInt(KEY_INPUT_DEVICE_ID, settings.inputDeviceId)
            .putString(KEY_INPUT_DEVICE_ADDRESS, settings.inputDeviceAddress)
            .putFloat(KEY_GAIN_DB, settings.gainDb)
            .putInt(KEY_NS_PERCENT, settings.noiseSuppressionPercent)
            .apply()
    }
}
