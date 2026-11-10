package com.zenith.audio.ime

import android.content.Context

/**
 * Preferences shared by the app and the IME.
 *
 * The token is a LAN-scoped bearer secret. It is stored in the app's private
 * `SharedPreferences` and sent over the LAN without TLS, which is the same
 * trust model as ZAP (`docs/phone-dictation.md` §8). Keeping the keys here
 * avoids the app and the IME drifting apart.
 */
object DictationPrefs {
    const val PREFS = "zenith_prefs"
    const val KEY_SERVER_IP = "server_ip"
    const val KEY_PORT = "dictation_port"
    const val KEY_TOKEN = "dictation_token"
    private const val KEY_KNOWN_HOSTS = "known_server_ips"

    /**
     * Every address the app has used for the PC, with the configured one
     * first. The IME tries them in order, so switching between Wi-Fi
     * (192.168.x.x) and the WireGuard VPN (10.181.104.x) needs no edit: the
     * first address that accepts the TCP connection wins. Both are stored by
     * the app's normal connect/discovery flow.
     */
    fun hosts(context: Context): List<String> {
        val prefs = prefs(context)
        val configured = prefs.getString(KEY_SERVER_IP, "")?.trim().orEmpty()
        val known = prefs.getStringSet(KEY_KNOWN_HOSTS, emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return (listOf(configured) + known)
            .filter { it.isNotEmpty() }
            .distinct()
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
