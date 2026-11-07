package com.zenith.audio.network

import android.content.Context
import android.os.Process
import android.util.Log
import com.zenith.audio.audio.LowLatencyAudioPlayer
import com.zenith.audio.codec.OpusMediaCodecDecoder
import com.zenith.audio.jitter.AdaptiveJitterBuffer
import com.zenith.audio.model.ConnectionState
import com.zenith.audio.model.StreamMetrics
import com.zenith.audio.protocol.ZapProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UdpAudioReceiver(private val context: Context) {
    companion object {
        private const val TAG = "UdpReceiver"
        private const val SOCKET_TIMEOUT_MS = 2000
    }

    private val netIoExecutor = Executors.newSingleThreadExecutor()

    private val _metrics = MutableStateFlow(StreamMetrics())
    val metrics: StateFlow<StreamMetrics> = _metrics.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private var rxThread: Thread? = null
    private var playThread: Thread? = null
    private var telemetryThread: Thread? = null

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = ZapProtocol.DEFAULT_PORT

    private val jitterBuffer = AdaptiveJitterBuffer()
    private val opusDecoder = OpusMediaCodecDecoder()
    private val audioPlayer = LowLatencyAudioPlayer(context)

    private var measuredRttMs: Double = 0.0
    private var currentBitrateKbps: Int = 320
    private var lastPacketTimeNs: Long = 0L

    fun start(
        serverIp: String,
        port: Int = ZapProtocol.DEFAULT_PORT,
        initialBitrateKbps: Int = 320,
        pcAudioEnabled: Boolean = true,
        phoneOnly: Boolean = false
    ): Boolean {
        if (isRunning.get()) stop()

        currentBitrateKbps = initialBitrateKbps
        isPcAudioEnabled = pcAudioEnabled
        isPhoneOnly = phoneOnly
        serverPort = port

        try {
            serverAddress = InetAddress.getByName(serverIp)
            socket = DatagramSocket().apply {
                soTimeout = SOCKET_TIMEOUT_MS
                receiveBufferSize = 256 * 1024
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create socket or resolve host $serverIp", e)
            return false
        }

        jitterBuffer.reset()
        if (!opusDecoder.start()) {
            socket?.close()
            return false
        }
        if (!audioPlayer.start()) {
            opusDecoder.stop()
            socket?.close()
            return false
        }

        isRunning.set(true)
        lastPacketTimeNs = System.nanoTime()
        hasReceivedServerPacket = false
        lastReportedState = null

        currentTransportName = if (serverIp.startsWith("10.") || serverIp.startsWith("192.168.42.") || serverIp == "127.0.0.1") {
            "USB 0.5ms Direct"
        } else {
            "Wi-Fi"
        }

        _metrics.value = StreamMetrics(
            connectionState = ConnectionState.CONNECTING,
            serverIp = serverIp,
            bitrateKbps = initialBitrateKbps,
            transportType = currentTransportName
        )

        rxThread = Thread({ rxLoop() }, "Zenith-UDP-Rx").apply { start() }
        playThread = Thread({ playLoop() }, "Zenith-Audio-Play").apply { start() }
        telemetryThread = Thread({ telemetryLoop() }, "Zenith-Telemetry").apply { start() }

        return true
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            socket?.close()
        } catch (_: Exception) {}

        rxThread?.join(500)
        playThread?.join(500)
        telemetryThread?.join(500)

        opusDecoder.stop()
        audioPlayer.stop()

        _metrics.value = _metrics.value.copy(
            connectionState = ConnectionState.DISCONNECTED
        )
    }

    private var isAutoBitrate: Boolean = false
    private var stableCycles: Int = 0
    private var currentVolumePercent: Int = 100
    private var currentTransportName: String = "Wi-Fi"
    private var isCallDuckingActive: Boolean = false
    private var isAoaDacActive: Boolean = false
    private var isPcAudioEnabled: Boolean = true
    private var isPhoneOnly: Boolean = false
    private var hasReceivedServerPacket: Boolean = false
    private var lastReportedState: ConnectionState? = null

    fun setAutoBitrate(enabled: Boolean) {
        isAutoBitrate = enabled
        _metrics.value = _metrics.value.copy(isAutoBitrate = enabled)
    }

    val dspEngine: com.zenith.audio.dsp.DspEqualizerEngine
        get() = audioPlayer.dspEngine

    fun setGamingMode(enabled: Boolean) {
        jitterBuffer.setGamingMode(enabled)
    }

    fun setCallDuckingActive(active: Boolean) {
        isCallDuckingActive = active
        _metrics.value = _metrics.value.copy(isCallDuckingActive = active)
    }

    fun setAoaDacActive(active: Boolean) {
        isAoaDacActive = active
        _metrics.value = _metrics.value.copy(isAoaDacActive = active)
    }

    fun setTransportName(name: String) {
        currentTransportName = name
        _metrics.value = _metrics.value.copy(transportType = name)
    }

    fun setMasterVolume(volumePercent: Int) {
        currentVolumePercent = volumePercent.coerceIn(0, 100)
        val packet = ZapProtocol.buildVolumeControlPacket(currentVolumePercent)
        sendRaw(packet)
        _metrics.value = _metrics.value.copy(volumePercent = currentVolumePercent)
    }

    fun getCurrentVolume(): Int = currentVolumePercent

    /**
     * Enables or disables the PC system-audio stream for this client.
     * Disabling it keeps the microphone path and the connection alive.
     */
    fun setPcAudioEnabled(enabled: Boolean) {
        isPcAudioEnabled = enabled
        jitterBuffer.reset()
        sendRaw(
            ZapProtocol.buildControlPacket(
                if (enabled) ZapProtocol.CMD_RESUME else ZapProtocol.CMD_PAUSE,
                0
            )
        )
    }

    /**
     * Mutes or unmutes the PC's default output sink. The server captures the
     * sink monitor, which remains available while the speakers are muted, so
     * the phone can be the only audible output.
     */
    fun setPhoneOnly(enabled: Boolean) {
        isPhoneOnly = enabled
        sendRaw(
            ZapProtocol.buildControlPacket(
                ZapProtocol.CMD_SET_PC_MUTE,
                0,
                if (enabled) 1 else 0
            )
        )
    }

    private fun resendControlState() {
        sendRaw(
            ZapProtocol.buildControlPacket(
                if (isPcAudioEnabled) ZapProtocol.CMD_RESUME else ZapProtocol.CMD_PAUSE,
                0
            )
        )
        // Only claim the PC mute state when phone-only mode is active. Sending
        // an unmute on every reconnect would override a mute the user applied
        // manually on the PC.
        if (isPhoneOnly) {
            sendRaw(
                ZapProtocol.buildControlPacket(
                    ZapProtocol.CMD_SET_PC_MUTE,
                    0,
                    1
                )
            )
        }
    }

    fun switchEndpoint(newIp: String, newPort: Int = ZapProtocol.DEFAULT_PORT, transportName: String = "Wi-Fi") {
        if (newIp.isBlank()) return
        currentTransportName = transportName
        netIoExecutor.execute {
            try {
                val newAddr = InetAddress.getByName(newIp)
                serverAddress = newAddr
                serverPort = newPort
                Log.i(TAG, "[Failover] Zero-drop hot-switched active endpoint to $newIp:$newPort ($transportName)")
                sendPing()
                _metrics.value = _metrics.value.copy(
                    serverIp = newIp,
                    transportType = transportName
                )
            } catch (e: Exception) {
                Log.e(TAG, "[Failover] Failed to switch endpoint to $newIp", e)
            }
        }
    }

    fun setBitrate(bitrateKbps: Int) {
        currentBitrateKbps = bitrateKbps
        val targetBps = bitrateKbps * 1000L
        val packet = ZapProtocol.buildControlPacket(ZapProtocol.CMD_SET_BITRATE, targetBps)
        sendRaw(packet)
        _metrics.value = _metrics.value.copy(bitrateKbps = bitrateKbps)
    }

    private fun sendPing() {
        val nowUs = System.nanoTime() / 1000L
        val pingBytes = ZapProtocol.buildPingPacket(nowUs)
        sendRaw(pingBytes)
    }

    private fun sendFeedback() {
        val totalRcv = jitterBuffer.getPacketsReceived()
        val totalLost = jitterBuffer.getPacketsLost()
        val lossFraction = if (totalRcv + totalLost > 0) {
            ((totalLost * 10000) / (totalRcv + totalLost)).toInt()
        } else 0

        val fbBytes = ZapProtocol.buildFeedbackPacket(
            lastSeqReceived = jitterBuffer.getNextExpectedSeq(),
            totalPacketsReceived = totalRcv,
            totalPacketsLost = totalLost,
            lossFractionPercent = lossFraction,
            jitterUs = (jitterBuffer.getJitterMs() * 1000.0).toInt(),
            bufferDelayMs = jitterBuffer.getCurrentBufferDelayMs().toInt(),
            rttMs = measuredRttMs.toInt()
        )
        sendRaw(fbBytes)
    }

    fun sendRaw(data: ByteArray) {
        val s = socket ?: return
        val addr = serverAddress ?: return
        netIoExecutor.execute {
            try {
                val dgram = DatagramPacket(data, data.size, addr, serverPort)
                s.send(dgram)
            } catch (e: Exception) {
                Log.e(TAG, "sendRaw failed to $addr:$serverPort", e)
            }
        }
    }

    private fun rxLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rxBuffer = ByteArray(2048)
        val packet = DatagramPacket(rxBuffer, rxBuffer.size)

        // Send initial ping from background thread
        sendPing()
        Log.i(TAG, "Sent initial PING to $serverAddress:$serverPort")

        while (isRunning.get()) {
            try {
                val s = socket ?: break
                packet.length = rxBuffer.size // Ensure buffer length is not truncated by previous small packet
                s.receive(packet)

                lastPacketTimeNs = System.nanoTime()
                val receiveTimeUs = lastPacketTimeNs / 1000L

                val byteBuffer = ByteBuffer.wrap(packet.data, packet.offset, packet.length)
                val header = ZapProtocol.parseHeader(byteBuffer) ?: continue
                hasReceivedServerPacket = true

                when (header.type) {
                    ZapProtocol.PKT_AUDIO_FRAME -> {
                        if (!isPcAudioEnabled) continue
                        val payloadOffset = packet.offset + ZapProtocol.HEADER_SIZE
                        val payloadSize = header.payloadSize
                        if (payloadSize > 0 && payloadOffset + payloadSize <= packet.offset + packet.length) {
                            val pooledPacket = jitterBuffer.obtainPacket(
                                header,
                                packet.data,
                                payloadOffset,
                                payloadSize,
                                receiveTimeUs
                            )
                            jitterBuffer.pushPacket(pooledPacket)
                        }
                    }

                    ZapProtocol.PKT_PONG -> {
                        if (packet.length >= ZapProtocol.HEADER_SIZE + 16) {
                            val pldBuf = ByteBuffer.wrap(packet.data, packet.offset + ZapProtocol.HEADER_SIZE, 16)
                                .order(ByteOrder.BIG_ENDIAN)
                            val clientSendTimeUs = pldBuf.long
                            val roundTripUs = receiveTimeUs - clientSendTimeUs
                            if (roundTripUs in 1..2_000_000) {
                                measuredRttMs = measuredRttMs * 0.8 + (roundTripUs / 1000.0) * 0.2
                            }
                        }
                    }

                    ZapProtocol.PKT_SERVER_ANNOUNCE -> {
                        if (packet.length >= ZapProtocol.HEADER_SIZE + 20) {
                            val pldBuf = ByteBuffer.wrap(packet.data, packet.offset + ZapProtocol.HEADER_SIZE, 20)
                                .order(ByteOrder.BIG_ENDIAN)
                            pldBuf.int // sample rate
                            pldBuf.short // channels
                            pldBuf.short // frame samples
                            val bitrate = pldBuf.int
                            currentBitrateKbps = bitrate / 1000
                        }
                    }
                }
            } catch (e: Exception) {
                // Socket timeout or read error
                if (!isRunning.get()) break
            }
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        while (isRunning.get()) {
            val popResult = jitterBuffer.popNextPacket()

            when (popResult) {
                is AdaptiveJitterBuffer.PopResult.Packet -> {
                    val pkt = popResult.audioPacket
                    opusDecoder.decode(
                        pkt.payload,
                        0,
                        pkt.payloadSize,
                        pkt.header.timestampUs
                    ) { pcmBuffer, pcmSize ->
                        audioPlayer.write(pcmBuffer, pcmSize)
                    }
                    jitterBuffer.recyclePacket(pkt)

                    // NetEQ WSOLA Clock Sync: micro pitch-preserving time stretching (±2%)
                    val curDelay = jitterBuffer.getCurrentBufferDelayMs()
                    val targetDelay = jitterBuffer.getTargetBufferDelayMs()
                    if (curDelay > targetDelay + 15.0) {
                        audioPlayer.setPlaybackSpeed(1.02f)
                    } else if (curDelay < targetDelay - 8.0) {
                        audioPlayer.setPlaybackSpeed(0.98f)
                    } else {
                        audioPlayer.setPlaybackSpeed(1.00f)
                    }
                }

                is AdaptiveJitterBuffer.PopResult.LostPacket -> {
                    // Conceal lost packet with 10ms of silence in AudioTrack to prevent underruns
                    audioPlayer.writeSilence(10)
                }

                is AdaptiveJitterBuffer.PopResult.WaitingForReorder -> {
                    try {
                        Thread.sleep(1)
                    } catch (_: Exception) {}
                }

                is AdaptiveJitterBuffer.PopResult.Empty -> {
                    try {
                        Thread.sleep(2)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun telemetryLoop() {
        var pingCounter = 0

        while (isRunning.get()) {
            try {
                Thread.sleep(500) // 2Hz stats update & feedback
            } catch (_: Exception) {
                break
            }

            pingCounter++
            if (pingCounter % 4 == 0) { // Every 1 second
                sendPing()
            }
            sendFeedback()

            val nowNs = System.nanoTime()
            val timeSinceLastPacketMs = (nowNs - lastPacketTimeNs) / 1_000_000.0

            val connectionState = when {
                timeSinceLastPacketMs > 3000.0 -> {
                    // Watchdog: attempt auto-reconnect ping
                    sendPing()
                    ConnectionState.RECONNECTING
                }
                // PONG/announce traffic keeps the link alive even when the
                // client is in mic-only mode and no audio frames are arriving.
                hasReceivedServerPacket -> ConnectionState.CONNECTED
                else -> ConnectionState.CONNECTING
            }

            if (connectionState == ConnectionState.CONNECTED &&
                lastReportedState != ConnectionState.CONNECTED
            ) {
                // Re-apply the routing state after a reconnect or a server restart.
                resendControlState()
            }
            lastReportedState = connectionState

            val rtt = measuredRttMs
            val jitter = jitterBuffer.getJitterMs()
            val jbufDelay = jitterBuffer.getCurrentBufferDelayMs()
            val playDelay = audioPlayer.getPlaybackLatencyMs()
            val totalEstLatency = (rtt / 2.0) + jbufDelay + playDelay

            val totalRcv = jitterBuffer.getPacketsReceived()
            val totalLost = jitterBuffer.getPacketsLost()
            val lossPct = if (totalRcv + totalLost > 0) {
                (totalLost.toDouble() / (totalRcv + totalLost).toDouble()) * 100.0
            } else 0.0

            // Dynamic Congestion Control (Smart Auto-Pilot Bitrate)
            if (isAutoBitrate && isPcAudioEnabled && connectionState == ConnectionState.CONNECTED) {
                if (lossPct > 1.2 || rtt > 50.0) {
                    val nextBitrate = when (currentBitrateKbps) {
                        320 -> 256
                        256 -> 192
                        192 -> 128
                        128 -> 64
                        else -> 64
                    }
                    if (nextBitrate != currentBitrateKbps) {
                        setBitrate(nextBitrate)
                        stableCycles = 0
                        Log.i(TAG, "[AutoBitrate] Congestion detected (loss=${lossPct}%, rtt=${rtt}ms) -> Stepped down to ${nextBitrate}k")
                    }
                } else if (lossPct < 0.2 && rtt < 25.0) {
                    stableCycles++
                    if (stableCycles >= 4) { // Clean channel for 2 seconds
                        val nextBitrate = when (currentBitrateKbps) {
                            64 -> 128
                            128 -> 192
                            192 -> 256
                            256 -> 320
                            else -> 320
                        }
                        if (nextBitrate != currentBitrateKbps) {
                            setBitrate(nextBitrate)
                            stableCycles = 0
                            Log.i(TAG, "[AutoBitrate] Link clean -> Stepped up to ${nextBitrate}k")
                        }
                    }
                } else {
                    stableCycles = 0
                }
            }

            val (devName, isBt) = audioPlayer.getOutputDeviceInfo()

            _metrics.value = StreamMetrics(
                connectionState = connectionState,
                serverIp = serverAddress?.hostAddress ?: "",
                bitrateKbps = currentBitrateKbps,
                sampleRate = 48000,
                channels = "Stereo",
                networkRttMs = rtt,
                jitterMs = jitter,
                jitterBufferMs = jbufDelay,
                playbackLatencyMs = playDelay,
                totalEstimatedLatencyMs = totalEstLatency,
                outputDeviceName = devName,
                isBluetooth = isBt,
                packetLossPercent = lossPct,
                packetsReceived = totalRcv,
                packetsLost = totalLost,
                audioUnderruns = audioPlayer.getUnderruns(),
                isAutoBitrate = isAutoBitrate,
                volumePercent = currentVolumePercent,
                transportType = currentTransportName,
                isCallDuckingActive = isCallDuckingActive,
                isAoaDacActive = isAoaDacActive
            )
        }
    }
}
