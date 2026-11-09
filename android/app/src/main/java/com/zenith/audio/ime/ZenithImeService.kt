package com.zenith.audio.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.zenith.audio.MainActivity
import com.zenith.audio.R
import com.zenith.audio.mic.MicAudioRecorder
import com.zenith.audio.mic.MicSettingsStore

/**
 * Voice keyboard. Hold the mic, speak, release: the PC transcribes and the
 * text is committed to the focused field through the InputConnection.
 *
 * Partials are shown as composing text (underlined, provisional) and replaced
 * by each preview; only the final RESULT is committed. The IME never touches
 * the WAV or the model — it captures PCM16 and speaks the bridge protocol.
 */
class ZenithImeService : InputMethodService() {

    private enum class State { IDLE, CONNECTING, RECORDING, WAITING }

    private var state = State.IDLE
    private var statusText: TextView? = null
    private var micButton: Button? = null
    private var cancelButton: Button? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var client: DictationClient? = null
    private var recorder: MicAudioRecorder? = null
    private var pressStartedAt = 0L

    private val maxDurationRunnable = Runnable {
        if (state == State.RECORDING) {
            Log.i(TAG, "Dictation reached the ${MAX_DICTATION_MS / 1000} s cap; finishing")
            finishRecording()
        }
    }

    private val resultTimeoutRunnable = Runnable {
        if (state == State.WAITING) {
            failSession("timeout")
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                cancelDictation(clearPreview = true, notify = false)
            }
        }
    }

    private val ioListener = object : DictationClient.Listener {
        override fun onConnected() {
            mainHandler.post {
                if (state != State.CONNECTING) return@post
                startCapture()
            }
        }

        override fun onPartial(text: String) {
            mainHandler.post {
                if (state != State.RECORDING && state != State.WAITING) return@post
                runCatching { currentInputConnection?.setComposingText(text, 1) }
            }
        }

        override fun onResult(text: String) {
            mainHandler.post {
                if (state != State.WAITING) return@post
                endSession(clearPreview = false)
                commitText(text)
                renderIdle()
            }
        }

        override fun onError(code: String, message: String) {
            mainHandler.post {
                Log.w(TAG, "Bridge error $code: $message")
                if (state == State.IDLE) return@post
                failSession(code)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice, null)
        statusText = view.findViewById(R.id.ime_status)
        micButton = view.findViewById(R.id.ime_mic)
        cancelButton = view.findViewById(R.id.ime_cancel)

        micButton?.setOnTouchListener { button, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    pressStartedAt = SystemClock.elapsedRealtime()
                    startDictation()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    button.performClick()
                    releaseDictation()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelDictation(clearPreview = true, notify = false)
                    true
                }
                else -> false
            }
        }

        cancelButton?.setOnClickListener {
            cancelDictation(clearPreview = true, notify = true)
        }
        view.findViewById<Button>(R.id.ime_switch).setOnClickListener {
            switchKeyboard()
        }
        view.findViewById<Button>(R.id.ime_settings).setOnClickListener {
            openSettings()
        }

        if (state == State.IDLE) {
            renderIdle()
        } else {
            updateSessionUi()
            setStatus(
                when (state) {
                    State.CONNECTING -> getString(R.string.ime_connecting)
                    State.RECORDING -> getString(R.string.ime_status_listening)
                    State.WAITING -> getString(R.string.ime_status_transcribing)
                    State.IDLE -> ""
                }
            )
        }
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (state == State.IDLE) renderIdle()
    }

    override fun onFinishInput() {
        cancelDictation(clearPreview = true, notify = false)
        super.onFinishInput()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        cancelDictation(clearPreview = true, notify = false)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    private fun startDictation() {
        if (state != State.IDLE) return

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_SERVER_IP, "")?.trim().orEmpty()
        if (host.isEmpty()) {
            setStatus(getString(R.string.ime_status_no_server))
            toast(getString(R.string.ime_toast_no_server))
            openSettings()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setStatus(getString(R.string.ime_status_no_permission))
            toast(getString(R.string.ime_toast_no_permission))
            openSettings()
            return
        }

        val port = prefs.getInt(KEY_PORT, DictationProtocol.DEFAULT_PORT)
            .takeIf { it in 1..65535 } ?: DictationProtocol.DEFAULT_PORT
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()

        state = State.CONNECTING
        setStatus(getString(R.string.ime_status_connecting, host, port))
        updateSessionUi()

        val session = DictationClient(
            host = host,
            port = port,
            token = token,
            listener = ioListener
        )
        client = session
        session.start()
    }

    private fun startCapture() {
        val session = client ?: return
        val capture = MicAudioRecorder(
            context = this,
            sendAudioPacket = { frame -> session.enqueue(frame) },
            sendControlPacket = {},
            packetize = false
        )
        capture.applySettings(MicSettingsStore.read(this), restartIfRecording = false)

        if (!capture.start()) {
            capture.stop()
            failSession("microphone")
            return
        }

        recorder = capture
        state = State.RECORDING
        setStatus(getString(R.string.ime_status_listening))
        updateSessionUi()
        mainHandler.removeCallbacks(maxDurationRunnable)
        mainHandler.postDelayed(maxDurationRunnable, MAX_DICTATION_MS)
    }

    private fun releaseDictation() {
        when (state) {
            State.CONNECTING -> cancelDictation(clearPreview = true, notify = true)
            State.RECORDING -> {
                val heldMs = SystemClock.elapsedRealtime() - pressStartedAt
                if (heldMs < MIN_HOLD_MS) {
                    cancelDictation(clearPreview = true, notify = true)
                    setStatus(getString(R.string.ime_status_too_short))
                } else {
                    finishRecording()
                }
            }
            State.WAITING, State.IDLE -> Unit
        }
    }

    private fun finishRecording() {
        if (state != State.RECORDING) return
        mainHandler.removeCallbacks(maxDurationRunnable)
        recorder?.stop()
        recorder = null
        state = State.WAITING
        setStatus(getString(R.string.ime_status_transcribing))
        updateSessionUi()
        client?.finish()
        mainHandler.removeCallbacks(resultTimeoutRunnable)
        mainHandler.postDelayed(resultTimeoutRunnable, RESULT_TIMEOUT_MS)
    }

    private fun cancelDictation(clearPreview: Boolean, notify: Boolean) {
        if (state == State.IDLE && client == null && recorder == null) {
            if (clearPreview) clearComposing()
            return
        }
        mainHandler.removeCallbacks(maxDurationRunnable)
        mainHandler.removeCallbacks(resultTimeoutRunnable)
        recorder?.stop()
        recorder = null
        client?.cancel()
        client = null
        state = State.IDLE
        if (clearPreview) clearComposing()
        renderIdle()
        if (notify) toast(getString(R.string.ime_toast_cancelled))
    }

    private fun failSession(code: String) {
        endSession(clearPreview = true)
        val label = describeError(code)
        setStatus(label)
        toast(label)
    }

    private fun endSession(clearPreview: Boolean) {
        mainHandler.removeCallbacks(maxDurationRunnable)
        mainHandler.removeCallbacks(resultTimeoutRunnable)
        recorder?.stop()
        recorder = null
        client?.cancel()
        client = null
        state = State.IDLE
        if (clearPreview) clearComposing()
        updateSessionUi()
    }

    private fun commitText(text: String) {
        val connection = currentInputConnection
        if (connection == null) {
            toast(getString(R.string.ime_toast_no_field))
            return
        }
        if (text.isBlank()) {
            // Empty results are common on silence; committing "" can clear a
            // selection, so only drop the provisional composing text.
            clearComposing()
            return
        }
        runCatching { connection.commitText(text, 1) }
            .onFailure {
                Log.w(TAG, "commitText failed", it)
                toast(getString(R.string.ime_toast_no_field))
            }
    }

    private fun clearComposing() {
        runCatching { currentInputConnection?.setComposingText("", 1) }
    }

    private fun renderIdle() {
        state = State.IDLE
        updateSessionUi()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_SERVER_IP, "")?.trim().orEmpty()
        val port = prefs.getInt(KEY_PORT, DictationProtocol.DEFAULT_PORT)
        setStatus(
            if (host.isEmpty()) getString(R.string.ime_status_no_server)
            else getString(R.string.ime_status_idle, host, port)
        )
    }

    private fun updateSessionUi() {
        micButton?.setText(
            when (state) {
                State.IDLE -> R.string.ime_hold_to_talk
                State.CONNECTING -> R.string.ime_connecting
                State.RECORDING -> R.string.ime_release_to_transcribe
                State.WAITING -> R.string.ime_transcribing_short
            }
        )
        cancelButton?.visibility = if (state == State.IDLE) View.GONE else View.VISIBLE
    }

    private fun setStatus(text: String) {
        statusText?.text = text
    }

    private fun describeError(code: String): String = when (code) {
        "connection" -> getString(R.string.ime_error_connection)
        "network-slow" -> getString(R.string.ime_error_network_slow)
        "timeout" -> getString(R.string.ime_error_timeout)
        "model-busy" -> getString(R.string.ime_error_model_busy)
        "model-unavailable" -> getString(R.string.ime_error_model_unavailable)
        "audio-too-large" -> getString(R.string.ime_error_audio_too_large)
        "bad-request" -> getString(R.string.ime_error_bad_request)
        "unsupported-format" -> getString(R.string.ime_error_unsupported_format)
        "microphone" -> getString(R.string.ime_error_microphone)
        else -> getString(R.string.ime_error_generic)
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
            return
        }
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        val token = window?.window?.attributes?.token ?: return
        @Suppress("DEPRECATION")
        manager.switchToNextInputMethod(token, false)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "ZenithImeService"
        const val PREFS = "zenith_prefs"
        const val KEY_SERVER_IP = "server_ip"
        const val KEY_PORT = "dictation_port"
        const val KEY_TOKEN = "dictation_token"
        const val MIN_HOLD_MS = 300L
        const val MAX_DICTATION_MS = 120_000L
        const val RESULT_TIMEOUT_MS = 120_000L
    }
}
