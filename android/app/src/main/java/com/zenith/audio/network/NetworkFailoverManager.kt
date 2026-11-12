package com.zenith.audio.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.zenith.audio.usb.UsbIpResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkFailoverManager(
    private val context: Context,
    private val onFailoverTriggered: (newTransport: String, targetIp: String) -> Unit
) {
    companion object {
        private const val TAG = "FailoverManager"
    }

    private val _isWifiAvailable = MutableStateFlow(false)
    val isWifiAvailable: StateFlow<Boolean> = _isWifiAvailable.asStateFlow()

    private val _isUsbAvailable = MutableStateFlow(false)
    val isUsbAvailable: StateFlow<Boolean> = _isUsbAvailable.asStateFlow()

    private val _activeDetectedIp = MutableStateFlow("192.168.1.9")
    val activeDetectedIp: StateFlow<String> = _activeDetectedIp.asStateFlow()

    private val _activeTransport = MutableStateFlow("Wi-Fi")
    val activeTransport: StateFlow<String> = _activeTransport.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val wifiNetworks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
    private val usbNetworks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()

    private var cachedWifiIp: String = "192.168.1.9"
    @Volatile
    private var pollerUsbDetected = false
    private var isStreamingActive: Boolean = false

    fun start(initialWifiIp: String = "192.168.1.9") {
        if (initialWifiIp.isNotBlank() && initialWifiIp != "127.0.0.1") {
            cachedWifiIp = initialWifiIp
            _activeDetectedIp.value = initialWifiIp
        }

        if (networkCallback != null) return // Already running

        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val cm = connectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val hasUsbEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)

                if (hasWifi) wifiNetworks.add(network) else wifiNetworks.remove(network)
                if (hasUsbEthernet) usbNetworks.add(network) else usbNetworks.remove(network)

                _isWifiAvailable.value = wifiNetworks.isNotEmpty()
                _isUsbAvailable.value = usbNetworks.isNotEmpty() || pollerUsbDetected
                selectTransport()
            }

            override fun onLost(network: Network) {
                wifiNetworks.remove(network)
                usbNetworks.remove(network)
                _isWifiAvailable.value = wifiNetworks.isNotEmpty()
                _isUsbAvailable.value = usbNetworks.isNotEmpty() || pollerUsbDetected
                Log.w(TAG, "Network lost on interface. Checking fallback...")
                selectTransport()
            }
        }

        try {
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
            Log.i(TAG, "Network failover monitor registered and active")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }

        startInterfacePoller()
    }

    private var pollerJob: kotlinx.coroutines.Job? = null
    private val pollerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)

    /**
     * Chooses the transport from the union of all networks, never from a
     * single callback (Wi-Fi and USB can be up at the same time).
     */
    private fun selectTransport() {
        val previous = _activeTransport.value
        val target: String
        val ip: String
        if (_isUsbAvailable.value) {
            target = "USB 0.5ms Direct"
            ip = UsbIpResolver.resolveUsbHostIp()
        } else if (_isWifiAvailable.value) {
            target = "Wi-Fi"
            ip = cachedWifiIp
        } else {
            _activeDetectedIp.value = ""
            _activeTransport.value = "Offline"
            return
        }
        _activeDetectedIp.value = ip
        _activeTransport.value = target
        if (isStreamingActive && target != previous) {
            Log.i(TAG, "[Auto-Pilot] $previous -> $target ($ip)")
            onFailoverTriggered(target, ip)
        }
    }

    private fun startInterfacePoller() {
        pollerJob?.cancel()
        pollerJob = pollerScope.launch {
            while (isActive) {
                checkPhysicalInterfaces()
                delay(1200L)
            }
        }
    }

    private fun checkPhysicalInterfaces() {
        var hasUsb = false
        var usbHostIp = ""
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isUp && (intf.name.startsWith("rndis") || intf.name.startsWith("usb") ||
                            intf.name.startsWith("ncm") || intf.name.startsWith("eth"))
                ) {
                    for (addr in intf.inetAddresses) {
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            hasUsb = true
                            usbHostIp = UsbIpResolver.resolveUsbHostIp()
                            break
                        }
                    }
                }
                if (hasUsb) break
            }
        } catch (_: Exception) {}

        if (hasUsb) {
            val wasUsb = _isUsbAvailable.value
            pollerUsbDetected = true
            _isUsbAvailable.value = true
            val targetUsbIp = usbHostIp.ifBlank { "10.81.101.129" }
            _activeDetectedIp.value = targetUsbIp
            _activeTransport.value = "USB 0.5ms Direct"

            if (!wasUsb && isStreamingActive) {
                Log.i(TAG, "[Auto-Pilot] USB tethering attached -> Zero-drop hot-switching to USB $targetUsbIp")
                onFailoverTriggered("USB 0.5ms Direct", targetUsbIp)
            }
        } else if (usbNetworks.isEmpty()) {
            // Do not clear a USB transport that ConnectivityManager still
            // reports; the interface poller is only a fallback.
            val wasUsb = _isUsbAvailable.value
            pollerUsbDetected = false
            _isUsbAvailable.value = false
            if (wasUsb) {
                Log.i(TAG, "[Auto-Pilot] USB tethering detached -> Falling back to Wi-Fi $cachedWifiIp")
                _activeDetectedIp.value = cachedWifiIp
                _activeTransport.value = "Wi-Fi"
                if (isStreamingActive) {
                    onFailoverTriggered("Wi-Fi", cachedWifiIp)
                }
            }
        }
    }

    fun setStreamingActive(active: Boolean) {
        isStreamingActive = active
    }

    fun stop() {
        pollerJob?.cancel()
        pollerJob = null
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    fun updateWifiServerIp(ip: String) {
        if (ip.isNotBlank() && ip != "127.0.0.1") {
            cachedWifiIp = ip
            if (!_isUsbAvailable.value) {
                _activeDetectedIp.value = ip
            }
        }
    }

    fun manualSwitch(transport: String, targetIp: String) {
        _activeTransport.value = transport
        _activeDetectedIp.value = targetIp
        if (isStreamingActive) {
            onFailoverTriggered(transport, targetIp)
        }
    }
}
