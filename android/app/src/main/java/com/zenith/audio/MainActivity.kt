package com.zenith.audio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zenith.audio.model.ConnectionState
import com.zenith.audio.model.StreamMetrics
import com.zenith.audio.protocol.ZapProtocol
import com.zenith.audio.service.AudioReceiverService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var audioService: AudioReceiverService? = null
    private var isBound = false
    private val metricsState = mutableStateOf(StreamMetrics())
    private val discoveredServersState = mutableStateOf<List<ZapProtocol.DiscoveredServer>>(emptyList())
    private val isMicRecordingState = mutableStateOf(false)
    private val micLevelState = mutableStateOf(0f)
    private val isCallDuckingEnabledState = mutableStateOf(true)
    private val isCallActiveState = mutableStateOf(false)
    private val isAoaEnabledState = mutableStateOf(false)
    private val isUsbConnectedState = mutableStateOf(false)

    private val activeDetectedIpState = mutableStateOf("192.168.1.9")
    private val activeTransportState = mutableStateOf("Wi-Fi")
    private val isGamingModeState = mutableStateOf(true)
    private val pcAudioEnabledState = mutableStateOf(true)
    private val phoneOnlyState = mutableStateOf(false)

    private val dspEnabledState = mutableStateOf(true)
    private val dspPresetState = mutableStateOf(com.zenith.audio.dsp.DspEqualizerEngine.Preset.FLAT)
    private val dspBassStrengthState = mutableStateOf(0)
    private val dspBandsState = mutableStateOf<List<com.zenith.audio.dsp.DspEqualizerEngine.BandInfo>>(emptyList())
    private val dspBandLevelsState = mutableStateOf<Map<Int, Int>>(emptyMap())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AudioReceiverService.LocalBinder
            val service = localBinder?.getService() ?: return
            audioService = service
            isBound = true

            service.getMetricsFlow()?.let { flow ->
                lifecycleScope.launch {
                    flow.collectLatest { metrics ->
                        metricsState.value = metrics
                    }
                }
            }

            lifecycleScope.launch {
                service.discoveredServers.collectLatest { servers ->
                    discoveredServersState.value = servers
                }
            }

            service.activeDetectedIp?.let { flow ->
                lifecycleScope.launch {
                    flow.collectLatest { ip ->
                        activeDetectedIpState.value = ip
                    }
                }
            }

            service.activeTransport?.let { flow ->
                lifecycleScope.launch {
                    flow.collectLatest { transport ->
                        activeTransportState.value = transport
                    }
                }
            }

            service.dspEngine?.let { engine ->
                lifecycleScope.launch {
                    engine.isEnabled.collectLatest { dspEnabledState.value = it }
                }
                lifecycleScope.launch {
                    engine.currentPreset.collectLatest { dspPresetState.value = it }
                }
                lifecycleScope.launch {
                    engine.bassStrength.collectLatest { dspBassStrengthState.value = it }
                }
                lifecycleScope.launch {
                    engine.bands.collectLatest { dspBandsState.value = it }
                }
                lifecycleScope.launch {
                    engine.bandLevels.collectLatest { dspBandLevelsState.value = it }
                }
            }

            service.isMicRecording?.let { flow ->
                lifecycleScope.launch {
                    flow.collectLatest { recording ->
                        isMicRecordingState.value = recording
                    }
                }
            }

            service.micLevel?.let { flow ->
                lifecycleScope.launch {
                    flow.collectLatest { level ->
                        micLevelState.value = level
                    }
                }
            }

            service.isCallDuckingEnabled?.let { flow ->
                lifecycleScope.launch {
                    flow.collectLatest { enabled ->
                        isCallDuckingEnabledState.value = enabled
                    }
                }
            }

            service.isCallActive?.let { flow ->
                lifecycleScope.launch {
                    flow.collectLatest { active ->
                        isCallActiveState.value = active
                    }
                }
            }

            service.isAoaEnabled?.let { flow ->
                lifecycleScope.launch {
                    flow.collectLatest { enabled ->
                        isAoaEnabledState.value = enabled
                    }
                }
            }

            service.isUsbConnected?.let { flow ->
                lifecycleScope.launch {
                    flow.collectLatest { connected ->
                        isUsbConnectedState.value = connected
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        try {
            val serviceIntent = Intent(this, AudioReceiverService::class.java)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {}

        val prefs = getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
        val initialIp = prefs.getString("server_ip", "192.168.1.9") ?: "192.168.1.9"
        pcAudioEnabledState.value = prefs.getBoolean("pc_audio_enabled", true)
        phoneOnlyState.value = prefs.getBoolean("phone_only", false)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZenithAudioScreen(
                        initialIp = initialIp,
                        activeDetectedIp = activeDetectedIpState.value,
                        activeTransport = activeTransportState.value,
                        metrics = metricsState.value,
                        discoveredServers = discoveredServersState.value,
                        isGamingMode = isGamingModeState.value,
                        dspEnabled = dspEnabledState.value,
                        dspPreset = dspPresetState.value,
                        dspBassStrength = dspBassStrengthState.value,
                        dspBands = dspBandsState.value,
                        dspBandLevels = dspBandLevelsState.value,
                        isMicRecording = isMicRecordingState.value,
                        micLevel = micLevelState.value,
                        isCallDuckingEnabled = isCallDuckingEnabledState.value,
                        isCallActive = isCallActiveState.value,
                        isAoaEnabled = isAoaEnabledState.value,
                        isUsbConnected = isUsbConnectedState.value,
                        pcAudioEnabled = pcAudioEnabledState.value,
                        phoneOnly = phoneOnlyState.value,
                        onConnect = { ip, bitrate ->
                            // Keep every address already used (LAN and VPN), so
                            // discovery can probe both after a network switch.
                            // commit() instead of apply(): the user may close the
                            // app right after connecting, and the address must
                            // survive the next launch.
                            val knownIps = prefs.getStringSet("known_server_ips", emptySet())
                                ?.toMutableSet() ?: mutableSetOf()
                            knownIps.add(ip)
                            prefs.edit()
                                .putString("server_ip", ip)
                                .putStringSet("known_server_ips", knownIps)
                                .commit()
                            startStreamingService(ip, bitrate)
                        },
                        onDisconnect = {
                            stopStreamingService()
                        },
                        onBitrateChange = { bitrate ->
                            audioService?.setBitrate(bitrate)
                        },
                        onToggleAutoBitrate = { enabled ->
                            audioService?.setAutoBitrate(enabled)
                        },
                        onToggleGamingMode = { enabled ->
                            isGamingModeState.value = enabled
                            audioService?.setGamingMode(enabled)
                        },
                        onTriggerAutoDetect = {
                            audioService?.triggerFastDiscovery()
                        },
                        onToggleDsp = { enabled ->
                            audioService?.dspEngine?.setEnabled(enabled)
                        },
                        onSelectDspPreset = { preset ->
                            audioService?.dspEngine?.setPreset(preset)
                        },
                        onSetBassBoost = { strength ->
                            audioService?.dspEngine?.setBassBoost(strength)
                        },
                        onSetBandLevel = { band, level ->
                            audioService?.dspEngine?.setBandLevel(band, level)
                        },
                        onVolumeChange = { volumePercent ->
                            audioService?.setMasterVolume(volumePercent)
                        },
                        onToggleCallDucking = { enabled ->
                            audioService?.setCallDuckingEnabled(enabled)
                        },
                        onToggleAoa = { enabled ->
                            val currentIp = prefs.getString("server_ip", "192.168.1.9") ?: "192.168.1.9"
                            audioService?.setAoaModeEnabled(enabled, currentIp)
                        },
                        onToggleMic = { enable ->
                            if (enable && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                requestRequiredPermissions()
                            } else {
                                audioService?.toggleMic(enable)
                            }
                        },
                        onTogglePcAudio = { enabled ->
                            pcAudioEnabledState.value = enabled
                            prefs.edit().putBoolean("pc_audio_enabled", enabled).apply()
                            audioService?.setPcAudioEnabled(enabled)
                        },
                        onTogglePhoneOnly = { enabled ->
                            phoneOnlyState.value = enabled
                            prefs.edit().putBoolean("phone_only", enabled).apply()
                            audioService?.setPhoneOnly(enabled)
                        }
                    )
                }
            }
        }
    }

    private fun startStreamingService(ip: String, bitrate: Int) {
        val intent = Intent(this, AudioReceiverService::class.java).apply {
            action = AudioReceiverService.ACTION_START
            putExtra(AudioReceiverService.EXTRA_SERVER_IP, ip)
            putExtra(AudioReceiverService.EXTRA_BITRATE, bitrate)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopStreamingService() {
        val intent = Intent(this, AudioReceiverService::class.java).apply {
            action = AudioReceiverService.ACTION_STOP
        }
        startService(intent)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        metricsState.value = StreamMetrics(connectionState = ConnectionState.DISCONNECTED)
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (permissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun ZenithAudioScreen(
    initialIp: String,
    activeDetectedIp: String,
    activeTransport: String,
    metrics: StreamMetrics,
    discoveredServers: List<ZapProtocol.DiscoveredServer>,
    isGamingMode: Boolean,
    dspEnabled: Boolean,
    dspPreset: com.zenith.audio.dsp.DspEqualizerEngine.Preset,
    dspBassStrength: Int,
    dspBands: List<com.zenith.audio.dsp.DspEqualizerEngine.BandInfo>,
    dspBandLevels: Map<Int, Int>,
    isMicRecording: Boolean,
    micLevel: Float,
    isCallDuckingEnabled: Boolean,
    isCallActive: Boolean,
    isAoaEnabled: Boolean,
    isUsbConnected: Boolean,
    pcAudioEnabled: Boolean,
    phoneOnly: Boolean,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onBitrateChange: (Int) -> Unit,
    onToggleAutoBitrate: (Boolean) -> Unit,
    onToggleGamingMode: (Boolean) -> Unit,
    onTriggerAutoDetect: () -> Unit,
    onToggleDsp: (Boolean) -> Unit,
    onSelectDspPreset: (com.zenith.audio.dsp.DspEqualizerEngine.Preset) -> Unit,
    onSetBassBoost: (Int) -> Unit,
    onSetBandLevel: (Int, Int) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onToggleCallDucking: (Boolean) -> Unit,
    onToggleAoa: (Boolean) -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onTogglePcAudio: (Boolean) -> Unit,
    onTogglePhoneOnly: (Boolean) -> Unit
) {
    var serverIp by remember { mutableStateOf(initialIp) }
    var hasUserEditedIp by remember { mutableStateOf(false) }
    var selectedBitrate by remember { mutableIntStateOf(320) }
    var isAutoBitrate by remember { mutableStateOf(false) }
    var volumeSlider by remember { mutableFloatStateOf(100f) }

    LaunchedEffect(activeDetectedIp) {
        // Fill the field automatically only when there is no saved address yet.
        // Otherwise a stale Wi-Fi/LAN detection overwrites the saved VPN IP on
        // every launch, making it look like the address was never persisted.
        if (!hasUserEditedIp && (serverIp.isBlank() || serverIp == "192.168.1.9")) {
            serverIp = activeDetectedIp
        }
    }

    val bitrates = listOf(320, 256, 192, 128, 96, 64)

    val isConnected = metrics.connectionState == ConnectionState.CONNECTED
    val isConnecting = metrics.connectionState == ConnectionState.CONNECTING ||
            metrics.connectionState == ConnectionState.RECONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "ZENITH AUDIO",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Linux / Windows → Android Ultra-Low-Latency",
                    fontSize = 12.sp,
                    color = Color(0xFFAAAAAA)
                )
            }

            StatusBadge(state = metrics.connectionState)
        }

        // LAN Auto-Discovery Section
        AnimatedVisibility(
            visible = !isConnected && discoveredServers.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    text = "DISCOVERED ZENITH SERVERS (LAN)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E676),
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                discoveredServers.forEach { srv ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                serverIp = srv.ip
                                onConnect(srv.ip, selectedBitrate)
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2A1E))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = srv.serverName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "${srv.ip}:${srv.port} • ${srv.bitrateKbps} kbps",
                                        fontSize = 12.sp,
                                        color = Color(0xFF888888)
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    serverIp = srv.ip
                                    onConnect(srv.ip, selectedBitrate)
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00E676),
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Server IP Input Field with 1-Tap Auto-Detect
        OutlinedTextField(
            value = serverIp,
            onValueChange = {
                serverIp = it
                hasUserEditedIp = true
            },
            label = { Text("Server IP (Auto-Discovered)") },
            placeholder = { Text("192.168.1.9 or 10.81.101.129") },
            enabled = !isConnected && !isConnecting,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            leadingIcon = {
                Icon(
                    imageVector = if (activeTransport.contains("USB")) Icons.Default.Usb else Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (activeTransport.contains("USB")) Color(0xFF00E5FF) else Color(0xFF00E676)
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = {
                        onTriggerAutoDetect()
                        if (discoveredServers.isNotEmpty()) {
                            serverIp = discoveredServers.first().ip
                            hasUserEditedIp = true
                        }
                        // If nothing was discovered, keep the saved/manual IP.
                        // Falling back to activeDetectedIp here used to replace
                        // a valid VPN address with the "192.168.1.9" placeholder.
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Auto-Detect IP",
                        tint = Color(0xFF00E676)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00E676),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        // Active Auto-Pilot Link status row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (activeTransport.contains("USB")) Color(0xFF00E5FF) else Color(0xFF00E676),
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Link: $activeTransport ($activeDetectedIp)",
                    fontSize = 11.sp,
                    color = Color(0xFFCCCCCC)
                )
            }

            TextButton(
                onClick = {
                    onTriggerAutoDetect()
                    if (discoveredServers.isNotEmpty()) {
                        serverIp = discoveredServers.first().ip
                        hasUserEditedIp = true
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "⚡ AUTO-DETECT IP",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E676)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Quality Bitrate Selector with AUTO mode
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AUDIO QUALITY (BITRATE)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF888888)
            )

            if (isAutoBitrate) {
                Text(
                    text = "AUTO-PILOT ACTIVE (${metrics.bitrateKbps}k)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E676)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // AUTO Mode Chip
            item {
                FilterChip(
                    selected = isAutoBitrate,
                    onClick = {
                        isAutoBitrate = !isAutoBitrate
                        onToggleAutoBitrate(isAutoBitrate)
                        if (!isAutoBitrate) {
                            onBitrateChange(selectedBitrate)
                        }
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "AUTO",
                                fontSize = 12.sp,
                                fontWeight = if (isAutoBitrate) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00E676),
                        selectedLabelColor = Color.Black
                    )
                )
            }

            // Fixed bitrates
            items(bitrates) { br ->
                val isSelected = !isAutoBitrate && (br == selectedBitrate)
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        isAutoBitrate = false
                        onToggleAutoBitrate(false)
                        selectedBitrate = br
                        if (isConnected) onBitrateChange(br)
                    },
                    label = {
                        Text(
                            text = if (br == 320) "$br kbps (HQ)" else "$br kbps",
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00E676),
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Main Connect / Disconnect Action Button
        Button(
            onClick = {
                if (isConnected || isConnecting) {
                    onDisconnect()
                } else {
                    onConnect(serverIp.trim(), selectedBitrate)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected || isConnecting) Color(0xFFFF5252) else Color(0xFF00E676),
                contentColor = if (isConnected || isConnecting) Color.White else Color.Black
            )
        ) {
            Icon(
                imageVector = if (isConnected || isConnecting) Icons.Default.Close else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isConnected -> "DISCONNECT STREAM"
                    isConnecting -> "CANCEL CONNECTING"
                    else -> "CONNECT TO SERVER"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ⚡ DYNAMIC LATENCY PROFILE CARD (0.5ms Gaming vs 5.0ms Media)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isGamingMode) Color(0xFF142918) else Color(0xFF162533)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = if (isGamingMode) Color(0xFF00E676) else Color(0xFF40C4FF),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isGamingMode) "⚡ ULTRA GAMING MODE (0.5ms)" else "🛡️ SMOOTH MEDIA MODE (5ms)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text(
                            text = if (isGamingMode) "Sub-millisecond competitive response for CS2/Valorant"
                                   else "Glitch-free buffer safe mode through walls and interference",
                            fontSize = 11.sp,
                            color = if (isGamingMode) Color(0xFFA5D6A7) else Color(0xFF90CAF9)
                        )
                    }
                }

                Switch(
                    checked = isGamingMode,
                    onCheckedChange = { onToggleGamingMode(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Color(0xFF00E676),
                        uncheckedThumbColor = Color(0xFF40C4FF),
                        uncheckedTrackColor = Color(0xFF1A384D)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 🎛️ STUDIO 10-BAND HARDWARE DSP EQUALIZER & BASS BOOST CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (dspEnabled) Color(0xFF1F1C2D) else Color(0xFF1E1E1E)
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = if (dspEnabled) Color(0xFFB388FF) else Color(0xFF888888),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "STUDIO HARDWARE EQUALIZER & BASS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (dspEnabled) "Active: ${dspPreset.displayName} • 0.0ms DSP Latency" else "Hardware DSP Off (Direct Passthrough)",
                                fontSize = 11.sp,
                                color = if (dspEnabled) Color(0xFFD1C4E9) else Color(0xFF888888)
                            )
                        }
                    }

                    Switch(
                        checked = dspEnabled,
                        onCheckedChange = { onToggleDsp(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color(0xFFB388FF),
                            uncheckedThumbColor = Color(0xFF888888),
                            uncheckedTrackColor = Color(0xFF333333)
                        )
                    )
                }

                if (dspEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Preset Chips
                    Text(
                        text = "PRESET PROFILES",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB388FF)
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(com.zenith.audio.dsp.DspEqualizerEngine.Preset.values()) { preset ->
                            val isSelected = dspPreset == preset
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectDspPreset(preset) },
                                label = {
                                    Text(
                                        text = preset.displayName,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFB388FF),
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Bass Boost Resonator Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔊 BASS BOOST RESONATOR",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFBBBBBB)
                        )
                        Text(
                            text = "${(dspBassStrength / 10f).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB388FF)
                        )
                    }

                    Slider(
                        value = dspBassStrength.toFloat(),
                        onValueChange = { onSetBassBoost(it.toInt()) },
                        valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFB388FF),
                            activeTrackColor = Color(0xFFB388FF),
                            inactiveTrackColor = Color(0xFF333333)
                        )
                    )

                    // Individual Hardware Band Gain Sliders
                    if (dspBands.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "FREQUENCY BANDS (dB)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFBBBBBB)
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        dspBands.forEach { band ->
                            val currentLevel = dspBandLevels[band.index] ?: 0
                            val freqStr = if (band.centerFreqHz >= 1000) "${band.centerFreqHz / 1000}k" else "${band.centerFreqHz}Hz"
                            val gainDb = currentLevel / 100f

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = freqStr,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFAAAAAA),
                                    modifier = Modifier.width(42.dp)
                                )
                                Slider(
                                    value = currentLevel.toFloat(),
                                    onValueChange = { onSetBandLevel(band.index, it.toInt()) },
                                    valueRange = band.minMilliBels.toFloat()..band.maxMilliBels.toFloat(),
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFB388FF),
                                        activeTrackColor = Color(0xFF7C4DFF),
                                        inactiveTrackColor = Color(0xFF333333)
                                    )
                                )
                                Text(
                                    text = String.format("%+.1fdB", gainDb),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (gainDb > 0) Color(0xFFB388FF) else Color(0xFF888888),
                                    modifier = Modifier.width(46.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 🎙️ DEDICATED REVERSE WIRELESS MICROPHONE CARD (On-Demand Section)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isMicRecording) Color(0xFF1C2D1F) else Color(0xFF1E1E1E)
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isMicRecording) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = null,
                            tint = if (isMicRecording) Color(0xFF00E676) else Color(0xFF888888),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "WIRELESS MICROPHONE",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (isMicRecording) "Streaming to PC ('Zenith Wireless Microphone')" else "Routes phone mic to Linux PC for Discord/Zoom",
                                fontSize = 11.sp,
                                color = if (isMicRecording) Color(0xFF00E676) else Color(0xFF888888)
                            )
                        }
                    }

                    // Independent Mic Toggle Button
                    Button(
                        onClick = {
                            onToggleMic(!isMicRecording)
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMicRecording) Color(0xFFFF5252) else Color(0xFF2E7D32),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isMicRecording) "TURN OFF" else "TURN ON",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Live Mic Input Level VU Meter
                if (isMicRecording) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("MIC LEVEL", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF888888))
                        Spacer(modifier = Modifier.width(8.dp))
                        LinearProgressIndicator(
                            progress = micLevel.coerceIn(0f, 1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = Color(0xFF00E676),
                            trackColor = Color(0xFF333333)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 🔊 AUDIO ROUTING (MIC-ONLY / PHONE-ONLY)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "AUDIO ROUTING",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PC AUDIO TO PHONE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (pcAudioEnabled) "System audio is streamed to the phone" else "Mic-only mode (no PC audio on the phone)",
                            fontSize = 11.sp,
                            color = if (pcAudioEnabled) Color(0xFF00E676) else Color(0xFFFFB74D)
                        )
                    }
                    Switch(
                        checked = pcAudioEnabled,
                        onCheckedChange = onTogglePcAudio
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PHONE-ONLY OUTPUT",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (phoneOnly) "PC speakers muted; audio goes only to the phone" else "PC speakers stay on",
                            fontSize = 11.sp,
                            color = if (phoneOnly) Color(0xFF00E676) else Color(0xFF888888)
                        )
                    }
                    Switch(
                        checked = phoneOnly,
                        onCheckedChange = onTogglePhoneOnly
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 🎚️ HARDWARE VOLUME SYNC CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (volumeSlider > 0) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LINUX MASTER VOLUME SYNC",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Text(
                        text = "${volumeSlider.toInt()}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF00E676)
                    )
                }

                Slider(
                    value = volumeSlider,
                    onValueChange = {
                        volumeSlider = it
                        onVolumeChange(it.toInt())
                    },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E676),
                        activeTrackColor = Color(0xFF00E676),
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 📞 SMART PHONE CALL AUTO-DUCKING CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isCallActive) Color(0xFF381C14) else Color(0xFF1E1E1E)
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            tint = if (isCallActive) Color(0xFFFF9100) else Color(0xFF00E676),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "PHONE CALL AUTO-DUCKING",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isCallDuckingEnabled) Color(0xFF00E676).copy(alpha = 0.2f)
                                            else Color(0xFF333333),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isCallDuckingEnabled) "ENABLED" else "MUTED",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCallDuckingEnabled) Color(0xFF00E676) else Color(0xFF888888)
                                    )
                                }
                            }
                            Text(
                                text = if (isCallActive) "Active call in progress: PC volume ducked to 15%"
                                       else "Auto-ducks PC volume to 15% on incoming & active calls",
                                fontSize = 11.sp,
                                color = if (isCallActive) Color(0xFFFFCC80) else Color(0xFF888888)
                            )
                        }
                    }

                    Switch(
                        checked = isCallDuckingEnabled,
                        onCheckedChange = { onToggleCallDucking(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color(0xFF00E676),
                            uncheckedThumbColor = Color(0xFF888888),
                            uncheckedTrackColor = Color(0xFF333333)
                        )
                    )
                }

                if (isCallActive) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFF9100).copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFFFF9100), shape = CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ACTIVE CALL DETECTED • DUCKING TO 15% ACTIVE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFB74D)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ⚡ ANDROID AOA 2.0 HARDWARE USB DAC CARD (ON/OFF OPTION)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isAoaEnabled) Color(0xFF142938) else Color(0xFF1E1E1E)
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Usb,
                            contentDescription = null,
                            tint = if (isAoaEnabled) Color(0xFF40C4FF) else Color(0xFF888888),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "AOA 2.0 HARDWARE USB DAC",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isAoaEnabled) Color(0xFF00B0FF).copy(alpha = 0.2f)
                                            else Color(0xFF333333),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isAoaEnabled) "ON" else "OFF",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAoaEnabled) Color(0xFF40C4FF) else Color(0xFF888888)
                                    )
                                }
                            }
                            Text(
                                text = "Pure zero-network USB accessory DAC • Zero Wi-Fi/IP stack",
                                fontSize = 11.sp,
                                color = if (isAoaEnabled) Color(0xFFB3E5FC) else Color(0xFF888888)
                            )
                        }
                    }

                    Switch(
                        checked = isAoaEnabled,
                        onCheckedChange = { onToggleAoa(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color(0xFF40C4FF),
                            uncheckedThumbColor = Color(0xFF888888),
                            uncheckedTrackColor = Color(0xFF333333)
                        )
                    )
                }

                if (isAoaEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isUsbConnected) Color(0xFF00E676).copy(alpha = 0.15f)
                                else Color(0xFFFFA000).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isUsbConnected) Color(0xFF00E676) else Color(0xFFFFB300),
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isUsbConnected)
                                    "AOA HARDWARE USB ACCESSORY CONNECTED (0.5ms DAC)"
                                else
                                    "USB DAC READY • CONNECT USB CABLE TO LINUX PC",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isUsbConnected) Color(0xFF00E676) else Color(0xFFFFCA28)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Real-Time Diagnostic Dashboard
        Text(
            text = "LIVE TELEMETRY & DIAGNOSTICS",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Hardware Output & Active Transport Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (metrics.isBluetooth) Icons.Default.Bluetooth else Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = if (metrics.isBluetooth) Color(0xFF448AFF) else Color(0xFF00E676),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "ACTIVE AUDIO SINK",
                                fontSize = 9.sp,
                                color = Color(0xFF888888),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = metrics.outputDeviceName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }

                    // Transport Status Pill
                    Box(
                        modifier = Modifier
                            .background(
                                if (metrics.transportType.contains("USB", ignoreCase = true)) Color(0xFF16384C)
                                else Color(0xFF1B2E1E),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (metrics.transportType.contains("USB", ignoreCase = true))
                                    Icons.Default.Usb else Icons.Default.Wifi,
                                contentDescription = null,
                                tint = if (metrics.transportType.contains("USB", ignoreCase = true))
                                    Color(0xFF40C4FF) else Color(0xFF00E676),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = metrics.transportType,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (metrics.transportType.contains("USB", ignoreCase = true))
                                    Color(0xFF40C4FF) else Color(0xFF00E676)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SyncAlt,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Zero-Drop Hot-Failover: Wi-Fi ↔ USB active (0ms drop)",
                        fontSize = 10.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Telemetry Grid
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                title = "EST. TOTAL LATENCY",
                value = if (isConnected) "%.1f ms".format(metrics.totalEstimatedLatencyMs) else "--",
                subtitle = "Net: %.1f | Jbuf: %.0f | DAC: %.0f".format(
                    metrics.networkRttMs / 2.0,
                    metrics.jitterBufferMs,
                    metrics.playbackLatencyMs
                ),
                icon = Icons.Default.Timer,
                highlight = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            MetricCard(
                title = "PACKET LOSS",
                value = if (isConnected) "%.2f%%".format(metrics.packetLossPercent) else "0.00%",
                subtitle = "Lost: ${metrics.packetsLost} / Rcv: ${metrics.packetsReceived}",
                icon = Icons.Default.ReportProblem,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                title = "NETWORK JITTER",
                value = if (isConnected) "%.2f ms".format(metrics.jitterMs) else "--",
                subtitle = "RFC 3550 Estimator",
                icon = Icons.Default.Timeline,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            MetricCard(
                title = "STREAM FORMAT",
                value = if (isConnected) "${metrics.bitrateKbps} kbps" else "320 kbps",
                subtitle = "48 kHz | Stereo | Opus Low-Delay",
                icon = Icons.Default.GraphicEq,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                title = "ROUND TRIP (RTT)",
                value = if (isConnected) "%.1f ms".format(metrics.networkRttMs) else "--",
                subtitle = "UDP Ping/Pong Heartbeat",
                icon = Icons.Default.NetworkCheck,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            MetricCard(
                title = "UNDERRUN COUNT",
                value = "${metrics.audioUnderruns}",
                subtitle = "AudioTrack Buffer Glitches",
                icon = Icons.Default.Warning,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hardware AOA 2.0 & NetEQ Clock Sync Footer
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF181818))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = Color(0xFF00E676),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "AOA 2.0 DAC • ZERO-DROP FAILOVER • CALL DUCKING",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "AOA 2.0 USB DAC hardware mode • 0ms Wi-Fi ↔ USB Failover • WSOLA NetEQ sync",
                        fontSize = 10.sp,
                        color = Color(0xFF888888)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(state: ConnectionState) {
    val (bgColor, textColor, text) = when (state) {
        ConnectionState.CONNECTED -> Triple(Color(0xFF1B5E20), Color(0xFF00E676), "CONNECTED")
        ConnectionState.CONNECTING -> Triple(Color(0xFFE65100), Color(0xFFFFB74D), "CONNECTING")
        ConnectionState.RECONNECTING -> Triple(Color(0xFFB71C1C), Color(0xFFFF8A80), "RECONNECTING")
        ConnectionState.DISCONNECTED -> Triple(Color(0xFF333333), Color(0xFFAAAAAA), "DISCONNECTED")
    }

    Box(
        modifier = Modifier
            .background(bgColor, shape = CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(textColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) Color(0xFF1C2A20) else Color(0xFF1E1E1E)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (highlight) Color(0xFF00E676) else Color(0xFF888888)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlight) Color(0xFF00E676) else Color(0xFF666666),
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (highlight) Color(0xFF00E676) else Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 9.sp,
                color = Color(0xFFAAAAAA),
                maxLines = 1
            )
        }
    }
}
