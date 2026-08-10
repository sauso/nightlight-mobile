package com.sauso.nightlight

import android.content.Context
import android.content.SharedPreferences
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Which Nightlight server this app talks to, chosen at runtime rather than baked
 * into the APK (the same first-run flow as the Home Assistant app).
 *
 * From the web side (both the bundled setup/error pages and the remote app):
 *
 *   Capacitor.Plugins.ServerConfig.get()            -> { url: string | null }
 *   Capacitor.Plugins.ServerConfig.save({ url })    -> validates + persists, rejects if unreachable
 *   Capacitor.Plugins.ServerConfig.clear()
 *   Capacitor.Plugins.ServerConfig.restart()        -> rebuilds the activity/bridge so the
 *                                                      current saved choice takes effect
 *
 * MainActivity reads the saved value in onCreate and points the Capacitor bridge at
 * it; no saved value means the bundled first-run setup page (www/) loads instead.
 */
@CapacitorPlugin(name = "ServerConfig")
class ServerConfigPlugin : Plugin() {

    companion object {
        private const val PREFS = "nightlight_server"
        private const val KEY_URL = "server_url"
        // Every server that has ever passed validation, most recently used first -
        // lets the setup screen offer previous servers as one-tap choices instead
        // of making the person retype an address they've already proven works.
        private const val KEY_KNOWN = "known_servers"

        fun getSavedUrl(context: Context): String? {
            val url = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null)
            return if (url.isNullOrBlank()) null else url
        }

        private fun knownServersOf(prefs: SharedPreferences): MutableList<String> {
            val list = mutableListOf<String>()
            try {
                val arr = JSONArray(prefs.getString(KEY_KNOWN, "[]"))
                for (i in 0 until arr.length()) list.add(arr.getString(i))
            } catch (e: Exception) {
                // Corrupt prefs entry - treat as empty.
            }
            return list
        }

        // Persist a server as the active one (and remember it), from native code that already knows
        // the address is good — e.g. MainActivity switching to the server carried in a tapped
        // deep link. Mirrors what the instance save() writes, minus the network validation the
        // caller has already done.
        fun saveUrl(context: Context, url: String) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val list = knownServersOf(p)
            list.remove(url)
            list.add(0, url)
            p.edit().putString(KEY_URL, url).putString(KEY_KNOWN, JSONArray(list).toString()).apply()
        }

        // Quick liveness check used at launch (see MainActivity) to decide whether to point the
        // app at the saved server or drop to the bundled setup screen. Must be called OFF the main
        // thread — HttpURLConnection can sit the full timeout on an unreachable host.
        fun isReachable(url: String, timeoutMs: Int): Boolean {
            return try {
                val conn = URL("$url/api/health").openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "GET"
                val ok = conn.responseCode == 200
                conn.disconnect()
                ok
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun knownServers(): MutableList<String> = knownServersOf(prefs())

    private fun rememberServer(url: String) {
        val list = knownServers()
        list.remove(url)
        list.add(0, url)
        prefs().edit().putString(KEY_KNOWN, JSONArray(list).toString()).apply()
    }

    @PluginMethod
    fun get(call: PluginCall) {
        val ret = JSObject()
        ret.put("url", getSavedUrl(context))
        call.resolve(ret)
    }

    // Previously used servers plus which one is currently active, for the setup
    // screen's one-tap list.
    @PluginMethod
    fun list(call: PluginCall) {
        val ret = JSObject()
        ret.put("servers", JSArray(knownServers()))
        ret.put("active", getSavedUrl(context))
        call.resolve(ret)
    }

    // Drop a server from the remembered list (does not touch the active choice).
    @PluginMethod
    fun forget(call: PluginCall) {
        val url = call.getString("url")
        if (url != null) {
            val list = knownServers()
            list.remove(url)
            prefs().edit().putString(KEY_KNOWN, JSONArray(list).toString()).apply()
        }
        call.resolve()
    }

    // Confirms the address actually hosts a reachable Nightlight server (its
    // unauthenticated /api/health endpoint) before persisting anything - a typo'd
    // address that saved anyway would boot the app onto a dead URL every launch,
    // with only the error page as a way back.
    //
    // Scheme handling: an explicit https:// or http:// is respected as typed. A bare
    // address (domain, IP, IP:port) tries https first, then falls back to http -
    // self-hosted servers are commonly exposed plain-http on the LAN with no reverse
    // proxy in front (e.g. 192.168.1.50:4000), and the wrong-scheme attempt fails
    // fast (TLS handshake against a plaintext port doesn't sit out the timeout).
    @PluginMethod
    fun save(call: PluginCall) {
        val raw = call.getString("url")?.trim().orEmpty()
        if (raw.isEmpty()) {
            call.reject("Enter your server address")
            return
        }
        val bare = raw.trimEnd('/')
        val candidates = if (bare.contains("://")) {
            if (!bare.startsWith("https://") && !bare.startsWith("http://")) {
                call.reject("The address must start with https:// or http://")
                return
            }
            listOf(bare)
        } else {
            listOf("https://$bare", "http://$bare")
        }

        // Plain thread rather than blocking a Capacitor thread: HttpURLConnection
        // can sit the full timeout on an unreachable host.
        Thread {
            for (url in candidates) {
                try {
                    val conn = URL("$url/api/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    if (code == 200 && body.contains("\"ok\"")) {
                        prefs().edit().putString(KEY_URL, url).apply()
                        rememberServer(url)
                        val ret = JSObject()
                        ret.put("url", url)
                        call.resolve(ret)
                        return@Thread
                    }
                } catch (e: Exception) {
                    // Fall through to the next candidate scheme, if any.
                }
            }
            call.reject("Couldn't reach a Nightlight server at that address")
        }.start()
    }

    // Clears only the ACTIVE choice, deliberately keeping the remembered list -
    // "Change server" should land on a screen offering your other servers, not a
    // blank slate (use forget() to actually drop one from the list).
    @PluginMethod
    fun clear(call: PluginCall) {
        prefs().edit().remove(KEY_URL).apply()
        call.resolve()
    }

    // Recreating the activity rebuilds the Capacitor bridge from scratch, which is
    // what re-runs MainActivity's saved-server check - the supported way to change
    // a bridge's server URL, which is otherwise fixed for the bridge's lifetime.
    @PluginMethod
    fun restart(call: PluginCall) {
        call.resolve()
        activity?.runOnUiThread { activity.recreate() }
    }
}
