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
        assertFalse(detector.onFrame(frame(amplitude = 4_000)))

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
    fun `speech resets the silence run`() {
        val detector = SilenceDetector()
        val silence = frame(amplitude = 0)
        val speech = frame(amplitude = 4_000)

        repeat(100) { assertFalse(detector.onFrame(silence)) }
        assertFalse(detector.onFrame(speech))
        repeat(199) { assertFalse(detector.onFrame(silence)) }
        assertTrue(detector.onFrame(silence))
    }

    @Test
    fun `negative samples count as speech energy`() {
        val detector = SilenceDetector()
        val silence = frame(amplitude = 0)
        val speech = frame(amplitude = -4_000)

        repeat(150) { detector.onFrame(silence) }
        assertFalse(detector.onFrame(speech))
        assertTrue(detector.silentDurationMs < 1_500)
    }

    @Test
    fun `a partial frame uses its real duration`() {
        val detector = SilenceDetector()
        assertFalse(detector.onFrame(frame(amplitude = 4_000)))
        // 240 samples = 5 ms at 48 kHz; 400 frames = 2000 ms.
        val halfFrame = frame(amplitude = 0, samples = 240)

        repeat(399) { assertFalse(detector.onFrame(halfFrame)) }
        assertTrue(detector.onFrame(halfFrame))
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
