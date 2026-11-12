package com.zenith.audio.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

object UsbIpResolver {
    fun resolveUsbHostIp(): String {
        try {
            val file = java.io.File("/proc/net/arp")
            if (file.exists()) {
                for (line in file.readLines()) {
                    val tokens = line.trim().split(Regex("\\s+"))
                    if (tokens.size >= 6) {
                        val ip = tokens[0]
                        val flags = tokens[2]
                        val iface = tokens[5]
                        if (flags == "0x2" && (iface.startsWith("rndis") || iface.startsWith("usb") || iface.startsWith("eth"))) {
                            if (ip.isNotBlank() && ip != "0.0.0.0") return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.name.startsWith("rndis") || intf.name.startsWith("usb") || intf.name.startsWith("eth")) {
                    for (addr in intf.inetAddresses) {
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            val lastDot = host.lastIndexOf('.')
                            if (lastDot > 0) {
                                return host.substring(0, lastDot + 1) + "129"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return "10.81.101.129"
    }
}

class AoaDacManager(
    private val context: Context,
    private val onSwitchTransport: (isUsb: Boolean, targetIp: String) -> Unit
) {
    companion object {
        private const val TAG = "AoaDacManager"
    }

    private val _isAoaEnabled = MutableStateFlow(
        context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
            .getBoolean("aoa_enabled", false)
    )
    val isAoaEnabled: StateFlow<Boolean> = _isAoaEnabled.asStateFlow()

    private val prefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)

    private val _isUsbConnected = MutableStateFlow(false)
    val isUsbConnected: StateFlow<Boolean> = _isUsbConnected.asStateFlow()

    private var usbManager: UsbManager? = null
    private var ioExecutor = Executors.newSingleThreadExecutor()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    }
                    Log.i(TAG, "USB Accessory Attached: ${accessory?.description}")
                    _isUsbConnected.value = true
                    if (_isAoaEnabled.value) {
                        ioExecutor.execute {
                            val usbIp = UsbIpResolver.resolveUsbHostIp()
                            onSwitchTransport(true, usbIp)
                        }
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    Log.i(TAG, "USB Accessory Detached")
                    _isUsbConnected.value = false
                    if (_isAoaEnabled.value) {
                        ioExecutor.execute { onSwitchTransport(false, "") }
                    }
                }
            }
        }
    }

    fun start() {
        if (ioExecutor.isShutdown) {
            ioExecutor = Executors.newSingleThreadExecutor()
        }
        usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        _isUsbConnected.value = usbManager?.accessoryList?.isNotEmpty() == true
        if (_isAoaEnabled.value && _isUsbConnected.value) {
            ioExecutor.execute {
                onSwitchTransport(true, UsbIpResolver.resolveUsbHostIp())
            }
        }
    }

    fun stop() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        ioExecutor.shutdownNow()
    }

    fun setAoaModeEnabled(enabled: Boolean, fallbackWifiIp: String) {
        if (ioExecutor.isShutdown) {
            ioExecutor = Executors.newSingleThreadExecutor()
        }
        _isAoaEnabled.value = enabled
        prefs.edit().putBoolean("aoa_enabled", enabled).apply()
        Log.i(TAG, "AOA 2.0 Hardware USB DAC mode toggled: $enabled")

        if (enabled) {
            if (!_isUsbConnected.value) {
                Log.i(TAG, "AOA enabled; waiting for a USB accessory before switching")
                return
            }
            ioExecutor.execute {
                val usbIp = UsbIpResolver.resolveUsbHostIp()
                Log.i(TAG, "AOA USB DAC enabled -> Routing to USB host $usbIp")
                onSwitchTransport(true, usbIp)
            }
        } else {
            Log.i(TAG, "AOA USB DAC disabled -> Restoring Wi-Fi to $fallbackWifiIp")
            ioExecutor.execute { onSwitchTransport(false, fallbackWifiIp) }
        }
    }
}
