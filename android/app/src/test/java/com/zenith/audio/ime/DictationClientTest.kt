package com.zenith.audio.ime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Exercises the real socket path against a fake bridge: START, aggregated
 * 100 ms AUDIO frames, END and the PARTIAL/RESULT reply flow.
 */
class DictationClientTest {

    @Test
    fun `streams aggregated audio and delivers partial and result`() {
        val server = ServerSocket(0)
        val audioBytes = AtomicInteger(0)
        val startPayload = AtomicReference<JSONObject?>()
        val serverFailure = AtomicReference<Throwable?>()

        val serverThread = thread(name = "fake-bridge") {
            try {
                server.accept().use { socket ->
                    val parser = DictationFrameParser()
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        for (frame in parser.push(buffer, 0, read)) {
                            when (frame.type) {
                                DictationProtocol.FrameType.START -> {
                                    startPayload.set(
                                        JSONObject(String(frame.payload, Charsets.UTF_8))
                                    )
                                }
                                DictationProtocol.FrameType.AUDIO -> {
                                    audioBytes.addAndGet(frame.payload.size)
                                }
                                DictationProtocol.FrameType.END -> {
                                    output.write(
                                        DictationProtocol.encodeJson(
                                            DictationProtocol.FrameType.PARTIAL,
                                            JSONObject().put("version", 1).put("text", "parcial")
                                        )
                                    )
                                    output.write(
                                        DictationProtocol.encodeJson(
                                            DictationProtocol.FrameType.RESULT,
                                            JSONObject()
                                                .put("version", 1)
                                                .put("text", "texto final")
                                                .put("livePasses", 1)
                                        )
                                    )
                                    output.flush()
                                }
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }

        val connected = CountDownLatch(1)
        val result = CountDownLatch(1)
        val partial = AtomicReference<String?>()
        val finalText = AtomicReference<String?>()
        val error = AtomicReference<String?>()

        val client = DictationClient(
            host = "127.0.0.1",
            port = server.localPort,
            token = "segredo",
            listener = object : DictationClient.Listener {
                override fun onConnected() {
                    connected.countDown()
                }

                override fun onPartial(text: String) {
                    partial.set(text)
                }

                override fun onResult(text: String) {
                    finalText.set(text)
                    result.countDown()
                }

                override fun onError(code: String, message: String) {
                    error.set(code)
                    result.countDown()
                }
            }
        )

        try {
            client.start()
            assertTrue("client did not connect", connected.await(5, TimeUnit.SECONDS))

            // Ten 10 ms frames must become one 100 ms AUDIO frame.
            repeat(10) { client.enqueue(ByteArray(960)) }
            client.finish()

            assertTrue("no reply from the fake bridge", result.await(5, TimeUnit.SECONDS))
        } finally {
            client.cancel()
            server.close()
        }

        serverThread.join(5_000)
        assertEquals(null, serverFailure.get())
        assertEquals(null, error.get())
        assertEquals("parcial", partial.get())
        assertEquals("texto final", finalText.get())
        assertEquals(9_600, audioBytes.get())

        val start = startPayload.get() ?: throw AssertionError("no START frame")
        assertEquals(1, start.getInt("version"))
        assertEquals(48_000, start.getInt("sampleRate"))
        assertEquals(1, start.getInt("channels"))
        assertEquals("s16le", start.getString("format"))
        assertEquals("segredo", start.getString("token"))
        assertTrue(start.getBoolean("live"))
    }

    @Test
    fun `error frame is surfaced to the listener`() {
        val server = ServerSocket(0)
        val serverThread = thread(name = "fake-bridge-error") {
            server.accept().use { socket ->
                val parser = DictationFrameParser()
                val input = socket.getInputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (parser.push(buffer, 0, read).any {
                            it.type == DictationProtocol.FrameType.END
                        }
                    ) {
                        socket.getOutputStream().write(
                            DictationProtocol.encodeJson(
                                DictationProtocol.FrameType.ERROR,
                                JSONObject()
                                    .put("version", 1)
                                    .put("code", "model-busy")
                                    .put("message", "another dictation is active")
                            )
                        )
                        socket.getOutputStream().flush()
                        break
                    }
                }
            }
        }

        val connected = CountDownLatch(1)
        val done = CountDownLatch(1)
        val errorCode = AtomicReference<String?>()

        val client = DictationClient(
            host = "127.0.0.1",
            port = server.localPort,
            token = "",
            listener = object : DictationClient.Listener {
                override fun onConnected() {
                    connected.countDown()
                }

                override fun onPartial(text: String) = Unit

                override fun onResult(text: String) {
                    done.countDown()
                }

                override fun onError(code: String, message: String) {
                    errorCode.set(code)
                    done.countDown()
                }
            }
        )

        try {
            client.start()
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            client.finish()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally {
            client.cancel()
            server.close()
        }
        serverThread.join(5_000)

        assertEquals("model-busy", errorCode.get())
    }
}
