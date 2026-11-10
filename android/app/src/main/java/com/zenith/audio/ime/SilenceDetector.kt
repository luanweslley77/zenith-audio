package com.zenith.audio.ime

import kotlin.math.sqrt

/**
 * Watches captured PCM16 frames and reports when the speaker has been silent
 * for [silenceMs]. The IME uses it to end a tap-to-talk session automatically.
 *
 * The detector runs on the recorder thread. [onFrame] returns true exactly
 * once per session when the silence limit is reached, so the caller can post a
 * single stop to the main thread.
 *
 * The threshold is a normalized RMS (-40 dBFS for the default 0.01), the same
 * order of magnitude as the bridge's pause heuristic. It is a constant for now
 * and must be checked against the phone's noise floor.
 */
class SilenceDetector(
    private val sampleRate: Int = DictationProtocol.SAMPLE_RATE,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val silenceMs: Int = DEFAULT_SILENCE_MS
) {
    private var silentMs = 0.0
    private var triggered = false

    fun onFrame(pcm16: ByteArray, size: Int = pcm16.size): Boolean {
        if (triggered || size < 2) return false
        val samples = size / 2
        if (samples == 0) return false

        var sumSquares = 0.0
        var i = 0
        while (i + 1 < size) {
            val raw = (pcm16[i].toInt() and 0xFF) or (pcm16[i + 1].toInt() shl 8)
            val sample = raw.toShort() / 32768.0
            sumSquares += sample * sample
            i += 2
        }
        val rms = sqrt(sumSquares / samples)

        if (rms >= threshold) {
            silentMs = 0.0
            return false
        }

        silentMs += samples * 1000.0 / sampleRate
        if (silentMs >= silenceMs) {
            triggered = true
            return true
        }
        return false
    }

    val silentDurationMs: Int get() = silentMs.toInt()

    companion object {
        /** -40 dBFS, matching the bridge's live-preview pause threshold. */
        const val DEFAULT_THRESHOLD = 0.01f
        const val DEFAULT_SILENCE_MS = 2_000
    }
}
