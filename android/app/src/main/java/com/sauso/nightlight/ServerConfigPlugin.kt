package com.sauso.nightlight

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Which Nightlight server this app talks to, chosen at runtime rather than baked
 * into the APK (the same first-run flow as the Home Assistant app).
 *
 * From the web side (both the bundled setup/error pages and the remote app):
 *
 *   Capacitor.Plugins.ServerConfig.get()            -> { url: string | null, tailscale: string | null }
 *   Capacitor.Plugins.ServerConfig.addEndpoint({ url })   -> { added }; saves the server's Tailscale address (see below)
 *   Capacitor.Plugins.ServerConfig.removeEndpoint()       -> { removed }
 *   Capacitor.Plugins.ServerConfig.save({ url })    -> validates + persists, rejects if unreachable
 *   Capacitor.Plugins.ServerConfig.save({ url, expectId })
 *                                                   -> the same, and refuses unless the server there
 *                                                      reports exactly that id (a found server, #20)
 *   Capacitor.Plugins.ServerConfig.clear()
 *   Capacitor.Plugins.ServerConfig.restart()        -> rebuilds the activity/bridge so the
 *                                                      current saved choice takes effect
 *
 * From the bundled setup page ONLY (www/index.html; any other page is refused, see callerIsBundledPage):
 *
 *   Capacitor.Plugins.ServerConfig.startDiscovery() -> { started, onWifi }; looks for servers on the LAN
 *   Capacitor.Plugins.ServerConfig.stopDiscovery()
 *   event serverFound { id, name, url, fingerprint } -> a server that answered its health check
 *   event serverLost  { id }                         -> its announcement went away
 *   event discoveryFailed { }                        -> looking could not start on this phone
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

        // The server's Tailscale address that goes with the saved server [primary], or null (see EndpointRules).
        fun getTailscaleUrl(context: Context, primary: String): String? {
            val url = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EndpointRules.key(primary), null)
            // Re-checked on read: only an address of the one accepted shape ever reaches the launch gate or the page.
            return if (EndpointRules.isTailscaleUrl(url)) url else null
        }

        // The server id the saved Tailscale address was saved for (see EndpointRules.idKey), or null.
        fun getTailscaleId(context: Context, primary: String): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EndpointRules.idKey(primary), null)

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

        // A server named by a tapped nightlight:// link, waiting for the web app to ask the person (#559).
        // This replaces the old saveUrl, which saved a link's server as the ACTIVE one (and added it to the
        // known list) with no question; nothing in native code switches server on its own any more. ONE slot,
        // replaced by a newer link, and it expires after two minutes so a request nobody answered cannot
        // turn up in a later session. In memory only: a request does not survive the app being killed.
        private const val PENDING_TTL_MS = 2 * 60 * 1000L
        private var pendingServer: String? = null
        private var pendingAtMs = 0L

        fun setPending(origin: String) {
            synchronized(this) {
                pendingServer = origin
                pendingAtMs = SystemClock.elapsedRealtime()
            }
        }

        // A newer link that names no usable server cancels whatever an earlier link left waiting.
        fun clearPending() {
            synchronized(this) { pendingServer = null }
        }

        // Hand the held request over exactly once: it is cleared whether or not it was still fresh.
        fun takePendingServer(): String? {
            synchronized(this) {
                val server = pendingServer
                val fresh = server != null && SystemClock.elapsedRealtime() - pendingAtMs <= PENDING_TTL_MS
                pendingServer = null
                return if (fresh) server else null
            }
        }

        private const val TAG = "ServerConfig"

        // ONE first-in-first-out thread for every session save, clear and read (SessionVault is not thread safe, and a save must
        // never overtake the clear that followed it). Process-wide so a recreated activity shares it. A daemon thread.
        private val sessionExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nightlight-session").apply { isDaemon = true } }

        // The plan's wording (#20, decision 3): plain, and it says what to do next.
        private const val NOT_THE_FOUND_SERVER = "That address is not the server we found. Search again."
        private const val NOT_ON_THIS_PAGE = "Looking for servers is only available on the setup screen."

        // How long the plugin thread waits for the main thread to read the WebView's URL (callerIsBundledPage).
        // The main thread answers in microseconds unless it is busy; if it is busy for a whole second the call is
        // refused (fails closed) and the page simply hides the search.
        private const val PAGE_CHECK_TIMEOUT_MS = 1_000L

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

    // "Look for my Nightlight" (#20). Made on the first startDiscovery, so a phone that never searches never touches
    // NsdManager. Volatile: plugin methods run on Capacitor's plugin thread, the lifecycle hooks below on the main one.
    @Volatile
    private var discovery: ServerDiscovery? = null

    // Events reach the page through notifyListeners, which must run on Capacitor's plugin thread: the listener lists
    // it walks are plain collections that addListener and removeListener (plugin methods) change on that thread.
    // The generation is checked again THERE, after any stop or start the page asked for has run, so a result from a
    // search the page has already ended is never delivered (plan review: the stop state machine). notifyListeners
    // is called without retainUntilConsumed: an event nobody is listening for is dropped, never replayed to a later
    // listener (that would be a card from an old search), which is why the page adds its listeners first.
    private val discoveryEvents = object : ServerDiscovery.Listener {
        override fun onServerFound(generation: Int, found: DiscoveryState.Found) {
            val data = JSObject()
            data.put("id", found.id)
            data.put("name", found.name)
            data.put("url", found.url)
            data.put("fingerprint", found.fingerprint)
            deliver(generation, "serverFound", data)
        }

        override fun onServerLost(generation: Int, id: String) {
            deliver(generation, "serverLost", JSObject().put("id", id))
        }

        // Looking could not start (NsdManager refused, or failed asynchronously after startDiscovery had already
        // resolved { started: true }). Code review of #20: logging alone left the page saying "Looking…" for two
        // minutes. The page shows its help at once and offers Search again. No details are sent: the message is
        // for the log, not the person.
        override fun onDiscoveryFailed(generation: Int, message: String) {
            Log.w(TAG, message)
            deliver(generation, "discoveryFailed", JSObject())
        }
    }

    private fun deliver(generation: Int, event: String, data: JSObject) {
        val b = bridge ?: return
        b.execute {
            if (discovery?.isCurrent(generation) == true) notifyListeners(event, data)
        }
    }

    /**
     * Whether the page calling us is the bundled setup page (plan decision 3a). See DiscoveryRules.isBundledPage
     * for the rule. WebView.getUrl must be called on the main thread, and plugin methods run on Capacitor's plugin
     * thread, so the check is handed to the main thread and awaited here; that keeps start and stop in the order
     * the page sent them (both run on this one thread). Fails closed: no bridge, no answer in time, or any error
     * means "not the bundled page".
     */
    private fun callerIsBundledPage(): Boolean {
        val b = bridge ?: return false
        val check = FutureTask(Callable {
            DiscoveryRules.isBundledPage(b.webView?.url, b.serverUrl, b.scheme, b.host)
        })
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) check.run() else Handler(Looper.getMainLooper()).post(check)
            check.get(PAGE_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        }
    }

    private fun knownServers(): MutableList<String> = knownServersOf(prefs())

    private fun rememberServer(url: String) {
        val list = knownServers()
        list.remove(url)
        list.add(0, url)
        prefs().edit().putString(KEY_KNOWN, JSONArray(list).toString()).apply()
    }

    // `url` is the saved (primary) address; `tailscale` is the Tailscale address the server told this phone about, or null.
    // Which one the page was loaded from is NOT said here: the page knows its own origin.
    @PluginMethod
    fun get(call: PluginCall) {
        val ret = JSObject()
        val saved = getSavedUrl(context)
        ret.put("url", saved)
        ret.put("tailscale", saved?.let { getTailscaleUrl(context, it) })
        call.resolve(ret)
    }

    // The server a tapped link asked for, if any (#559): { server: "<origin>" | null }. Returns it once and
    // clears it. The web app asks the person before anything changes; this method only reports.
    @PluginMethod
    fun takePending(call: PluginCall) {
        val ret = JSObject()
        ret.put("server", takePendingServer())
        call.resolve(ret)
    }

    // Previously used servers plus which one is currently active, for the setup
    // screen's one-tap list.
    @PluginMethod
    fun list(call: PluginCall) {
        val ret = JSObject()
        ret.put("servers", JSArray(knownServers()))
        val active = getSavedUrl(context)
        ret.put("active", active)
        ret.put("tailscale", active?.let { getTailscaleUrl(context, it) })
        call.resolve(ret)
    }

    // Drop a server from the remembered list (does not touch the active choice). Its Tailscale address goes with it:
    // it was only ever meaningful for that server.
    @PluginMethod
    fun forget(call: PluginCall) {
        val url = call.getString("url")
        if (url != null) {
            val list = knownServers()
            list.remove(url)
            prefs().edit().putString(KEY_KNOWN, JSONArray(list).toString()).remove(EndpointRules.key(url)).remove(EndpointRules.idKey(url)).apply()
            // The saved login for that address goes too (it was only ever meaningful for that server).
            sessionExecutor.execute { sessionVault.clear(url) }
        }
        call.resolve()
    }

    // The Tailscale address saved for the saved server, with the server id it was saved for.
    private fun forgetTailscale(edit: SharedPreferences.Editor, primary: String): SharedPreferences.Editor =
        edit.remove(EndpointRules.key(primary)).remove(EndpointRules.idKey(primary))

    private fun refusal(check: EndpointRules.AddCheck): String = when (check) {
        EndpointRules.AddCheck.NOT_A_TAILSCALE_ADDRESS -> "Not a Tailscale address"
        EndpointRules.AddCheck.NOT_THIS_PAGE -> "Not available on this page"
        EndpointRules.AddCheck.UNREADABLE_SERVER -> "Couldn't read this server's identity"
        EndpointRules.AddCheck.UNREACHABLE_ADDRESS -> "Couldn't reach that Tailscale address"
        EndpointRules.AddCheck.DIFFERENT_SERVER -> "That address is a different server"
        EndpointRules.AddCheck.OK -> ""
    }

    // addEndpoint({ url }): the server on screen has said "you can also reach me at <url>" (the web app read it from
    // GET /api/remote-access/tailscale/address while signed in). Saved only when ALL of these hold, each fails closed
    // (the decisions are EndpointRules.checkPage and checkIdentity, unit-tested on the JVM):
    //   - the url is exactly `https://<name>.ts.net`: no port, path, credentials or IP;
    //   - the page is the saved server or the Tailscale address already saved for it (bridge.serverUrl), so the bundled setup
    //     page and anything else are refused;
    //   - the page's server and the url answer /api/health with the SAME id (EndpointProbe): an address that merely looks
    //     right is not saved for a server that is not this one. The id is public, so this is a claim, not proof (see
    //     checkIdentity); it is saved with the address and re-checked each time the address is opened.
    // Resolves { added }: true when this is new or different, false when it was already saved (so the app tells the
    // person once). Nothing is switched or restarted here: the address is used the next time the app starts.
    @PluginMethod
    fun addEndpoint(call: PluginCall) {
        val url = call.getString("url")?.trim()?.trimEnd('/')
        val primary = getSavedUrl(context)
        val page = bridge?.serverUrl
        val first = EndpointRules.checkPage(url, primary, primary?.let { getTailscaleUrl(context, it) }, page)
        if (first != EndpointRules.AddCheck.OK || url == null || primary == null || page == null) {
            call.reject(refusal(if (first == EndpointRules.AddCheck.OK) EndpointRules.AddCheck.NOT_THIS_PAGE else first))
            return
        }
        Thread {
            val reference = EndpointProbe.fetchHealth(page, 4000)
            val candidate = EndpointProbe.fetchHealth(url, 5000)
            val second = EndpointRules.checkIdentity(reference, candidate)
            if (second != EndpointRules.AddCheck.OK || candidate == null) {
                call.reject(refusal(if (second == EndpointRules.AddCheck.OK) EndpointRules.AddCheck.UNREACHABLE_ADDRESS else second))
                return@Thread
            }
            val previous = getTailscaleUrl(context, primary)
            prefs().edit().putString(EndpointRules.key(primary), url).putString(EndpointRules.idKey(primary), candidate.id).apply()
            call.resolve(JSObject().put("added", previous != url))
        }.start()
    }

    // removeEndpoint(): the server has said it no longer offers a Tailscale address (Tailscale was turned off there).
    // Same page rule as addEndpoint. Resolves { removed }.
    @PluginMethod
    fun removeEndpoint(call: PluginCall) {
        val primary = getSavedUrl(context)
        val saved = primary?.let { getTailscaleUrl(context, it) }
        if (primary == null || !EndpointRules.isActivePage(primary, saved, bridge?.serverUrl)) {
            call.reject(refusal(EndpointRules.AddCheck.NOT_THIS_PAGE))
            return
        }
        if (saved != null) forgetTailscale(prefs().edit(), primary).apply()
        call.resolve(JSObject().put("removed", saved != null))
    }

    // ---- One sign-in across the two addresses (owner, 2026-11-06; plan phase D) ----
    //
    // The session token is also kept encrypted in app-private storage (SessionVault, an Android Keystore key), one entry per
    // SAVED address, so the page on the server's Tailscale address can sign in without asking again. The rules are in
    // SessionRules (pure, JVM-tested); this is the glue. Three things the review of the plan fixed that the glue must keep:
    //  - a server's id is PUBLIC and proves nothing, so a session is handed ONLY to the page that IS the saved Tailscale
    //    address, never to the saved address or any other page, and only while everything the entry was stamped with still
    //    matches (SessionRules.checkDelivery);
    //  - the page cannot choose an id or an address: the bindings come from what the app itself saved;
    //  - save, clear and key creation run on ONE first-in-first-out thread (sessionExecutor), so they cannot reorder or race.
    // Every call is also refused unless the page the WebView is really showing has the origin the bridge was built for
    // (callerIsServerPage): the old-WebView fallback bridge can be called by any frame.

    private val sessionVault: SessionVault by lazy {
        SessionVault(PrefsKeyValueStore(context.getSharedPreferences(SessionVault.PREFS_FILE, Context.MODE_PRIVATE)), KeystoreSessionCipher())
    }

    // The saved address and the Tailscale address and id saved for it, right now; null when nothing is saved.
    private fun currentBindings(): SessionRules.Bindings? {
        val primary = getSavedUrl(context) ?: return null
        val tailscale = getTailscaleUrl(context, primary)
        return SessionRules.Bindings(primary, tailscale, if (tailscale != null) getTailscaleId(context, primary) else null)
    }

    // The same shape of check as callerIsBundledPage: the WebView's URL can only be read on the main thread.
    private fun callerIsServerPage(): Boolean {
        val b = bridge ?: return false
        val check = FutureTask(Callable { SessionRules.sameOrigin(b.webView?.url, b.serverUrl) })
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) check.run() else Handler(Looper.getMainLooper()).post(check)
            check.get(PAGE_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        }
    }

    // saveSession({ token }): the web app signed in (or confirmed its sign-in) and asks the app to keep the token. From the
    // saved address or its Tailscale address only; the token must look like a session token; the entry is stamped with the
    // addresses and id in force NOW (a first sign-in comes before the Tailscale address is learned, so the web app saves
    // again after it has: an entry with no Tailscale binding is never handed out). Resolves { saved }.
    @PluginMethod
    fun saveSession(call: PluginCall) {
        val token = call.getString("token")
        val current = currentBindings()
        if (current == null || !EndpointRules.isActivePage(current.savedUrl, current.tailscaleUrl, bridge?.serverUrl) || !callerIsServerPage()) {
            call.reject("Not available on this page")
            return
        }
        if (!SessionRules.isPlausibleToken(token)) {
            call.reject("Not a session token")
            return
        }
        val entry = SessionRules.Entry(token!!, current)
        sessionExecutor.execute { call.resolve(JSObject().put("saved", sessionVault.save(entry))) }
    }

    // getSession(): the page asks for a saved session. Resolves { token } with the token, or { token: null } for EVERY refusal
    // (the page learns nothing about why). Only the page that is the saved Tailscale address can get one: its own live
    // /api/health id is read first (off the executor: a slow network must not hold up saves and clears), then the entry is
    // read and checked against the bindings as they are at that moment.
    @PluginMethod
    fun getSession(call: PluginCall) {
        val none = JSObject().put("token", JSONObject.NULL)
        val current = currentBindings()
        val page = bridge?.serverUrl
        if (current == null || page == null || page != current.tailscaleUrl || !callerIsServerPage()) {
            call.resolve(none)
            return
        }
        Thread {
            val liveId = EndpointProbe.fetchHealth(page, 3000)?.id
            sessionExecutor.execute {
                val now = currentBindings()
                val entry = if (now != null) sessionVault.load(now.savedUrl) else null
                val verdict = SessionRules.checkDelivery(page, now, entry, liveId)
                if (verdict == SessionRules.Delivery.OK && entry != null) {
                    call.resolve(JSObject().put("token", entry.token))
                } else {
                    call.resolve(none)
                }
            }
        }.start()
    }

    // clearSession({ token? }): the web app signed out, or the server rejected a token. Needs no network. With a token, only
    // if it is the one stored (a rejection of an old token must not delete a newer login). Resolves { cleared }.
    @PluginMethod
    fun clearSession(call: PluginCall) {
        val current = currentBindings()
        if (current == null || !EndpointRules.isActivePage(current.savedUrl, current.tailscaleUrl, bridge?.serverUrl) || !callerIsServerPage()) {
            call.reject("Not available on this page")
            return
        }
        val token = call.getString("token")
        sessionExecutor.execute { call.resolve(JSObject().put("cleared", sessionVault.clear(current.savedUrl, token))) }
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
    //
    // expectId (optional, #20): set when the setup page connects to a server that "Look for my Nightlight" found.
    // The server at that address must then answer as a Nightlight whose id is exactly that one, or nothing is saved.
    // Why (plan review, High): the check below accepts any 200 reply containing "ok", and the address a card shows
    // may have changed hands since it was verified (a DHCP lease moving to another device). Design 7.5 step 6:
    // re-check at the moment of use. That check is HealthProbe.checkUrl, the same one discovery used, NOT the
    // HttpURLConnection below (code review of #20): HttpURLConnection follows redirects by default, so a redirect to
    // the real server could pass the id check while the ORIGINAL address was saved, and it would store any
    // Set-Cookie the answer carried through Capacitor's process-wide cookie handler. HealthProbe follows no redirect
    // (the 200 must come from that address itself), sends and stores no cookies, uses no proxy, and accepts only the
    // exact `http://<private IPv4>:<port>` form discovery produces. Without expectId (a typed address, a saved-server
    // tap) this behaves exactly as before. Present but not a string is refused, not ignored, so a caller that meant
    // to ask for the check cannot silently skip it.
    @PluginMethod
    fun save(call: PluginCall) {
        val raw = call.getString("url")?.trim().orEmpty()
        if (raw.isEmpty()) {
            call.reject("Enter your server address")
            return
        }
        val rawExpectId = call.data.opt("expectId")
        if (rawExpectId != null && rawExpectId !is String) {
            call.reject(NOT_THE_FOUND_SERVER)
            return
        }
        val expectId = rawExpectId as String?
        if (expectId != null) {
            saveFoundServer(call, raw, expectId)
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

    // save({ url, expectId }): see the comment on save. Saved under the URL exactly as discovery reported it.
    // Unreachable gets the same words as a typed address that does not answer; any other failure (another server,
    // a redirect, not a Nightlight, not a found-server address at all) gets "not the server we found".
    private fun saveFoundServer(call: PluginCall, url: String, expectId: String) {
        Thread {
            val result = HealthProbe().checkUrl(url, expectId)
            when (result.outcome) {
                HealthProbe.Outcome.VERIFIED -> {
                    prefs().edit().putString(KEY_URL, url).apply()
                    rememberServer(url)
                    val ret = JSObject()
                    ret.put("url", url)
                    call.resolve(ret)
                }
                HealthProbe.Outcome.CONNECT_FAILED, HealthProbe.Outcome.TIMED_OUT ->
                    call.reject("Couldn't reach a Nightlight server at that address")
                else -> call.reject(NOT_THE_FOUND_SERVER)
            }
        }.start()
    }

    // Clears only the ACTIVE choice, deliberately keeping the remembered list -
    // "Change server" should land on a screen offering your other servers, not a
    // blank slate (use forget() to actually drop one from the list).
    @PluginMethod
    fun clear(call: PluginCall) {
        // The Tailscale address goes with it: it belongs to the server being left (the review of the Tailscale fallback: it
        // must not wait around for whatever is saved at that address next).
        val leaving = getSavedUrl(context)
        val edit = prefs().edit().remove(KEY_URL)
        if (leaving != null) forgetTailscale(edit, leaving)
        edit.apply()
        // ...and so does its saved login: "Change server" leaves that server.
        if (leaving != null) sessionExecutor.execute { sessionVault.clear(leaving) }
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

    // "Look for my Nightlight" (#20). Starts a search and resolves { started, onWifi } at once; found servers
    // arrive later as serverFound events, and only after they answered the health check (ServerDiscovery). It only
    // SUGGESTS: nothing is saved or switched until the person taps a card, which goes through save({ url, expectId })
    // and restart() like any other connect (the #559 principle).
    //
    // With no Wi-Fi or Ethernet (mobile data only) nothing is started and it resolves { started: false,
    // onWifi: false }, so the page can say so (design section 10): the server cannot be heard from there.
    //
    // Refused on any page but the bundled setup page (plan decision 3a, callerIsBundledPage), and when this phone
    // has no service discovery. The page hides the whole section when the call is refused, which is also what
    // happens on iOS, where the method does not exist yet.
    @PluginMethod
    fun startDiscovery(call: PluginCall) {
        if (!callerIsBundledPage()) {
            call.reject(NOT_ON_THIS_PAGE)
            return
        }
        val d = discovery ?: ServerDiscovery(context).also { discovery = it }
        if (!d.isAvailable) {
            call.reject("Looking for servers isn't available on this phone.")
            return
        }
        val ret = JSObject()
        val onWifi = d.isOnLocalNetwork()
        ret.put("onWifi", onWifi)
        if (!onWifi) {
            d.stop()
            ret.put("started", false)
            call.resolve(ret)
            return
        }
        if (d.start(discoveryEvents) == null) {
            call.reject("Couldn't start looking for servers.")
            return
        }
        ret.put("started", true)
        call.resolve(ret)
    }

    // Gated like startDiscovery: a search is the bundled page's alone, start to finish.
    @PluginMethod
    fun stopDiscovery(call: PluginCall) {
        if (!callerIsBundledPage()) {
            call.reject(NOT_ON_THIS_PAGE)
            return
        }
        discovery?.stop()
        call.resolve()
    }

    // Capacitor calls this when the WebView starts loading ANY page (BridgeWebViewClient.onPageStarted ->
    // Bridge.reset), dropping every listener. With nobody left to tell, a search has no reason to go on: this makes
    // "leaving the page stops discovery" hold even if the page never got to call stopDiscovery (a reload, a crash
    // in its script). The page's own pagehide handler is the first line; this is the second.
    override fun removeAllListeners() {
        super.removeAllListeners()
        onPluginThread { it.stop() }
    }

    // The app went to the background: stop looking, so nothing keeps probing the network while nobody can see the
    // results. The page shows "Search again" when it is visible again.
    override fun handleOnStop() {
        onPluginThread { it.stop() }
        super.handleOnStop()
    }

    // Released on the plugin thread too, after anything already queued there. Bridge.onDestroy calls this and then
    // quitSafely() on that thread, which still runs every message already due, so the release does run.
    override fun handleOnDestroy() {
        val d = discovery
        discovery = null
        if (d != null) onPluginThread(d) { it.release() }
        super.handleOnDestroy()
    }

    // The three lifecycle callers above run on the MAIN thread, and stop()/release() take ServerDiscovery's lock,
    // which the plugin thread holds across NsdManager binder calls inside start() (second review of #20: a slow
    // binder call there would stall the main thread, an ANR risk). So the work is handed to Capacitor's plugin
    // thread: Bridge.execute posts to the same single "CapacitorPlugins" HandlerThread that runs every plugin call
    // (Bridge.java: callPluginMethod and execute both post to taskHandler), which never blocks the main thread and
    // keeps these stops in order with the page's own startDiscovery/stopDiscovery calls. Without a bridge (never
    // expected) a short-lived thread does it. Glue: no JVM test reaches this.
    private fun onPluginThread(target: ServerDiscovery? = discovery, action: (ServerDiscovery) -> Unit) {
        val d = target ?: return
        val b = bridge
        if (b != null) {
            b.execute { action(d) }
        } else {
            Thread({ action(d) }, "nightlight-discovery-lifecycle").start()
        }
    }
}
