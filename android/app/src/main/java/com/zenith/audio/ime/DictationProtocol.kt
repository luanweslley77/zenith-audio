package com.zenith.audio.ime

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire protocol shared with the PC bridge (`bridge/src/frames.ts`).
 *
 * One TCP connection per dictation. Every frame is:
 *
 * ```text
 * +--------+------------------+----------------------+
 * | type   | length (u32 BE)  | payload              |
 * | 1 byte | 4 bytes          | `length` bytes       |
 * +--------+------------------+----------------------+
 * ```
 *
 * The same bytes are produced and consumed by the Bun tests, so the constants
 * here must not drift from the bridge's FrameType table.
 */
object DictationProtocol {
    const val VERSION = 1

    /** Default bridge port; the bridge's `--port` flag must match. */
    const val DEFAULT_PORT = 59102

    const val SAMPLE_RATE = 48000
    const val CHANNELS = 1
    const val FORMAT = "s16le"

    /** The bridge rejects frames above this size. */
    const val MAX_FRAME_BYTES = 1024 * 1024

    /** 100 ms of 48 kHz mono PCM16, the client's write granularity. */
    const val AUDIO_CHUNK_BYTES = 9600

    object FrameType {
        const val START = 0x01
        const val AUDIO = 0x02
        const val END = 0x03
        const val PARTIAL = 0x04
        const val RESULT = 0x81
        const val ERROR = 0x82
    }

    const val HEADER_BYTES = 5

    data class Frame(val type: Int, val payload: ByteArray)

    data class Partial(
        val pass: Int,
        val text: String,
        val startMs: Int,
        val endMs: Int,
        val elapsedMs: Int
    )

    data class Result(
        val text: String,
        val model: String,
        val language: String,
        val durationMs: Int,
        val inferenceMs: Int,
        val livePasses: Int
    )

    data class Failure(val code: String, val message: String)

    fun encodeFrame(type: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_BYTES) { "frame too large: ${payload.size} bytes" }
        val buffer = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(type.toByte())
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun encodeJson(type: Int, value: JSONObject): ByteArray =
        encodeFrame(type, value.toString().toByteArray(Charsets.UTF_8))

    /**
     * START. `model` and `language` are omitted on purpose: the bridge is the
     * authority for both and refuses a client that asks for something else.
     */
    fun encodeStart(token: String, live: Boolean = true): ByteArray {
        val request = JSONObject()
            .put("version", VERSION)
            .put("sampleRate", SAMPLE_RATE)
            .put("channels", CHANNELS)
            .put("format", FORMAT)
            .put("token", token)
            .put("live", live)
        return encodeJson(FrameType.START, request)
    }

    /** AUDIO, copying `size` bytes starting at `offset` into one frame. */
    fun encodeAudio(source: ByteArray, offset: Int, size: Int): ByteArray {
        require(size in 0..MAX_FRAME_BYTES) { "frame too large: $size bytes" }
        require(offset >= 0 && offset + size <= source.size) { "audio slice out of bounds" }
        val buffer = ByteBuffer.allocate(HEADER_BYTES + size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(FrameType.AUDIO.toByte())
        buffer.putInt(size)
        buffer.put(source, offset, size)
        return buffer.array()
    }

    fun encodeEnd(): ByteArray = encodeFrame(FrameType.END, ByteArray(0))

    fun parsePartial(payload: ByteArray): Partial {
        val json = JSONObject(String(payload, Charsets.UTF_8))
        return Partial(
            pass = json.optInt("pass", 0),
            text = json.optString("text", ""),
            startMs = json.optInt("startMs", 0),
            endMs = json.optInt("endMs", 0),
            elapsedMs = json.optInt("elapsedMs", 0)
        )
    }

    fun parseResult(payload: ByteArray): Result {
        val json = JSONObject(String(payload, Charsets.UTF_8))
        return Result(
            text = json.optString("text", ""),
            model = json.optString("model", ""),
            language = json.optString("language", ""),
            durationMs = json.optInt("durationMs", 0),
            inferenceMs = json.optInt("inferenceMs", 0),
            livePasses = json.optInt("livePasses", 0)
        )
    }

    fun parseFailure(payload: ByteArray): Failure {
        val json = JSONObject(String(payload, Charsets.UTF_8))
        return Failure(
            code = json.optString("code", "internal"),
            message = json.optString("message", "")
        )
    }
}

/**
 * Incremental frame parser for the reply direction.
 *
 * TCP delivers arbitrary chunks, so a frame may be split across reads and one
 * read may carry several frames. The buffer grows geometrically and keeps only
 * the incomplete tail; mirroring `bridge/src/frames.ts` keeps the behaviour
 * identical on both ends.
 */
class DictationFrameParser {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var length = 0

    fun push(chunk: ByteArray, offset: Int = 0, count: Int = chunk.size): List<DictationProtocol.Frame> {
        if (count <= 0) return emptyList()
        ensure(length + count)
        System.arraycopy(chunk, offset, buffer, length, count)
        length += count

        val frames = ArrayList<DictationProtocol.Frame>()
        var cursor = 0
        while (length - cursor >= DictationProtocol.HEADER_BYTES) {
            val header = ByteBuffer.wrap(buffer, cursor, DictationProtocol.HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
            val type = header.get().toInt() and 0xFF
            val size = header.int
            if (size < 0 || size > DictationProtocol.MAX_FRAME_BYTES) {
                throw IllegalArgumentException("invalid frame length: $size")
            }
            if (length - cursor < DictationProtocol.HEADER_BYTES + size) break
            val start = cursor + DictationProtocol.HEADER_BYTES
            frames.add(
                DictationProtocol.Frame(
                    type = type,
                    payload = buffer.copyOfRange(start, start + size)
                )
            )
            cursor = start + size
        }

        if (cursor > 0) {
            System.arraycopy(buffer, cursor, buffer, 0, length - cursor)
            length -= cursor
        }
        return frames
    }

    private fun ensure(capacity: Int) {
        if (capacity <= buffer.size) return
        var next = maxOf(INITIAL_CAPACITY, buffer.size * 2)
        while (next < capacity) next *= 2
        buffer = buffer.copyOf(next)
    }

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
    }
}
