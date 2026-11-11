package com.zenith.audio.ime

import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Watches captured PCM16 frames and reports when the speaker has been silent
 * for [silenceMs] after having spoken something. The IME uses it to end a
 * tap-to-talk session automatically.
 *
 * The silence clock only starts after roughly 100 ms of speech above the
 * threshold inside a 300 ms window. Short gaps inside a word are tolerated;
 * opening the microphone and saying nothing must not stop the session, and an
 * isolated click must not arm the clock either.
 *
 * The detector runs on the recorder thread. [onFrame] returns true exactly
 * once per session when the silence limit is reached, so the caller can post a
 * single stop to the main thread.
 *
 * The threshold is a normalized RMS (-40 dBFS for the default 0.01), the same
 * as the bridge's speech gate. It is a constant for now and must be checked
 * against the phone's noise floor.
 */
class SilenceDetector(
    private val sampleRate: Int = DictationProtocol.SAMPLE_RATE,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val silenceMs: Int = DEFAULT_SILENCE_MS
) {
    private data class EnergyFrame(val durationMs: Double, val speech: Boolean)

    private val speechWindow = ArrayDeque<EnergyFrame>()
    private var speechWindowMs = 0.0
    private var speechInWindowMs = 0.0
    private var silentMs = 0.0
    private var heardSpeech = false
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
        val frameMs = samples * 1000.0 / sampleRate
        val speech = rms >= threshold

        // Only above-threshold audio inside the last 300 ms counts. Short gaps
        // inside a word are tolerated; clicks spread over time are not.
        speechWindow.addLast(EnergyFrame(frameMs, speech))
        speechWindowMs += frameMs
        if (speech) speechInWindowMs += frameMs
        if (speechInWindowMs >= MIN_SPEECH_MS) heardSpeech = true
        while (speechWindowMs > SPEECH_WINDOW_MS && speechWindow.isNotEmpty()) {
            val old = speechWindow.removeFirst()
            speechWindowMs -= old.durationMs
            if (old.speech) speechInWindowMs -= old.durationMs
        }

        if (speech) {
            silentMs = 0.0
            return false
        }

        // Nothing has been said yet: keep waiting instead of counting silence.
        if (!heardSpeech) return false

        silentMs += frameMs
        if (silentMs >= silenceMs) {
            triggered = true
            return true
        }
        return false
    }

    val silentDurationMs: Int get() = silentMs.toInt()

    companion object {
        /** -40 dBFS, matching the bridge's live-preview speech gate. */
        const val DEFAULT_THRESHOLD = 0.01f
        const val DEFAULT_SILENCE_MS = 2_000

        /** Speech above the threshold needed before the silence clock starts. */
        const val MIN_SPEECH_MS = 100.0

        /** The speech run must fit inside this window, so scattered clicks cannot add up. */
        const val SPEECH_WINDOW_MS = 300.0
    }
}
