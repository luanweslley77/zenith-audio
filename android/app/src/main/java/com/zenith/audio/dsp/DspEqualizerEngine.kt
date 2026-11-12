package com.zenith.audio.dsp

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DspEqualizerEngine(private val context: Context) {
    companion object {
        private const val TAG = "DspEqualizerEngine"
        private const val PREFS_NAME = "zenith_dsp_prefs"
        private const val KEY_PRESET = "selected_preset"
        private const val KEY_BASS_BOOST = "bass_boost_strength"
        private const val KEY_ENABLED = "dsp_enabled"
    }

    enum class Preset(val displayName: String, val description: String) {
        FLAT("Audiophile Flat", "Pure studio reference, unaltered bit-perfect sound"),
        GAMING_FPS("Gaming FPS", "Footstep & weapon clarity (+7dB upper-mids/treble)"),
        BASS_BEAST("Bass Beast", "Deep chest-thumping sub-bass (+10dB boost & hardware sub-resonator)"),
        CINEMA_VOCAL("Cinema & Vocal", "Dialogue intelligibility boost (+5dB speech presence)")
    }

    data class BandInfo(
        val index: Int,
        val centerFreqHz: Int,
        val minMilliBels: Int,
        val maxMilliBels: Int
    )

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _currentPreset = MutableStateFlow(
        runCatching {
            Preset.valueOf(prefs.getString(KEY_PRESET, Preset.FLAT.name) ?: Preset.FLAT.name)
        }.getOrDefault(Preset.FLAT)
    )
    val currentPreset: StateFlow<Preset> = _currentPreset.asStateFlow()

    private val _bassStrength = MutableStateFlow(prefs.getInt(KEY_BASS_BOOST, 0))
    val bassStrength: StateFlow<Int> = _bassStrength.asStateFlow()

    private val _bands = MutableStateFlow<List<BandInfo>>(emptyList())
    val bands: StateFlow<List<BandInfo>> = _bands.asStateFlow()

    private val _bandLevels = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val bandLevels: StateFlow<Map<Int, Int>> = _bandLevels.asStateFlow()

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0) return
        detach()

        var eq: Equalizer? = null
        var bb: BassBoost? = null
        try {
            eq = Equalizer(0, audioSessionId).apply {
                enabled = _isEnabled.value
            }

            bb = BassBoost(0, audioSessionId).apply {
                enabled = _isEnabled.value
                if (strengthSupported) {
                    setStrength(_bassStrength.value.toShort())
                }
            }

            // Read hardware band info
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()

            val bandList = mutableListOf<BandInfo>()
            val currentLevels = mutableMapOf<Int, Int>()
            val savedLevels = mutableMapOf<Int, Int>()

            for (i in 0 until numBands) {
                val centerFreqHz = eq.getCenterFreq(i.toShort()) / 1000
                bandList.add(BandInfo(i, centerFreqHz, minMb, maxMb))
                val savedLevel = prefs.getInt("band_level_$i", eq.getBandLevel(i.toShort()).toInt())
                currentLevels[i] = savedLevel
                if (prefs.contains("band_level_$i")) {
                    savedLevels[i] = savedLevel
                }
                try {
                    eq.setBandLevel(i.toShort(), savedLevel.toShort())
                } catch (_: Exception) {}
            }

            equalizer = eq
            bassBoost = bb
            _bands.value = bandList
            _bandLevels.value = currentLevels

            // A saved band curve is the user's real state. Materialize the
            // preset for bands never touched, then overlay every saved band
            // and the saved bass value.
            if (savedLevels.isEmpty()) {
                applyPresetInternal(_currentPreset.value, savePref = false)
            } else {
                applyPresetInternal(_currentPreset.value, savePref = false)
                val merged = _bandLevels.value.toMutableMap()
                for ((band, level) in savedLevels) {
                    try {
                        eq.setBandLevel(band.toShort(), level.toShort())
                    } catch (_: Exception) {}
                    merged[band] = level
                }
                _bandLevels.value = merged
                if (prefs.contains(KEY_BASS_BOOST)) {
                    // applyPresetInternal already overwrote _bassStrength with
                    // the preset default; read the user's saved value instead.
                    setBassBoost(prefs.getInt(KEY_BASS_BOOST, _bassStrength.value), persist = false)
                }
            }

            Log.i(TAG, "Hardware DSP attached to session $audioSessionId with $numBands bands")
        } catch (e: Exception) {
            try { eq?.release() } catch (_: Exception) {}
            try { bb?.release() } catch (_: Exception) {}
            equalizer = null
            bassBoost = null
            _bands.value = emptyList()
            _bandLevels.value = emptyMap()
            Log.e(TAG, "Failed to initialize hardware DSP Equalizer", e)
        }
    }

    fun detach() {
        try {
            equalizer?.release()
        } catch (_: Exception) {}
        try {
            bassBoost?.release()
        } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        try {
            equalizer?.enabled = enabled
            bassBoost?.enabled = enabled
        } catch (e: Exception) {
            Log.w(TAG, "Error toggling DSP: ${e.message}")
        }
    }

    fun setPreset(preset: Preset) {
        _currentPreset.value = preset
        prefs.edit().putString(KEY_PRESET, preset.name).apply()
        applyPresetInternal(preset, savePref = true)
    }

    fun setBassBoost(strength: Int, persist: Boolean = true) {
        val clamped = strength.coerceIn(0, 1000)
        _bassStrength.value = clamped
        if (persist) {
            prefs.edit().putInt(KEY_BASS_BOOST, clamped).apply()
        }
        try {
            if (bassBoost?.strengthSupported == true) {
                bassBoost?.setStrength(clamped.toShort())
            }
        } catch (_: Exception) {}
    }

    fun setBandLevel(bandIndex: Int, levelMilliBels: Int) {
        val eq = equalizer ?: return
        try {
            val range = eq.bandLevelRange
            val clamped = levelMilliBels.coerceIn(range[0].toInt(), range[1].toInt())
            eq.setBandLevel(bandIndex.toShort(), clamped.toShort())

            val currentMap = _bandLevels.value.toMutableMap()
            currentMap[bandIndex] = clamped
            _bandLevels.value = currentMap

            prefs.edit().putInt("band_level_$bandIndex", clamped).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Error setting band $bandIndex level: ${e.message}")
        }
    }

    private fun applyPresetInternal(preset: Preset, savePref: Boolean) {
        val eq = equalizer ?: return
        val numBands = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        val minMb = range[0].toInt()
        val maxMb = range[1].toInt()

        val newLevels = mutableMapOf<Int, Int>()

        when (preset) {
            Preset.FLAT -> {
                for (i in 0 until numBands) {
                    newLevels[i] = 0
                }
                setBassBoost(0, persist = savePref)
            }
            Preset.GAMING_FPS -> {
                // Boost upper midrange and treble (footstep / reload cues)
                for (i in 0 until numBands) {
                    val progress = i.toFloat() / (numBands - 1).coerceAtLeast(1)
                    val boostMb = if (progress >= 0.5f) {
                        (700 * ((progress - 0.5f) / 0.5f)).toInt() // +7dB at highest band
                    } else {
                        -100 // slight low cut to eliminate rumble
                    }
                    newLevels[i] = boostMb.coerceIn(minMb, maxMb)
                }
                setBassBoost(0, persist = savePref)
            }
            Preset.BASS_BEAST -> {
                // Heavy sub-bass and bass boost
                for (i in 0 until numBands) {
                    val progress = i.toFloat() / (numBands - 1).coerceAtLeast(1)
                    val boostMb = if (progress <= 0.4f) {
                        (1000 * (1.0f - (progress / 0.4f))).toInt() // +10dB at lowest band
                    } else {
                        100
                    }
                    newLevels[i] = boostMb.coerceIn(minMb, maxMb)
                }
                setBassBoost(850, persist = savePref)
            }
            Preset.CINEMA_VOCAL -> {
                // Vocal presence in 500Hz - 3kHz range
                for (i in 0 until numBands) {
                    val progress = i.toFloat() / (numBands - 1).coerceAtLeast(1)
                    val distFromCenter = Math.abs(progress - 0.5f)
                    val boostMb = (550 * (1.0f - (distFromCenter * 2.0f))).toInt()
                    newLevels[i] = boostMb.coerceIn(minMb, maxMb)
                }
                setBassBoost(300, persist = savePref)
            }
        }

        for ((band, level) in newLevels) {
            try {
                eq.setBandLevel(band.toShort(), level.toShort())
                if (savePref) {
                    prefs.edit().putInt("band_level_$band", level).apply()
                }
            } catch (_: Exception) {}
        }
        _bandLevels.value = newLevels
    }
}
