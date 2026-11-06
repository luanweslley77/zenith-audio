package com.zenith.audio.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.zenith.audio.protocol.ZapProtocol
import com.zenith.audio.usb.UsbIpResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer

class ServerDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "ServerDiscovery"
        private const val DISCOVERY_PORT = ZapProtocol.DEFAULT_PORT
    }

    private val _discoveredServers = MutableStateFlow<List<ZapProtocol.DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<ZapProtocol.DiscoveredServer>> = _discoveredServers.asStateFlow()

    private val _latestDiscoveredServer = MutableStateFlow<ZapProtocol.DiscoveredServer?>(null)
    val latestDiscoveredServer: StateFlow<ZapProtocol.DiscoveredServer?> = _latestDiscoveredServer.asStateFlow()

    private val scanTriggerChannel = Channel<Unit>(Channel.CONFLATED)
    private var discoveryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return

        discoveryJob = scope.launch {
            var socket: DatagramSocket? = null
            var multicastLock: WifiManager.MulticastLock? = null

            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                try {
                    multicastLock = wifiManager?.createMulticastLock("ZenithDiscoveryLock")?.apply {
                        setReferenceCounted(true)
                        acquire()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MulticastLock acquire failed: ${e.message}")
                }

                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1200
                    bind(InetSocketAddress(0)) // Ephemeral port to receive probe responses
                }

                val rxBuffer = ByteArray(1024)
                val rxPacket = DatagramPacket(rxBuffer, rxBuffer.size)
                val probeData = ZapProtocol.buildDiscoveryProbePacket()

                val serverMap = mutableMapOf<String, Pair<ZapProtocol.DiscoveredServer, Long>>()

                while (isActive) {
                    val discoveryTargets = getDiscoveryTargets()
                    for (target in discoveryTargets) {
                        try {
                            val probePacket = DatagramPacket(probeData, probeData.size, target, DISCOVERY_PORT)
                            socket.send(probePacket)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed probe send to $target: ${e.message}")
                        }
                    }

                    // Listen for beacons/responses for 1.8 seconds
                    val windowStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - windowStart < 1800L && isActive) {
                        try {
                            rxPacket.length = rxBuffer.size
                            socket.receive(rxPacket)

                            val ip = rxPacket.address.hostAddress ?: continue
                            val byteBuffer = ByteBuffer.wrap(rxPacket.data, rxPacket.offset, rxPacket.length)
                            val header = ZapProtocol.parseHeader(byteBuffer)

                            if (header != null && (header.type == ZapProtocol.PKT_DISCOVERY_BEACON || header.type == ZapProtocol.PKT_SERVER_ANNOUNCE)) {
                                val payloadBuffer = ByteBuffer.wrap(
                                    rxPacket.data,
                                    rxPacket.offset + ZapProtocol.HEADER_SIZE,
                                    rxPacket.length - ZapProtocol.HEADER_SIZE
                                )
                                val server = ZapProtocol.parseDiscoveryBeacon(payloadBuffer, ip)
                                if (server != null) {
                                    val now = System.currentTimeMillis()
                                    serverMap[server.ip] = Pair(server, now)
                                    _latestDiscoveredServer.value = server

                                    // Filter stale servers (> 12s) and prioritize high-speed USB interface
                                    val activeList = serverMap.values
                                        .filter { now - it.second < 12_000L }
                                        .map { it.first }
                                        .sortedByDescending { it.ip.startsWith("10.") || it.ip.startsWith("172.") }
                                    _discoveredServers.value = activeList
                                    Log.i(TAG, "Discovered active Zenith host: ${server.serverName} at ${server.ip}:${server.port}")
                                }
                            }
                        } catch (_: Exception) {
                            // Socket timeout or packet read timeout
                        }
                    }

                    // Wait 3s or wake immediately on manual scan trigger
                    kotlinx.coroutines.selects.select<Unit> {
                        scanTriggerChannel.onReceive { }
                        scope.launch { delay(3000L) }.onJoin { }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
            } finally {
                socket?.close()
                multicastLock?.let {
                    if (it.isHeld) it.release()
                }
            }
        }
    }

    fun triggerFastScan() {
        scanTriggerChannel.trySend(Unit)
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    private fun getDiscoveryTargets(): Set<InetAddress> {
        val targets = mutableSetOf<InetAddress>()
        try {
            // 1. Global broadcast fallback
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}

        try {
            // 1b. Unicast to every server address used before. Broadcast does
            // not cross point-to-point VPNs such as WireGuard, so these probes
            // are what keep discovery working when the phone is on a VPN.
            // Keeping the LAN and VPN addresses means switching networks does
            // not require typing the address again.
            val prefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
            val candidates = mutableSetOf<String>()
            prefs.getStringSet("known_server_ips", emptySet())?.let { candidates.addAll(it) }
            prefs.getString("server_ip", null)?.let { candidates.add(it) }

            for (candidate in candidates) {
                if (candidate.isNotBlank()) {
                    try {
                        targets.add(InetAddress.getByName(candidate.trim()))
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid saved server address $candidate: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add saved server IPs to discovery targets: ${e.message}")
        }

        try {
            // 2. Discover all local subnet broadcasts across Wi-Fi, Ethernet, and USB
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (!netIf.isUp || netIf.isLoopback) continue

                for (ifAddr in netIf.interfaceAddresses) {
                    val bcast = ifAddr.broadcast
                    if (bcast != null) {
                        targets.add(bcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed enumerating network interfaces: ${e.message}")
        }

        try {
            // 3. USB Tethering Host Gateway Direct Unicast
            val usbHostIp = UsbIpResolver.resolveUsbHostIp()
            if (usbHostIp.isNotBlank() && usbHostIp != "10.81.101.129" && usbHostIp != "127.0.0.1") {
                targets.add(InetAddress.getByName(usbHostIp))
            }
            targets.add(InetAddress.getByName("10.81.101.129"))
        } catch (_: Exception) {}

        return targets
    }
}
