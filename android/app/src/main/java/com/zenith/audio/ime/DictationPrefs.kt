package com.zenith.audio.ime

import android.content.Context

/**
 * Preferences shared by the app and the IME.
 *
 * The IME does not discover or probe networks. The streaming service owns the
 * current endpoint: NetworkFailoverManager tracks it and
 * `AudioReceiverService` publishes it to [KEY_ACTIVE_SERVER_IP]. The IME reads
 * that single address; only when it does not exist yet (fresh install, app
 * never opened) does it fall back to the configured [KEY_SERVER_IP].
 *
 * The token is a LAN-scoped bearer secret. It is stored in the app's private
 * `SharedPreferences` and sent over the LAN without TLS, which is the same
 * trust model as ZAP (`docs/phone-dictation.md` §8).
 */
object DictationPrefs {
    const val PREFS = "zenith_prefs"
    const val KEY_SERVER_IP = "server_ip"
    const val KEY_ACTIVE_SERVER_IP = "active_server_ip"
    const val KEY_PORT = "dictation_port"
    const val KEY_TOKEN = "dictation_token"

    /**
     * The PC address the streaming service is currently using. This is the
     * only address the IME connects to: the app, not the IME, decides between
     * the Wi-Fi address and the VPN address.
     */
    fun activeHost(context: Context): String {
        val prefs = prefs(context)
        val active = prefs.getString(KEY_ACTIVE_SERVER_IP, "")?.trim().orEmpty()
        if (active.isNotEmpty()) return active
        return prefs.getString(KEY_SERVER_IP, "")?.trim().orEmpty()
    }

    /** Called by the streaming service whenever its endpoint changes. */
    fun saveActiveHost(context: Context, host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty() || trimmed == "127.0.0.1" || trimmed == "localhost") return
        prefs(context).edit().putString(KEY_ACTIVE_SERVER_IP, trimmed).apply()
    }

    fun port(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DictationProtocol.DEFAULT_PORT)
            .takeIf { it in 1..65535 } ?: DictationProtocol.DEFAULT_PORT

    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "").orEmpty()

    fun save(context: Context, port: Int, token: String) {
        prefs(context).edit()
            .putInt(KEY_PORT, port)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
