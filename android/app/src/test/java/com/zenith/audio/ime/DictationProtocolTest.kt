package com.zenith.audio.ime

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DictationProtocolTest {

    @Test
    fun `frame header carries type and big-endian length`() {
        val frame = DictationProtocol.encodeFrame(
            DictationProtocol.FrameType.RESULT,
            byteArrayOf(0x41, 0x42)
        )

        assertEquals(0x81, frame[0].toInt() and 0xFF)
        assertEquals(2, ByteBuffer.wrap(frame, 1, 4).int)
        assertArrayEquals(byteArrayOf(0x41, 0x42), frame.copyOfRange(5, 7))
    }

    @Test
    fun `start frame declares 48 kHz mono s16le and the token`() {
        val start = DictationProtocol.encodeStart(token = "segredo", live = true)
        val parser = DictationFrameParser()
        val frames = parser.push(start)

        assertEquals(1, frames.size)
        assertEquals(DictationProtocol.FrameType.START, frames[0].type)

        val json = JSONObject(String(frames[0].payload, Charsets.UTF_8))
        assertEquals(1, json.getInt("version"))
        assertEquals(48000, json.getInt("sampleRate"))
        assertEquals(1, json.getInt("channels"))
        assertEquals("s16le", json.getString("format"))
        assertEquals("segredo", json.getString("token"))
        assertTrue(json.getBoolean("live"))
    }

    @Test
    fun `audio frame preserves the pcm slice`() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val audio = DictationProtocol.encodeAudio(source, offset = 2, size = 4)
        val frames = DictationFrameParser().push(audio)

        assertEquals(DictationProtocol.FrameType.AUDIO, frames[0].type)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), frames[0].payload)
    }

    @Test
    fun `parser reassembles a frame split across reads`() {
        val frame = DictationProtocol.encodeFrame(
            DictationProtocol.FrameType.PARTIAL,
            "{\"text\":\"olá\"}".toByteArray(Charsets.UTF_8)
        )
        val parser = DictationFrameParser()

        assertTrue(parser.push(frame, 0, 3).isEmpty())
        val frames = parser.push(frame, 3, frame.size - 3)

        assertEquals(1, frames.size)
        assertArrayEquals(frame.copyOfRange(5, frame.size), frames[0].payload)
    }

    @Test
    fun `parser returns every frame from one read`() {
        val start = DictationProtocol.encodeStart("")
        val end = DictationProtocol.encodeEnd()
        val parser = DictationFrameParser()

        val frames = parser.push(start + end)

        assertEquals(2, frames.size)
        assertEquals(DictationProtocol.FrameType.START, frames[0].type)
        assertEquals(DictationProtocol.FrameType.END, frames[1].type)
        assertEquals(0, frames[1].payload.size)
    }

    @Test
    fun `parser rejects a frame above the size cap`() {
        val header = ByteBuffer.allocate(DictationProtocol.HEADER_BYTES)
            .put(0x01)
            .putInt(DictationProtocol.MAX_FRAME_BYTES + 1)
            .array()

        assertThrows(IllegalArgumentException::class.java) {
            DictationFrameParser().push(header)
        }
    }

    @Test
    fun `partial carries the provisional text`() {
        val json = JSONObject()
            .put("version", 1)
            .put("pass", 3)
            .put("text", "…")
            .put("startMs", 1200)
            .put("endMs", 5200)
            .put("elapsedMs", 140)

        val partial = DictationProtocol.parsePartial(json.toString().toByteArray(Charsets.UTF_8))

        assertEquals(3, partial.pass)
        assertEquals("…", partial.text)
        assertEquals(1200, partial.startMs)
        assertEquals(5200, partial.endMs)
        assertEquals(140, partial.elapsedMs)
    }

    @Test
    fun `result carries the final text and timings`() {
        val json = JSONObject()
            .put("version", 1)
            .put("text", "Criar uma função que soma dois números.")
            .put("model", "custom-7d13a4fb474b2100")
            .put("language", "pt")
            .put("durationMs", 8200)
            .put("inferenceMs", 410)
            .put("livePasses", 7)

        val result = DictationProtocol.parseResult(json.toString().toByteArray(Charsets.UTF_8))

        assertEquals("Criar uma função que soma dois números.", result.text)
        assertEquals("custom-7d13a4fb474b2100", result.model)
        assertEquals("pt", result.language)
        assertEquals(8200, result.durationMs)
        assertEquals(410, result.inferenceMs)
        assertEquals(7, result.livePasses)
    }

    @Test
    fun `error carries the bridge code`() {
        val json = JSONObject()
            .put("version", 1)
            .put("code", "model-busy")
            .put("message", "another dictation is active")

        val failure = DictationProtocol.parseFailure(json.toString().toByteArray(Charsets.UTF_8))

        assertEquals("model-busy", failure.code)
        assertEquals("another dictation is active", failure.message)
    }
}
