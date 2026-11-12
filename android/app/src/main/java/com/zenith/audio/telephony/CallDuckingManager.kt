package com.zenith.audio.telephony

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

class CallDuckingManager(
    private val context: Context,
    private val onSetVolume: (Int) -> Unit,
    private val onDuckingStateChanged: (Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "CallDucking"
        private const val DUCKED_VOLUME_PERCENT = 15 // Lower volume to 15% during phone call
    }

    private val prefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)

    private val _isDuckingEnabled = MutableStateFlow(
        prefs.getBoolean("call_ducking_enabled", true)
    )
    val isDuckingEnabled: StateFlow<Boolean> = _isDuckingEnabled.asStateFlow()

    private val _isCallActive = MutableStateFlow(false)
    val isCallActive: StateFlow<Boolean> = _isCallActive.asStateFlow()

    private var telephonyManager: TelephonyManager? = null
    private var preDuckVolume: Int = 100
    private var isDucked = false
    private val stateLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null
    @Volatile
    private var stopped = false

    fun start() {
        if (telephonyManager != null || executor.isShutdown) return
        stopped = false
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val tm = telephonyManager ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state)
                    }
                }
                tm.registerTelephonyCallback(executor, cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
            }
            Log.i(TAG, "Call auto-ducking listener registered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register call state listener (permission may be needed): ${e.message}")
        }
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        telephonyManager?.let { tm ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (telephonyCallback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
                } else {
                    phoneStateListener?.let {
                        @Suppress("DEPRECATION")
                        tm.listen(it, PhoneStateListener.LISTEN_NONE)
                    }
                }
            } catch (_: Exception) {}
        }
        telephonyCallback = null
        phoneStateListener = null
        telephonyManager = null

        synchronized(stateLock) {
            if (isDucked) {
                onSetVolume(preDuckVolume)
                onDuckingStateChanged(false)
                isDucked = false
            }
        }
        executor.shutdownNow()
    }

    fun setAutoDuckingEnabled(enabled: Boolean) {
        _isDuckingEnabled.value = enabled
        prefs.edit().putBoolean("call_ducking_enabled", enabled).apply()
        synchronized(stateLock) {
            if (!enabled && isDucked) {
                onSetVolume(preDuckVolume)
                onDuckingStateChanged(false)
                isDucked = false
            }
        }
    }

    fun updateCurrentVolume(volumePercent: Int) {
        synchronized(stateLock) {
            // While ducked, ignore the 15% echo but keep a genuine user
            // adjustment so call end does not jump back over it.
            if (!isDucked || volumePercent != DUCKED_VOLUME_PERCENT) {
                preDuckVolume = volumePercent
            }
        }
    }

    private fun handleCallState(state: Int) {
        mainHandler.post {
            if (stopped) return@post
            handleCallStateOnMain(state)
        }
    }

    private fun handleCallStateOnMain(state: Int) {
        synchronized(stateLock) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    _isCallActive.value = true
                    if (_isDuckingEnabled.value && !isDucked) {
                        isDucked = true
                        Log.i(TAG, "Call active (state=$state) -> Ducking PC audio to ${DUCKED_VOLUME_PERCENT}%")
                        onSetVolume(DUCKED_VOLUME_PERCENT)
                        onDuckingStateChanged(true)
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    _isCallActive.value = false
                    if (isDucked) {
                        Log.i(TAG, "Call ended -> Restoring PC audio volume to ${preDuckVolume}%")
                        onSetVolume(preDuckVolume)
                        onDuckingStateChanged(false)
                        isDucked = false
                    }
                }
            }
        }
    }
}
