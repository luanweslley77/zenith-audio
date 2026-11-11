package com.zenith.audio.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {

    @Test
    fun `two seconds of silence after speech trigger exactly once`() {
        val detector = SilenceDetector()
        val silence = frame(amplitude = 0)
        speak(detector)

        repeat(199) {
            assertFalse("frame ${it + 1} fired too early", detector.onFrame(silence))
        }
        assertTrue("200 x 10 ms must fire", detector.onFrame(silence))
        assertFalse("must not fire twice", detector.onFrame(silence))
    }

    @Test
    fun `silence before any speech never fires`() {
        val detector = SilenceDetector()
        val silence = frame(amplitude = 0)

        // Ten seconds of nothing said: the session stays open.
        repeat(1_000) { assertFalse("frame ${it + 1} fired", detector.onFrame(silence)) }
        assertEquals(0, detector.silentDurationMs)
    }

    @Test
    fun `one short click does not arm the silence stop`() {
        val detector = SilenceDetector()
        val silence = frame(amplitude = 0)

        // A finger tap or a keyboard clack lasts ~10 ms, not a word.
        assertFalse(detector.onFrame(frame(amplitude = 4_000)))
        repeat(1_000) { assertFalse(detector.onFrame(silence)) }
        assertEquals(0, detector.silentDurationMs)
    }

    @Test
    fun `isolated clicks do not accumulate into speech`() {
        val detector = SilenceDetector()
        val speech = frame(amplitude = 4_000)
        val silence = frame(amplitude = 0)

        // Ten clicks, each separated by more than the 300 ms speech window.
        repeat(10) {
            assertFalse(detector.onFrame(speech))
            repeat(40) { assertFalse(detector.onFrame(silence)) }
        }
        repeat(1_000) { assertFalse(detector.onFrame(silence)) }
        assertEquals(0, detector.silentDurationMs)
    }

    @Test
    fun `fragmented weak speech still arms the stop`() {
        val detector = SilenceDetector()
        val speech = frame(amplitude = 4_000)
        val silence = frame(amplitude = 0)

        // Half the frames are above the threshold for 200 ms: only 100 ms of
        // speech, but enough to arm the clock.
        repeat(10) {
            assertFalse(detector.onFrame(speech))
            assertFalse(detector.onFrame(silence))
        }
        repeat(198) { assertFalse(detector.onFrame(silence)) }
        assertTrue(detector.onFrame(silence))
    }

    @Test
    fun `a short gap inside a word still arms the stop`() {
        val detector = SilenceDetector()
        val speech = frame(amplitude = 4_000)
        val silence = frame(amplitude = 0)

        repeat(6) { assertFalse(detector.onFrame(speech)) }
        assertFalse(detector.onFrame(silence))
        repeat(6) { assertFalse(detector.onFrame(speech)) }

        repeat(199) { assertFalse(detector.onFrame(silence)) }
        assertTrue(detector.onFrame(silence))
    }

    @Test
    fun `speech resets the silence run`() {
        val detector = SilenceDetector()
        val silence = frame(amplitude = 0)
        val speech = frame(amplitude = 4_000)

        repeat(100) { assertFalse(detector.onFrame(silence)) }
        repeat(10) { assertFalse(detector.onFrame(speech)) }
        repeat(199) { assertFalse(detector.onFrame(silence)) }
        assertTrue(detector.onFrame(silence))
    }

    @Test
    fun `negative samples count as speech energy`() {
        val detector = SilenceDetector()
        val silence = frame(amplitude = 0)
        val speech = frame(amplitude = -4_000)

        repeat(150) { detector.onFrame(silence) }
        repeat(10) { assertFalse(detector.onFrame(speech)) }
        assertEquals(0, detector.silentDurationMs)

        repeat(199) { assertFalse(detector.onFrame(silence)) }
        assertTrue(detector.onFrame(silence))
    }

    @Test
    fun `a partial frame uses its real duration`() {
        val detector = SilenceDetector()
        speak(detector)
        // 240 samples = 5 ms at 48 kHz; 400 frames = 2000 ms.
        val halfFrame = frame(amplitude = 0, samples = 240)

        repeat(399) { assertFalse(detector.onFrame(halfFrame)) }
        assertTrue(detector.onFrame(halfFrame))
    }

    /** 10 x 10 ms of speech, the minimum that arms the silence clock. */
    private fun speak(detector: SilenceDetector) {
        val speech = frame(amplitude = 4_000)
        repeat(10) { assertFalse(detector.onFrame(speech)) }
    }

    private fun frame(amplitude: Int, samples: Int = 480): ByteArray {
        val bytes = ByteArray(samples * 2)
        val value = amplitude.toShort()
        for (i in 0 until samples) {
            bytes[i * 2] = (value.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
