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

    fun host(context: Context): String =
        prefs(context).getString(KEY_SERVER_IP, "")?.trim().orEmpty()

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
