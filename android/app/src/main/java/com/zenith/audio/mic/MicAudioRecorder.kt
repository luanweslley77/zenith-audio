package com.zenith.audio.mic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.zenith.audio.protocol.ZapProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

enum class MicSourceMode {
    UNPROCESSED,
    VOICE_RECOGNITION,
    MIC,
    VOICE_CALL;

    companion object {
        fun fromStored(value: String?): MicSourceMode = when (value) {
            "RAW", "UNPROCESSED" -> UNPROCESSED
            "VOICE", "VOICE_RECOGNITION" -> VOICE_RECOGNITION
            "MIC" -> MIC
            "CALL", "VOICE_CALL" -> VOICE_CALL
            else -> UNPROCESSED
        }
    }
}

data class MicSettings(
    val sourceMode: MicSourceMode = MicSourceMode.UNPROCESSED,
    val inputDeviceId: Int = DEFAULT_INPUT_DEVICE,
    val gainDb: Float = DEFAULT_GAIN_DB,
    val noiseSuppressionPercent: Int = DEFAULT_NOISE_SUPPRESSION_PERCENT,
    val inputDeviceAddress: String = ""
) {
    companion object {
        const val DEFAULT_INPUT_DEVICE = -1
        const val DEFAULT_GAIN_DB = 0f
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 24f
        const val DEFAULT_NOISE_SUPPRESSION_PERCENT = 0
        const val MIN_NOISE_SUPPRESSION_PERCENT = 0
        const val MAX_NOISE_SUPPRESSION_PERCENT = 100
    }
}

data class MicInputDevice(
    val id: Int,
    val name: String,
    val details: String,
    val address: String,
    val type: Int,
    val isRecommended: Boolean,
    val isVirtual: Boolean
)

class MicAudioRecorder(
    private val context: Context,
    private val sendAudioPacket: (ByteArray) -> Unit,
    private val sendControlPacket: (ByteArray) -> Unit,
    /**
     * When false, [sendAudioPacket] receives the raw PCM16 frame instead of a
     * ZAP packet, and no ZAP control packets are sent. The dictation IME uses
     * this: its transport is TCP and ZAP's framing/controls do not apply.
     */
    private val packetize: Boolean = true
) {
    companion object {
        private const val TAG = "MicAudioRecorder"
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SAMPLES = 480 // 10 ms at 48 kHz
        private const val BYTES_PER_SAMPLE = 2
        private const val FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE // 960 bytes
        private const val LEVEL_WINDOW_SAMPLES = 4800 // ~100 ms
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _settings = MutableStateFlow(MicSettings())
    val settings: StateFlow<MicSettings> = _settings.asStateFlow()

    private val _routedDevice = MutableStateFlow("")
    val routedDevice: StateFlow<String> = _routedDevice.asStateFlow()

    private val _activeSource = MutableStateFlow("")
    val activeSource: StateFlow<String> = _activeSource.asStateFlow()

    private val running = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var gainDb: Float = MicSettings.DEFAULT_GAIN_DB

    @Synchronized
    fun applySettings(newSettings: MicSettings, restartIfRecording: Boolean = true) {
        val sanitized = newSettings.copy(
            gainDb = newSettings.gainDb.coerceIn(MicSettings.MIN_GAIN_DB, MicSettings.MAX_GAIN_DB),
            noiseSuppressionPercent = newSettings.noiseSuppressionPercent.coerceIn(
                MicSettings.MIN_NOISE_SUPPRESSION_PERCENT,
                MicSettings.MAX_NOISE_SUPPRESSION_PERCENT
            )
        )
        val previous = _settings.value
        _settings.value = sanitized
        gainDb = sanitized.gainDb

        if (packetize && previous.noiseSuppressionPercent != sanitized.noiseSuppressionPercent) {
            sendNoiseSuppression(sanitized.noiseSuppressionPercent)
        }

        val sourceChanged = previous.sourceMode != sanitized.sourceMode
        val deviceChanged = previous.inputDeviceId != sanitized.inputDeviceId ||
            previous.inputDeviceAddress != sanitized.inputDeviceAddress
        if (restartIfRecording && running.get() && (sourceChanged || deviceChanged)) {
            Log.i(TAG, "Mic settings changed; restarting capture")
            stop()
            start()
        }
    }

    fun getInputDevices(): List<MicInputDevice> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .asSequence()
                .filter { it.isSource }
                .filterNot { isNonMicrophoneRoute(it) }
                .map { device ->
                    val typeLabel = deviceTypeLabel(device)
                    val address = safeAddress(device)
                    val product = safeProductName(device)
                    val recommended = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                        address.contains("bottom", ignoreCase = true)
                    val virtual = device.type == AudioDeviceInfo.TYPE_TELEPHONY
                    MicInputDevice(
                        id = device.id,
                        name = buildDeviceName(typeLabel, address, product),
                        details = buildDeviceDetails(device, product, virtual),
                        address = address,
                        type = device.type,
                        isRecommended = recommended,
                        isVirtual = virtual
                    )
                }
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<MicInputDevice> { it.isRecommended }
                        .thenBy { it.isVirtual }
                        .thenBy { it.name }
                )
                .toList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate input devices", e)
            emptyList()
        }
    }



    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return false
        }

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        val bufSize = maxOf(minBufSize, FRAME_BYTES * 4)

        val preferredDevice = findPreferredInputDevice()
        val candidates = sourceCandidates()

        var record: AudioRecord? = null
        var usedSource: Int = 0
        for (source in candidates) {
            val candidate = tryCreateRecord(source, bufSize) ?: continue
            if (preferredDevice != null) {
                val accepted = candidate.setPreferredDevice(preferredDevice)
                Log.i(TAG, "Preferred input '${deviceLabel(preferredDevice)}' (id=${preferredDevice.id}) accepted=$accepted")
            }
            record = candidate
            usedSource = source
            break
        }

        if (record == null) {
            Log.e(TAG, "AudioRecord failed to initialize for sources $candidates")
            return false
        }

        try {
            audioRecord = record
            record.startRecording()
            running.set(true)
            _isRecording.value = true

            if (preferredDevice != null) {
                _routedDevice.value = deviceLabel(preferredDevice)
            } else {
                _routedDevice.value = "System default"
            }
            _activeSource.value = sourceLabel(usedSource)

            // Send mic state ON and the current denoiser strength.
            if (packetize) {
                sendControlPacket(ZapProtocol.buildMicStatePacket(true))
                sendNoiseSuppression(_settings.value.noiseSuppressionPercent)
            }

            recordThread = Thread({ recordLoop() }, "ZenithMicRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.i(TAG, "Wireless microphone started: ${sourceLabel(usedSource)} | " +
                    "input=${_routedDevice.value} | gain=${gainDb}dB | 48kHz mono 10ms")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting mic recorder", e)
            stop()
            return false
        }
    }

    @Synchronized
    fun stop() {
        val wasRunning = running.getAndSet(false)

        _isRecording.value = false
        _micLevel.value = 0f
        _routedDevice.value = ""
        _activeSource.value = ""

        // Send mic state OFF control packet only if capture had really started.
        if (wasRunning && packetize) {
            try {
                sendControlPacket(ZapProtocol.buildMicStatePacket(false))
            } catch (_: Exception) {}
        }

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        if (wasRunning) {
            try {
                recordThread?.join(500)
            } catch (_: Exception) {}
        }
        recordThread = null

        // Release even after a failed start, otherwise the recorder handle leaks.
        audioRecord?.release()
        audioRecord = null

        if (wasRunning) {
            Log.i(TAG, "Wireless microphone recording stopped")
        }
    }

    private fun recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val record = audioRecord ?: return
        val buffer = ByteArray(FRAME_BYTES)
        val highPass = HighPassFilter(80.0, SAMPLE_RATE.toDouble())
        var seq = 0

        var sumSquares = 0.0
        var samplesCount = 0

        // routedDevice is sometimes only available after startRecording().
        try {
            val routed = record.routedDevice
            if (routed != null) {
                _routedDevice.value = deviceLabel(routed)
                Log.i(TAG, "Actual routed input: ${deviceLabel(routed)} (id=${routed.id})")
            }
        } catch (_: Exception) {}

        try {
            while (running.get()) {
                val bytesRead = try {
                    record.read(buffer, 0, FRAME_BYTES)
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.w(TAG, "AudioRecord read failed: ${e.message}")
                    }
                    break
                }

                if (bytesRead > 0) {
                    val gain = 10.0.pow(gainDb.toDouble() / 20.0).toFloat()

                    var i = 0
                    while (i < bytesRead - 1) {
                        val raw = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                        var sample = raw.toShort() / 32768f

                        sample = highPass.process(sample)
                        sample *= gain
                        sample = softLimit(sample)

                        val out = (sample * 32767f).toInt().coerceIn(-32768, 32767)
                        buffer[i] = (out and 0xFF).toByte()
                        buffer[i + 1] = ((out shr 8) and 0xFF).toByte()

                        sumSquares += out.toDouble() * out.toDouble()
                        samplesCount++
                        i += 2
                    }

                    if (samplesCount >= LEVEL_WINDOW_SAMPLES) {
                        val rms = sqrt(sumSquares / samplesCount)
                        _micLevel.value = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                        sumSquares = 0.0
                        samplesCount = 0
                    }

                    val isDiscontinuity = (seq == 0)
                    val frame = if (packetize) {
                        ZapProtocol.buildMicAudioPacket(
                            seq = seq,
                            audioData = buffer,
                            size = bytesRead,
                            isPcm = true,
                            isDiscontinuity = isDiscontinuity
                        )
                    } else {
                        buffer.copyOf(bytesRead)
                    }
                    seq++
                    sendAudioPacket(frame)
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioRecord read error: $bytesRead")
                    break
                }
            }
        } finally {
            // Unexpected exit (read error, callback exception, HAL death) must
            // not leave the UI and the PC believing the microphone is still on.
            val wasRunning = running.getAndSet(false)
            _isRecording.value = false
            _micLevel.value = 0f
            _routedDevice.value = ""
            _activeSource.value = ""
            if (wasRunning) {
                if (packetize) {
                    try {
                        sendControlPacket(ZapProtocol.buildMicStatePacket(false))
                    } catch (_: Exception) {}
                }
                try {
                    record.stop()
                } catch (_: Exception) {}
                audioRecord?.release()
                audioRecord = null
                Log.w(TAG, "Microphone capture loop ended unexpectedly")
            }
        }
    }

    private fun sendNoiseSuppression(percent: Int) {
        try {
            sendControlPacket(ZapProtocol.buildMicNoiseSuppressionPacket(percent))
        } catch (_: Exception) {}
    }

    private fun sourceCandidates(): List<Int> {
        val mode = _settings.value.sourceMode
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessedHint = try {
            audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        } catch (_: Exception) {
            null
        }
        Log.i(TAG, "Source mode=$mode | UNPROCESSED hint=${unprocessedHint ?: "unknown"}")

        // The selected source is tried first; the remaining entries are
        // fallbacks for devices whose HAL refuses a particular source.
        return when (mode) {
            MicSourceMode.UNPROCESSED -> listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )
            MicSourceMode.VOICE_RECOGNITION -> listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )
            MicSourceMode.MIC -> listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )
            MicSourceMode.VOICE_CALL -> listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC
            )
        }
    }

    @SuppressLint("MissingPermission") // start() checks RECORD_AUDIO before creating a recorder
    private fun tryCreateRecord(source: Int, bufferSize: Int): AudioRecord? {
        return try {
            val record = AudioRecord(
                source,
                SAMPLE_RATE,
                CHANNELS,
                ENCODING,
                bufferSize
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "AudioRecord initialized with ${sourceLabel(source)}")
                record
            } else {
                Log.w(TAG, "${sourceLabel(source)} unavailable (state=${record.state})")
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "${sourceLabel(source)} failed: ${e.message}")
            null
        }
    }

    private fun findPreferredInputDevice(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return null
        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.isSource && !isNonMicrophoneRoute(it) }
        } catch (_: Exception) {
            return null
        }

        // The id can change after a reboot. The physical address (bottom/back)
        // is stable, but several routes can share the same address, so keep
        // the specific id when it still agrees with the stored address.
        val requestedId = _settings.value.inputDeviceId
        val requestedAddress = _settings.value.inputDeviceAddress
        val idDevice = if (requestedId >= 0) {
            devices.firstOrNull { it.id == requestedId }
        } else {
            null
        }
        if (idDevice != null) {
            val idAddress = safeAddress(idDevice)
            if (requestedAddress.isBlank() ||
                idAddress.equals(requestedAddress, ignoreCase = true)
            ) {
                return idDevice
            }
        }
        if (requestedAddress.isNotBlank()) {
            devices.firstOrNull {
                safeAddress(it).equals(requestedAddress, ignoreCase = true)
            }?.let { return it }
        }
        if (idDevice != null && (requestedAddress.isBlank() ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.P)) {
            return idDevice
        }

        // Default to the phone's own primary (bottom) microphone. A
        // wired/Bluetooth headset mic is often narrowband or noisy, and the
        // communication route can silently switch to it when a headset is
        // connected.
        return devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                safeAddress(it).contains("bottom", ignoreCase = true)
        }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: devices.firstOrNull { safeAddress(it).contains("back", ignoreCase = true) }
            ?: devices.firstOrNull()
    }

    private fun sourceLabel(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED (rawest)"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE (recognition)"
        MediaRecorder.AudioSource.MIC -> "MIC (OEM)"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "CALL (processed)"
        else -> "SOURCE $source"
    }

    private fun deviceLabel(device: AudioDeviceInfo): String =
        buildDeviceName(deviceTypeLabel(device), safeAddress(device), safeProductName(device))

    private fun deviceTypeLabel(device: AudioDeviceInfo): String = when {
        // AudioDeviceInfo does not expose a public BACK_MIC type; the back
        // microphone is reported as a built-in mic with address "back".
        safeAddress(device).contains("back", ignoreCase = true) -> "Back mic"
        device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        device.type == AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line in"
        device.type == AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital in"
        device.type == AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        device.type == AudioDeviceInfo.TYPE_DOCK -> "Dock"
        device.type == AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            device.type == AudioDeviceInfo.TYPE_IP -> "IP input"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            device.type == AudioDeviceInfo.TYPE_BUS -> "Bus input"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE speaker"
        else -> "Input"
    }

    private fun safeAddress(device: AudioDeviceInfo): String {
        // AudioDeviceInfo.getAddress() was added in API 28. minSdk is 26, and
        // NoSuchMethodError is an Error (not an Exception), so guard the call
        // explicitly or the mic settings screen crashes on Android 8.x.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return ""
        val raw = try {
            device.address.trim()
        } catch (_: Exception) {
            ""
        }
        // Some virtual routes report "0" as their address; treat it as empty.
        return if (raw == "0") "" else raw
    }

    private fun safeProductName(device: AudioDeviceInfo): String = try {
        device.productName?.toString()?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun buildDeviceName(typeLabel: String, address: String, product: String): String {
        val suffix = when {
            address.isNotBlank() -> address
            product.isNotBlank() && !product.equals(typeLabel, ignoreCase = true) -> product
            else -> ""
        }
        return if (suffix.isNotBlank()) "$typeLabel · $suffix" else typeLabel
    }

    private fun buildDeviceDetails(
        device: AudioDeviceInfo,
        product: String,
        virtual: Boolean
    ): String {
        val parts = mutableListOf<String>()
        parts.add("id ${device.id}")

        val maxRate = try {
            device.sampleRates.maxOrNull()
        } catch (_: Exception) {
            null
        }
        if (maxRate != null && maxRate > 0) {
            parts.add("${maxRate / 1000} kHz")
        }

        val maxChannels = try {
            device.channelCounts.maxOrNull()
        } catch (_: Exception) {
            null
        }
        if (maxChannels != null && maxChannels > 0) {
            parts.add(if (maxChannels == 1) "mono" else "$maxChannels ch")
        }

        if (product.isNotBlank()) parts.add(product)
        if (virtual) parts.add("virtual route")
        return parts.joinToString(" · ")
    }

    private fun isNonMicrophoneRoute(device: AudioDeviceInfo): Boolean {
        val address = safeAddress(device)
        if (address.contains("echo", ignoreCase = true) ||
            address.contains("reference", ignoreCase = true)
        ) {
            return true
        }
        if (device.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) return true
        if (device.type == AudioDeviceInfo.TYPE_FM_TUNER) return true
        if (device.type == AudioDeviceInfo.TYPE_TV_TUNER) return true
        return false
    }

    private fun softLimit(x: Float): Float {
        val ax = abs(x)
        if (ax <= 0.9f) return x
        val over = (ax - 0.9f) / 0.1f
        val shaped = 0.9f + 0.1f * tanh(over)
        return if (x < 0f) -shaped else shaped
    }

    private class HighPassFilter(cutoffHz: Double, sampleRate: Double, q: Double = 0.707) {
        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a1: Float
        private val a2: Float

        private var z1 = 0f
        private var z2 = 0f

        init {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha

            b0 = ((1.0 + cosW0) / 2.0 / a0).toFloat()
            b1 = (-(1.0 + cosW0) / a0).toFloat()
            b2 = ((1.0 + cosW0) / 2.0 / a0).toFloat()
            a1 = (-2.0 * cosW0 / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun process(x: Float): Float {
            // Direct Form II Transposed.
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }
}
