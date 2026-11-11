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
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.zenith.audio.MainActivity
import com.zenith.audio.R
import com.zenith.audio.mic.MicAudioRecorder
import com.zenith.audio.mic.MicSettingsStore
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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

    /** What happened to a RESULT when the IME tried to insert it. */
    private enum class CommitOutcome { INSERTED, EMPTY, NO_FIELD }

    private var state = State.IDLE
    private var statusText: TextView? = null
    private var micButton: Button? = null
    private var cancelButton: Button? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * AudioRecord open/close is blocking (tens to hundreds of ms). Keeping it
     * off the main thread avoids freezing the IME window during start/release.
     * A single thread keeps start/stop ordered.
     */
    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZenithImeCapture").apply { isDaemon = true }
    }

    @Volatile
    private var destroyed = false

    @Volatile
    private var client: DictationClient? = null
    private var recorder: MicAudioRecorder? = null

    /**
     * The connection that was focused when the user stopped recording. The
     * framework can finish the input (focus change, app closing the field)
     * while the bridge is still transcribing; the result must still target
     * that field instead of being thrown away.
     */
    private var pendingConnection: InputConnection? = null

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
                // Partials follow the focused field. If focus moves, the old
                // field's preview is cleared on finish and the final commit
                // inserts into the new field.
                val connection = previewConnection()
                runCatching { connection?.setComposingText(text, 1) }
            }
        }

        override fun onResult(text: String) {
            mainHandler.post {
                if (state != State.WAITING) {
                    Log.w(TAG, "Dropping result of ${text.length} chars: state=$state")
                    return@post
                }
                // Commit while the connection is still ours, before the
                // session teardown changes UI state.
                val outcome = commitText(text)
                endSession(clearPreview = outcome != CommitOutcome.INSERTED)
                renderIdle()
                if (outcome == CommitOutcome.EMPTY) {
                    setStatus(getString(R.string.ime_status_no_text))
                }
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

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice, null)
        statusText = view.findViewById(R.id.ime_status)
        micButton = view.findViewById(R.id.ime_mic)
        cancelButton = view.findViewById(R.id.ime_cancel)

        cancelButton?.setOnClickListener {
            cancelDictation(clearPreview = true, notify = true)
        }
        // Tap to start, tap again to stop. The session also stops by itself
        // after two seconds without speech (SilenceDetector), so the button is
        // never a hold gesture.
        micButton?.setOnClickListener { button ->
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            when (state) {
                State.IDLE -> startDictation()
                State.RECORDING -> finishRecording()
                State.CONNECTING -> cancelDictation(clearPreview = true, notify = false)
                State.WAITING -> Unit
            }
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
        // onFinishInput fires when the field loses its connection, which can
        // happen while the bridge is still transcribing after the user tapped
        // stop. Keep that utterance alive and remember its connection so the
        // result can still be committed; only a live capture is cancelled.
        if (state == State.WAITING) {
            if (pendingConnection == null) {
                pendingConnection = currentInputConnection
            }
            // The field being finished must not keep our provisional underline;
            // the final text will go to the next focused field (or to this one
            // through the saved connection when nothing else has focus).
            clearComposing(currentInputConnection)
        } else {
            cancelDictation(clearPreview = true, notify = false)
        }
        super.onFinishInput()
    }

    /**
     * Hiding the keyboard (Back, switching IMEs, the app hiding it) is not a
     * finishInput event. Without this the microphone and the TCP session would
     * keep running with no visible UI.
     */
    override fun onFinishInputView(finishInput: Boolean) {
        cancelIfCapturing()
        super.onFinishInputView(finishInput)
    }

    override fun onWindowHidden() {
        cancelIfCapturing()
        super.onWindowHidden()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        destroyed = true
        cancelDictation(clearPreview = true, notify = false)
        captureExecutor.shutdown()
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    private fun startDictation() {
        if (state != State.IDLE) return
        pendingConnection = null

        val host = DictationPrefs.activeHost(this)
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

        val port = DictationPrefs.port(this)
        val token = DictationPrefs.token(this)

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
        if (destroyed) return
        val session = client ?: return
        captureExecutor.execute {
            // The session may have been cancelled while this task was queued.
            if (destroyed || client !== session) return@execute

            // A fresh detector per session: the 2 s count starts with the
            // first spoken frame, not with capture.
            val silence = SilenceDetector()
            val capture = MicAudioRecorder(
                context = this,
                sendAudioPacket = { frame ->
                    session.enqueue(frame)
                    if (silence.onFrame(frame)) {
                        mainHandler.post { autoStopFromSilence() }
                    }
                },
                sendControlPacket = {},
                packetize = false
            )
            capture.applySettings(MicSettingsStore.read(this), restartIfRecording = false)
            val started = capture.start()

            // Cancelled while AudioRecord was opening: close it right here,
            // on the capture thread, without opening the microphone again.
            if (destroyed || client !== session) {
                capture.stop()
                return@execute
            }

            mainHandler.post {
                if (state != State.CONNECTING || client !== session) {
                    // The user released or cancelled while the device opened.
                    stopCapture(capture)
                    return@post
                }
                if (!started) {
                    stopCapture(capture)
                    failSession("microphone")
                    return@post
                }
                recorder = capture
                state = State.RECORDING
                setStatus(getString(R.string.ime_status_listening))
                updateSessionUi()
                mainHandler.removeCallbacks(maxDurationRunnable)
                mainHandler.postDelayed(maxDurationRunnable, MAX_DICTATION_MS)
            }
        }
    }

    /** Called from the recorder thread's silence detector, then posted here. */
    private fun autoStopFromSilence() {
        if (state == State.RECORDING) {
            Log.i(TAG, "Two seconds without speech; finishing dictation")
            finishRecording()
        }
    }

    private fun finishRecording() {
        if (state != State.RECORDING) return
        mainHandler.removeCallbacks(maxDurationRunnable)
        val capture = recorder
        recorder = null
        val session = client
        // The connection is valid now; it may not be when RESULT arrives.
        pendingConnection = currentInputConnection ?: pendingConnection
        state = State.WAITING
        setStatus(getString(R.string.ime_status_transcribing))
        updateSessionUi()

        if (capture == null) {
            session?.finish()
            scheduleResultTimeout()
            return
        }
        // Stop capture before END so the last 10 ms frame is not lost. The
        // stop runs off the main thread; only then is END sent.
        captureExecutor.execute {
            capture.stop()
            mainHandler.post {
                if (state == State.WAITING && client === session) {
                    session?.finish()
                    scheduleResultTimeout()
                }
            }
        }
    }

    private fun stopRecorder() {
        val capture = recorder ?: return
        recorder = null
        stopCapture(capture)
    }

    private fun stopCapture(capture: MicAudioRecorder) {
        try {
            captureExecutor.execute { capture.stop() }
        } catch (_: RejectedExecutionException) {
            // The service is being destroyed; release the device off the main
            // thread instead of blocking teardown.
            Thread({ capture.stop() }, "ZenithImeCaptureRelease").start()
        }
    }

    private fun cancelIfCapturing() {
        if (state == State.CONNECTING || state == State.RECORDING) {
            cancelDictation(clearPreview = true, notify = false)
        }
    }

    private fun scheduleResultTimeout() {
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
        stopRecorder()
        client?.cancel()
        client = null
        state = State.IDLE
        if (clearPreview) clearComposing()
        pendingConnection = null
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
        stopRecorder()
        client?.cancel()
        client = null
        state = State.IDLE
        if (clearPreview) clearComposing()
        pendingConnection = null
        updateSessionUi()
    }

    /**
     * Commits [text] to the currently focused field. If the framework already
     * finished that input, the connection saved when the user stopped is the
     * fallback; a refused insert is reported instead of silently dropped.
     */
    private fun commitText(text: String): CommitOutcome {
        val candidates = connectionCandidates()
        if (text.isBlank()) {
            // Empty results are common on silence; committing "" can clear a
            // selection, so only drop the provisional composing text.
            candidates.forEach { clearComposing(it) }
            return CommitOutcome.EMPTY
        }
        if (candidates.isEmpty()) {
            Log.w(TAG, "No input connection for ${text.length} chars")
            toast(getString(R.string.ime_toast_no_field))
            return CommitOutcome.NO_FIELD
        }
        for (connection in candidates) {
            val committed = runCatching { connection.commitText(text, 1) }
                .onFailure { Log.w(TAG, "commitText failed on a connection", it) }
                .getOrDefault(false)
            if (!committed) continue
            // If focus moved while transcribing, the old field may still show
            // our underlined preview; the committed text replaced it only on
            // the connection that accepted this insert.
            candidates.filter { it !== connection }.forEach { clearComposing(it) }
            Log.i(TAG, "Committed ${text.length} chars, state=$state")
            return CommitOutcome.INSERTED
        }
        Log.w(TAG, "commitText refused ${text.length} chars")
        toast(getString(R.string.ime_toast_no_field))
        return CommitOutcome.NO_FIELD
    }

    /**
     * The connection that should receive partials and the final text. The
     * focused field is preferred so the insert lands where the user is now;
     * the connection saved at stop is the fallback when no field is focused.
     */
    private fun connectionCandidates(): List<InputConnection> {
        return listOfNotNull(currentInputConnection, pendingConnection).distinct()
    }

    private fun previewConnection(): InputConnection? = connectionCandidates().firstOrNull()

    private fun clearComposing() {
        connectionCandidates().forEach { clearComposing(it) }
    }

    private fun clearComposing(connection: InputConnection?) {
        runCatching { connection?.setComposingText("", 1) }
    }

    private fun renderIdle() {
        state = State.IDLE
        updateSessionUi()
        val host = DictationPrefs.activeHost(this)
        val port = DictationPrefs.port(this)
        setStatus(
            if (host.isEmpty()) getString(R.string.ime_status_no_server)
            else getString(R.string.ime_status_idle, host, port)
        )
    }

    private fun updateSessionUi() {
        micButton?.setText(
            when (state) {
                State.IDLE -> R.string.ime_tap_to_talk
                State.CONNECTING -> R.string.ime_connecting
                State.RECORDING -> R.string.ime_tap_to_stop
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
        const val MAX_DICTATION_MS = 120_000L
        const val RESULT_TIMEOUT_MS = 120_000L
    }
}
