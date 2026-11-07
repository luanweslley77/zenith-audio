package com.zenith.audio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zenith.audio.MainActivity
import com.zenith.audio.mic.MicInputDevice
import com.zenith.audio.mic.MicSettings
import com.zenith.audio.mic.MicSourceMode
import com.zenith.audio.model.ConnectionState
import com.zenith.audio.model.StreamMetrics
import com.zenith.audio.network.UdpAudioReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AudioReceiverService : Service() {
    companion object {
        const val ACTION_START = "com.zenith.audio.START"
        const val ACTION_STOP = "com.zenith.audio.STOP"
        const val EXTRA_SERVER_IP = "EXTRA_SERVER_IP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_BITRATE = "EXTRA_BITRATE"

        private const val CHANNEL_ID = "zenith_audio_stream_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AudioReceiverService"
    }

    private val binder = LocalBinder()
    private var receiver: UdpAudioReceiver? = null
    private var micRecorder: com.zenith.audio.mic.MicAudioRecorder? = null
    private lateinit var serverDiscovery: com.zenith.audio.network.ServerDiscovery
    private var callDuckingManager: com.zenith.audio.telephony.CallDuckingManager? = null
    private var failoverManager: com.zenith.audio.network.NetworkFailoverManager? = null
    private var aoaDacManager: com.zenith.audio.usb.AoaDacManager? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var metricsCollectorJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioReceiverService = this@AudioReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val startupMicSettings = readMicSettings()
        receiver = UdpAudioReceiver(this)
        micRecorder = com.zenith.audio.mic.MicAudioRecorder(
            context = this,
            sendAudioPacket = { data -> receiver?.sendMicAudio(data) },
            sendControlPacket = { data -> receiver?.sendRaw(data) }
        ).apply {
            applySettings(startupMicSettings, restartIfRecording = false)
        }
        // Keep the receiver's remembered denoiser value so it can be replayed
        // with the rest of the control state after a server restart.
        receiver?.setMicNoiseSuppression(startupMicSettings.noiseSuppressionPercent)
        serverDiscovery = com.zenith.audio.network.ServerDiscovery(this)
        serverDiscovery.startDiscovery()

        callDuckingManager = com.zenith.audio.telephony.CallDuckingManager(
            context = this,
            onSetVolume = { duckVol ->
                receiver?.setMasterVolume(duckVol)
            },
            onDuckingStateChanged = { isDucking ->
                receiver?.setCallDuckingActive(isDucking)
            }
        ).apply { start() }

        val savedServerIp = getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
            .getString("server_ip", "192.168.1.9") ?: "192.168.1.9"

        failoverManager = com.zenith.audio.network.NetworkFailoverManager(this) { newTransport, targetIp ->
            receiver?.switchEndpoint(targetIp, transportName = newTransport)
        }.apply { start(savedServerIp) }

        serviceScope.launch {
            serverDiscovery.latestDiscoveredServer.collect { server ->
                if (server != null) {
                    failoverManager?.updateWifiServerIp(server.ip)
                }
            }
        }

        aoaDacManager = com.zenith.audio.usb.AoaDacManager(this) { isUsb, targetIp ->
            if (isUsb) {
                receiver?.setAoaDacActive(true)
                receiver?.switchEndpoint(targetIp.ifEmpty { "10.81.101.129" }, transportName = "USB AOA 2.0 DAC")
            } else {
                receiver?.setAoaDacActive(false)
                receiver?.switchEndpoint(targetIp.ifEmpty { "192.168.1.9" }, transportName = "Wi-Fi")
            }
        }.apply { start() }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZenithAudio::StreamWakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip = intent.getStringExtra(EXTRA_SERVER_IP) ?: "192.168.1.9"
                val port = intent.getIntExtra(EXTRA_PORT, com.zenith.audio.protocol.ZapProtocol.DEFAULT_PORT)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 320)

                startForeground(NOTIFICATION_ID, buildNotification("Connecting to $ip..."))
                startStreaming(ip, port, bitrate)
            }
            ACTION_STOP -> {
                stopStreaming()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun getMetricsFlow(): StateFlow<StreamMetrics>? = receiver?.metrics
    val discoveredServers: StateFlow<List<com.zenith.audio.protocol.ZapProtocol.DiscoveredServer>>
        get() = serverDiscovery.discoveredServers

    val isMicRecording: StateFlow<Boolean>? get() = micRecorder?.isRecording
    val micLevel: StateFlow<Float>? get() = micRecorder?.micLevel
    val micSettings: StateFlow<MicSettings>? get() = micRecorder?.settings
    val micRoutedDevice: StateFlow<String>? get() = micRecorder?.routedDevice
    val micActiveSource: StateFlow<String>? get() = micRecorder?.activeSource

    val isCallDuckingEnabled: StateFlow<Boolean>? get() = callDuckingManager?.isDuckingEnabled
    val isCallActive: StateFlow<Boolean>? get() = callDuckingManager?.isCallActive

    val isAoaEnabled: StateFlow<Boolean>? get() = aoaDacManager?.isAoaEnabled
    val isUsbConnected: StateFlow<Boolean>? get() = aoaDacManager?.isUsbConnected

    val activeDetectedIp: StateFlow<String>? get() = failoverManager?.activeDetectedIp
    val activeTransport: StateFlow<String>? get() = failoverManager?.activeTransport
    val dspEngine: com.zenith.audio.dsp.DspEqualizerEngine? get() = receiver?.dspEngine

    fun setBitrate(bitrateKbps: Int) {
        receiver?.setBitrate(bitrateKbps)
    }

    fun setAutoBitrate(enabled: Boolean) {
        receiver?.setAutoBitrate(enabled)
    }

    fun setGamingMode(enabled: Boolean) {
        receiver?.setGamingMode(enabled)
    }

    fun triggerFastDiscovery() {
        serverDiscovery.triggerFastScan()
    }

    fun setMasterVolume(volumePercent: Int) {
        callDuckingManager?.updateCurrentVolume(volumePercent)
        receiver?.setMasterVolume(volumePercent)
    }

    fun setPcAudioEnabled(enabled: Boolean) {
        receiver?.setPcAudioEnabled(enabled)
    }

    fun setPhoneOnly(enabled: Boolean) {
        receiver?.setPhoneOnly(enabled)
    }

    fun setCallDuckingEnabled(enabled: Boolean) {
        callDuckingManager?.setAutoDuckingEnabled(enabled)
    }

    fun setAoaModeEnabled(enabled: Boolean, fallbackWifiIp: String) {
        aoaDacManager?.setAoaModeEnabled(enabled, fallbackWifiIp)
    }

    fun toggleMic(enable: Boolean): Boolean {
        val mic = micRecorder ?: return false
        return if (enable) {
            mic.applySettings(readMicSettings(), restartIfRecording = false)
            mic.start()
        } else {
            mic.stop()
            true
        }
    }

    fun getMicSettings(): MicSettings = micRecorder?.settings?.value ?: readMicSettings()

    fun getMicInputDevices(): List<MicInputDevice> = micRecorder?.getInputDevices() ?: emptyList()

    fun applyMicSettings(settings: MicSettings) {
        persistMicSettings(settings)
        micRecorder?.applySettings(settings)
        receiver?.setMicNoiseSuppression(settings.noiseSuppressionPercent)
        Log.i(TAG, "Mic settings: source=${settings.sourceMode} input=${settings.inputDeviceId} " +
                "address=${settings.inputDeviceAddress} gain=${settings.gainDb}dB " +
                "ns=${settings.noiseSuppressionPercent}%")
    }

    private fun persistMicSettings(settings: MicSettings) {
        getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE).edit()
            .putString("mic_source_mode", settings.sourceMode.name)
            .putInt("mic_input_device_id", settings.inputDeviceId)
            .putString("mic_input_device_address", settings.inputDeviceAddress)
            .putFloat("mic_gain_db", settings.gainDb)
            .putInt("mic_ns_percent", settings.noiseSuppressionPercent)
            .apply()
    }

    private fun readMicSettings(): MicSettings {
        val prefs = getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
        return MicSettings(
            sourceMode = MicSourceMode.fromStored(prefs.getString("mic_source_mode", null)),
            inputDeviceId = prefs.getInt("mic_input_device_id", MicSettings.DEFAULT_INPUT_DEVICE),
            gainDb = prefs.getFloat("mic_gain_db", MicSettings.DEFAULT_GAIN_DB),
            noiseSuppressionPercent = prefs.getInt(
                "mic_ns_percent",
                MicSettings.DEFAULT_NOISE_SUPPRESSION_PERCENT
            ),
            inputDeviceAddress = prefs.getString("mic_input_device_address", "") ?: ""
        )
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        try {
            wifiLock = wifiManager.createWifiLock(mode, "ZenithAudio::StreamWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "High-performance Wi-Fi lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire Wi-Fi lock", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun startStreaming(ip: String, port: Int, bitrate: Int) {
        acquireWifiLock()
        failoverManager?.updateWifiServerIp(ip)
        failoverManager?.setStreamingActive(true)

        val prefs = getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
        val pcAudioEnabled = prefs.getBoolean("pc_audio_enabled", true)
        val phoneOnly = prefs.getBoolean("phone_only", false)
        val started = receiver?.start(ip, port, bitrate, pcAudioEnabled, phoneOnly) == true

        if (started) {
            // Send the current routing state to the server after the socket is up.
            receiver?.setPcAudioEnabled(pcAudioEnabled)
            if (phoneOnly) {
                // Do not send an unmute when phone-only is off: the PC may have
                // been muted manually and the app should not override that.
                receiver?.setPhoneOnly(true)
            }
        }

        metricsCollectorJob?.cancel()
        metricsCollectorJob = serviceScope.launch {
            var lastNotificationTime = 0L
            var lastState: ConnectionState? = null

            receiver?.metrics?.collect { metrics ->
                val now = System.currentTimeMillis()
                val stateChanged = metrics.connectionState != lastState
                val timePassed = (now - lastNotificationTime) >= 3000L

                if (stateChanged || timePassed) {
                    lastState = metrics.connectionState
                    lastNotificationTime = now

                    val text = when (metrics.connectionState) {
                        ConnectionState.CONNECTED -> "Streaming: ${metrics.bitrateKbps} kbps | Latency: ${metrics.totalEstimatedLatencyMs.toInt()}ms | ${metrics.outputDeviceName}"
                        ConnectionState.CONNECTING -> "Connecting to ${metrics.serverIp}..."
                        ConnectionState.RECONNECTING -> "Reconnecting to ${metrics.serverIp}..."
                        ConnectionState.DISCONNECTED -> "Disconnected"
                    }
                    val notification = buildNotification(text)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun stopStreaming() {
        metricsCollectorJob?.cancel()
        failoverManager?.setStreamingActive(false)
        micRecorder?.stop()

        // Do not leave the PC muted after the phone stops listening.
        val prefs = getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("phone_only", false)) {
            receiver?.setPhoneOnly(false)
        }

        receiver?.stop()
        releaseWifiLock()
    }

    override fun onDestroy() {
        stopStreaming()
        releaseWifiLock()
        serverDiscovery.stopDiscovery()
        callDuckingManager?.stop()
        failoverManager?.stop()
        aoaDacManager?.stop()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zenith Audio Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Low-latency audio streaming from Linux"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zenith Audio Engine")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
