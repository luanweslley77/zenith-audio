package com.zenith.audio.ime

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One dictation session over TCP.
 *
 * The client owns three things the IME must not: the socket, the bounded
 * send queue and the framing. Audio frames arrive on the recorder thread and
 * are only queued there, never written to the socket, so a slow network can
 * never stall capture. The writer aggregates 10 ms recorder frames into
 * 100 ms AUDIO frames, as the protocol specifies.
 *
 * The address is decided by the app (the endpoint the streaming service is
 * currently using); this client only opens that one connection.
 *
 * Callbacks are normally invoked on the I/O thread; `network-slow` is
 * reported from the recorder thread that filled the queue. The service
 * marshals every callback to the main thread.
 */
class DictationClient(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val listener: Listener,
    private val live: Boolean = true,
    private val connectTimeoutMs: Int = 5_000,
    private val resultTimeoutMs: Int = 120_000
) {
    interface Listener {
        fun onConnected()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(code: String, message: String)
    }

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_FRAMES)
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ runSession() }, "ZenithDictationIo").apply { start() }
    }

    /**
     * Queues one PCM16 frame. Never blocks: when the queue is full the
     * session aborts instead of silently dropping words.
     */
    fun enqueue(frame: ByteArray) {
        if (!running.get() || cancelled.get()) return
        if (!queue.offer(frame)) {
            fail("network-slow", "the send queue is full")
        }
    }

    /** No more audio will arrive; flush, send END and wait for the reply. */
    fun finish() {
        finishing.set(true)
    }

    /** Abort without END: the bridge discards the audio. */
    fun cancel() {
        cancelled.set(true)
        running.set(false)
        closeSocket()
        writerThread?.interrupt()
    }

    private fun runSession() {
        val sock = Socket()
        socket = sock
        try {
            sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
            sock.tcpNoDelay = true
            sock.soTimeout = resultTimeoutMs

            val output = sock.getOutputStream()
            output.write(DictationProtocol.encodeStart(token, live))
            output.flush()

            if (cancelled.get()) return
            listener.onConnected()

            startWriter(sock)
            readReplies(sock)
        } catch (e: SocketTimeoutException) {
            fail("timeout", "no reply from the bridge in time")
        } catch (e: IOException) {
            fail("connection", e.message ?: "network error")
        } catch (e: Exception) {
            Log.w(TAG, "Dictation session failed", e)
            fail("internal", e.message ?: "internal error")
        } finally {
            running.set(false)
            closeSocket()
            writerThread?.interrupt()
        }
    }

    private fun startWriter(sock: Socket) {
        writerThread = Thread({
            val chunk = ByteArray(DictationProtocol.AUDIO_CHUNK_BYTES)
            var used = 0
            try {
                val output = sock.getOutputStream()
                while (true) {
                    val frame = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                    if (frame == null) {
                        if (cancelled.get()) return@Thread
                        if (finishing.get() && queue.isEmpty()) break
                        continue
                    }
                    if (cancelled.get()) return@Thread

                    var offset = 0
                    while (offset < frame.size) {
                        val copied = minOf(frame.size - offset, chunk.size - used)
                        System.arraycopy(frame, offset, chunk, used, copied)
                        used += copied
                        offset += copied
                        if (used == chunk.size) {
                            output.write(DictationProtocol.encodeAudio(chunk, 0, used))
                            used = 0
                        }
                    }
                }

                if (used > 0) {
                    output.write(DictationProtocol.encodeAudio(chunk, 0, used))
                }
                output.write(DictationProtocol.encodeEnd())
                output.flush()
                sock.shutdownOutput()
            } catch (_: InterruptedException) {
                // cancel() interrupts the writer.
            } catch (e: IOException) {
                fail("connection", e.message ?: "network error")
            }
        }, "ZenithDictationTx").apply { start() }
    }

    private fun readReplies(sock: Socket) {
        val input = sock.getInputStream()
        val parser = DictationFrameParser()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val frames = try {
                parser.push(buffer, 0, read)
            } catch (e: IllegalArgumentException) {
                fail("bad-frame", e.message ?: "malformed frame from the bridge")
                return
            }
            for (frame in frames) {
                try {
                    when (frame.type) {
                        DictationProtocol.FrameType.PARTIAL -> {
                            listener.onPartial(DictationProtocol.parsePartial(frame.payload).text)
                        }
                        DictationProtocol.FrameType.RESULT -> {
                            val result = DictationProtocol.parseResult(frame.payload)
                            if (reported.compareAndSet(false, true)) {
                                listener.onResult(result.text)
                            }
                            running.set(false)
                            return
                        }
                        DictationProtocol.FrameType.ERROR -> {
                            val failure = DictationProtocol.parseFailure(frame.payload)
                            if (reported.compareAndSet(false, true)) {
                                listener.onError(failure.code, failure.message)
                            }
                            running.set(false)
                            return
                        }
                        else -> Log.w(TAG, "Ignoring frame type 0x${frame.type.toString(16)}")
                    }
                } catch (e: Exception) {
                    // A malformed preview must not abort a dictation that is
                    // still producing usable audio.
                    Log.w(TAG, "Ignoring malformed frame type 0x${frame.type.toString(16)}", e)
                }
            }
        }
        if (!cancelled.get() && !reported.get()) {
            fail("connection", "the bridge closed the connection without a result")
        }
    }

    private fun fail(code: String, message: String) {
        if (!reported.compareAndSet(false, true)) return
        running.set(false)
        closeSocket()
        writerThread?.interrupt()
        if (cancelled.get()) return
        listener.onError(code, message)
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val TAG = "DictationClient"
        const val QUEUE_FRAMES = 500 // 5 s of 10 ms frames
        const val POLL_MS = 25L
    }
}
